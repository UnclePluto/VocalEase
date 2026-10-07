package com.vocaease.patient.feature.training

import androidx.lifecycle.ViewModel
import com.vocaease.patient.core.database.AccountScopedDraftStorage
import com.vocaease.patient.core.database.SessionBindingMismatchException
import com.vocaease.patient.core.database.StaleAccountScopeException
import com.vocaease.patient.core.media.RecordingCoordinator
import com.vocaease.patient.core.media.RecordingStagingIdentity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RecordingDraftInfo(
    val draftId: String,
    val songTitle: String,
    val totalDurationMillis: Long,
    val accountScopeHash: String = "",
    val sessionId: String = "",
    val creationKey: String = "",
    val songId: String = "",
)

interface RecordingDraftGateway {
    suspend fun load(draftId: String): RecordingDraftInfo
    suspend fun acknowledgeHandoff(draftId: String)
    suspend fun markInterrupted(draftId: String, reason: RecordingInterruption, durationMillis: Long)
    suspend fun discard(draftId: String) = Unit
}

class AccountScopedRecordingDraftGateway(
    private val storage: AccountScopedDraftStorage,
) : RecordingDraftGateway {
    override suspend fun load(draftId: String): RecordingDraftInfo {
        val draft = storage.findDraft(draftId) ?: throw SessionBindingMismatchException()
        val preparation = storage.findPreparationDraft(draftId) ?: throw SessionBindingMismatchException()
        if (preparation.serverSessionId != draft.sessionId || preparation.songId != draft.songId) {
            throw SessionBindingMismatchException()
        }
        return RecordingDraftInfo(
            draftId,
            preparation.songTitle,
            preparation.songDurationSeconds.coerceAtLeast(0) * 1_000L,
            storage.accountScopeHash,
            draft.sessionId,
            draft.creationKey,
            draft.songId,
        )
    }

    override suspend fun acknowledgeHandoff(draftId: String) {
        if (!storage.acknowledgePreparationHandoff(draftId)) throw SessionBindingMismatchException()
    }

    override suspend fun markInterrupted(
        draftId: String,
        reason: RecordingInterruption,
        durationMillis: Long,
    ) {
        storage.markRecordingInterrupted(draftId, durationMillis, reason.safeMessage())
    }
    override suspend fun discard(draftId: String) = storage.deleteReviewDraft(draftId)
}

class RecordingViewModel(
    private val draftId: String,
    private val coordinator: RecordingCoordinator,
    private val gateway: RecordingDraftGateway,
    private val countdownTick: suspend (Int) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val cleanupDispatcher: CoroutineDispatcher = dispatcher,
    private val referenceRepository: ReferencePitchRepository? = null,
    private val lyricsRepository: LyricsRepository? = null,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lifecycleMutex = Mutex()
    private val exitMutex = Mutex()
    private val acknowledged = AtomicBoolean()
    private val left = AtomicBoolean()
    private var ticker: Job? = null
    private var lyricsJob: Job? = null
    private var lyricsSongId: String? = null
    private var referenceJob: Job? = null
    @Volatile private var cleanupJob: Job? = null
    private val mutableState = MutableStateFlow(RecordingUiState())
    val state: StateFlow<RecordingUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            coordinator.state.collect(::onRecordingState)
        }
    }

    suspend fun start() = lifecycleMutex.withLock {
        if (left.get() || mutableState.value.songTitle.isNotEmpty()) return@withLock
        try {
            val info = gateway.load(draftId)
            updateState { it.copy(
                songTitle = info.songTitle,
                totalDurationMillis = info.totalDurationMillis,
            ) }
            lyricsSongId = info.songId
            retryLyrics()
            retryReferencePitch()
            if (info.accountScopeHash.isNotBlank() && info.sessionId.isNotBlank() && info.creationKey.isNotBlank()) {
                coordinator.takeOver(
                    draftId,
                    RecordingStagingIdentity(info.accountScopeHash, draftId, info.sessionId, info.creationKey),
                )
            } else {
                coordinator.takeOver(draftId)
            }
            for (second in 3 downTo 1) countdownTick(second)
            coordinator.onCountdownFinished()
        } catch (error: CancellationException) {
            throw error
        } catch (_: StaleAccountScopeException) {
            interruptLocally(RecordingInterruption.ACCOUNT_CHANGED)
            runCatching { coordinator.interrupt(RecordingInterruption.ACCOUNT_CHANGED) }
        } catch (_: Exception) {
            interruptLocally(RecordingInterruption.CAMERA)
            runCatching { coordinator.interrupt(RecordingInterruption.CAMERA) }
        }
    }

    fun retryLyrics() {
        val repository = lyricsRepository ?: return
        val id = lyricsSongId ?: return
        if (left.get()) return
        lyricsJob?.cancel()
        lyricsJob = scope.launch {
            updateState { it.copy(lyrics = LyricsState.Loading) }
            val result = repository.load(id)
            if (!left.get()) updateState { it.copy(lyrics = result) }
        }
    }

    fun retryReferencePitch() {
        if (left.get()) return
        val id = lyricsSongId ?: return
        val repository = referenceRepository ?: return
        val binding = coordinator.playbackBinding
        val version = binding?.referenceVersion ?: return
        if (!binding.alignmentVerified || binding.accompanimentOffsetMs == null) {
            updateState { it.copy(referencePitch = ReferencePitchState.Unaligned) }
            return
        }
        referenceJob?.cancel()
        referenceJob = scope.launch {
            updateState { it.copy(referencePitch = ReferencePitchState.Loading) }
            val result = repository.load(id, version)
            if (!left.get()) updateState { it.copy(referencePitch = result) }
        }
    }

    suspend fun requestExit() = exitMutex.withLock {
        if (left.get() || mutableState.value.showExitConfirmation) return@withLock
        updateState { it.copy(exitBusy = true) }
        try {
            val active = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                coordinator.state.first { it !is RecordingState.Countdown && it !== RecordingState.Starting }
            }
            val paused = when (active) {
                is RecordingState.Recording -> coordinator.pause()
                is RecordingState.Paused -> true
                else -> false
            }
            if (active == null || (!paused && coordinator.state.value is RecordingState.Recording)) {
                coordinator.interrupt(RecordingInterruption.CAMERA)
            }
            if (!left.get()) updateState { it.copy(showExitConfirmation = true, canResumeRecording = paused) }
        } finally { updateState { it.copy(exitBusy = false) } }
    }

    suspend fun cancelExit() = exitMutex.withLock {
        if (left.get() || !mutableState.value.showExitConfirmation) return@withLock
        updateState { it.copy(exitBusy = true) }
        try {
            val resumed = coordinator.resume()
            updateState { it.copy(
                showExitConfirmation = false,
                errorMessage = if (resumed) null else "录制已中断，请返回准备页重新开始",
                navigateReviewDraftId = if (coordinator.state.value is RecordingState.Reviewable) draftId else null,
            ) }
        } finally { updateState { it.copy(exitBusy = false) } }
    }

    suspend fun confirmExit(): Boolean = exitMutex.withLock {
        if (!mutableState.value.showExitConfirmation || mutableState.value.exitBusy) return@withLock false
        updateState { it.copy(exitBusy = true, canResumeRecording = false) }
        try {
            leave()
            gateway.discard(draftId)
            updateState { it.copy(showExitConfirmation = false, navigateReviewDraftId = null) }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            updateState { it.copy(errorMessage = "录制已停止，清理失败，请再次确认返回") }
            false
        } finally { updateState { it.copy(exitBusy = false) } }
    }

    suspend fun stop() = exitMutex.withLock {
        if (!left.get() && !mutableState.value.showExitConfirmation && !mutableState.value.exitBusy) coordinator.stop()
    }

    suspend fun onHostStopped() = coordinator.interrupt(RecordingInterruption.CAMERA)

    suspend fun onAudioFocusLost() = coordinator.interrupt(RecordingInterruption.AUDIO)

    suspend fun leave() {
        if (!left.compareAndSet(false, true)) {
            cleanupJob?.join()
            return
        }
        cancelAndClose()
    }

    fun disposeRoute() {
        if (!left.compareAndSet(false, true)) return
        if (mutableState.value.recordingState is RecordingState.Reviewable) {
            coordinator.close()
            ticker?.cancel()
            lyricsJob?.cancel()
            referenceJob?.cancel()
            return
        }
        val cleanupScope = CoroutineScope(SupervisorJob() + cleanupDispatcher)
        cleanupJob = cleanupScope.launch {
            try {
                cancelAndClose()
            } finally {
                cleanupScope.cancel()
            }
        }
    }

    suspend fun switchMode(mode:com.vocaease.patient.core.media.SongPlaybackMode) = exitMutex.withLock {
        if(mutableState.value.switchingMode || mutableState.value.showExitConfirmation || mutableState.value.exitBusy || coordinator.state.value !is RecordingState.Recording) return@withLock
        updateState { it.copy(switchingMode=true) }
        try { coordinator.switchMode(mode);updateState { it.copy(activeMode=coordinator.activeMode.value) } }
        finally { updateState { it.copy(switchingMode=false) } }
    }

    fun consumeReviewNavigation() {
        updateState { it.copy(navigateReviewDraftId = null) }
    }

    override fun onCleared() {
        disposeRoute()
        scope.cancel()
    }

    private suspend fun cancelAndClose() {
        try {
            runCatching { coordinator.interrupt(RecordingInterruption.CANCELLED) }
            runCatching {
                gateway.markInterrupted(
                    draftId,
                    RecordingInterruption.CANCELLED,
                    coordinator.recordingDurationMillis,
                )
            }
        } finally {
            coordinator.close()
            ticker?.cancel()
            lyricsJob?.cancel()
            referenceJob?.cancel()
        }
    }

    private suspend fun onRecordingState(recordingState: RecordingState) {
        if (left.get()) return
        updateState {
            it.copy(
                recordingState = recordingState,
                keepScreenOn = recordingState === RecordingState.Starting ||
                    recordingState is RecordingState.Recording || recordingState is RecordingState.Paused || recordingState === RecordingState.Finalizing,
                recordingDurationMillis = coordinator.recordingDurationMillis,
                playbackPositionMillis = coordinator.playbackPositionMillis,
            )
        }
        when (recordingState) {
            is RecordingState.Recording -> {
                if (acknowledged.compareAndSet(false, true)) {
                    try {
                        gateway.acknowledgeHandoff(draftId)
                    } catch (_: StaleAccountScopeException) {
                        interruptLocally(RecordingInterruption.ACCOUNT_CHANGED)
                        coordinator.interrupt(RecordingInterruption.ACCOUNT_CHANGED)
                        return
                    } catch (_: Exception) {
                        interruptLocally(RecordingInterruption.STORAGE)
                        coordinator.interrupt(RecordingInterruption.STORAGE)
                        return
                    }
                }
                startTicker(recordingState)
            }
            is RecordingState.Reviewable -> {
                ticker?.cancel()
                updateState {
                    it.copy(
                        recordingDurationMillis = recordingState.durationMillis,
                        navigateReviewDraftId = if (it.showExitConfirmation || it.exitBusy) null else draftId,
                    )
                }
            }
            is RecordingState.Interrupted -> {
                ticker?.cancel()
                runCatching {
                    gateway.markInterrupted(draftId, recordingState.reason, coordinator.recordingDurationMillis)
                }
                updateState { it.copy(errorMessage = recordingState.reason.patientMessage()) }
            }
            is RecordingState.Paused -> ticker?.cancel()
            else -> Unit
        }
    }

    private fun startTicker(generation: RecordingState.Recording) {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (coordinator.state.value is RecordingState.Recording) {
                val duration = coordinator.recordingDurationMillis
                val playbackPosition = coordinator.playbackPositionMillis
                updateState { current ->
                    if (current.recordingState == generation && coordinator.state.value == generation) {
                        current.copy(
                            recordingDurationMillis = duration,
                            playbackPositionMillis = playbackPosition,
                            activeMode = coordinator.activeMode.value,
                            patientPitch = coordinator.pitch.value,
                            playbackAnchors = coordinator.anchors,
                            pitchHistory = if(current.pitchHistory.lastOrNull()?.recordingMs == coordinator.pitch.value.recordingMs) current.pitchHistory else (current.pitchHistory + coordinator.pitch.value).takeLast(160),
                        )
                    } else {
                        current
                    }
                }
                delay(50)
            }
        }
    }

    private suspend fun interruptLocally(reason: RecordingInterruption) {
        runCatching { gateway.markInterrupted(draftId, reason, coordinator.recordingDurationMillis) }
        updateState {
            it.copy(
                recordingState = RecordingState.Interrupted(reason),
                keepScreenOn = false,
                errorMessage = reason.patientMessage(),
            )
        }
    }

    private inline fun updateState(transform: (RecordingUiState) -> RecordingUiState) {
        mutableState.update(transform)
    }
}

private fun RecordingInterruption.safeMessage(): String = when (this) {
    RecordingInterruption.CAMERA -> "相机录制中断"
    RecordingInterruption.AUDIO -> "麦克风录制中断"
    RecordingInterruption.FINALIZE -> "录制封装失败"
    RecordingInterruption.VALIDATION -> "录制媒体校验失败"
    RecordingInterruption.STORAGE -> "录制保存失败"
    RecordingInterruption.ACCOUNT_CHANGED -> "账号切换导致录制中断"
    RecordingInterruption.CANCELLED -> "录制已取消"
}

private fun RecordingInterruption.patientMessage(): String = when (this) {
    RecordingInterruption.ACCOUNT_CHANGED -> "账号已切换，录制已中断"
    RecordingInterruption.CAMERA -> "摄像头不可用，录制已中断"
    RecordingInterruption.AUDIO -> "麦克风不可用，录制已中断"
    RecordingInterruption.CANCELLED -> "录制已取消"
    else -> "录制未能安全保存，请重新录制"
}
