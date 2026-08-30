package com.vocaease.patient.feature.training

import androidx.lifecycle.ViewModel
import com.vocaease.patient.core.database.DraftState
import com.vocaease.patient.core.database.StaleAccountScopeException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ReviewMediaKind { VIDEO, AUDIO }

data class ReviewMediaSource(
    val opaqueId: String,
    val mimeType: String,
    val plaintextSizeBytes: Long,
    internal val encryptedRelativePath: String? = null,
)

sealed interface ReviewMediaValidation {
    data class Valid(
        val video: ReviewMediaSource,
        val audio: ReviewMediaSource,
    ) : ReviewMediaValidation

    data object Missing : ReviewMediaValidation
    data object Corrupt : ReviewMediaValidation
}

data class ReviewDraft(
    val draftId: String,
    val songId: String,
    val songTitle: String,
    val sessionId: String,
    val creationKey: String,
    val state: DraftState,
    val durationMillis: Long,
    val media: ReviewMediaValidation,
)

data class ReviewDraftIdentity(
    val draftId: String,
    val songId: String,
    val sessionId: String,
    val creationKey: String,
)

interface ReviewDraftGateway {
    suspend fun load(draftId: String): ReviewDraft
    suspend fun confirm(draftId: String): Boolean
    suspend fun prepareRerecord(draftId: String): ReviewDraftIdentity
    suspend fun delete(draftId: String)
}

sealed interface ReviewPlayerEvent {
    data class Playing(val isPlaying: Boolean, val positionMillis: Long) : ReviewPlayerEvent
    data class Position(val positionMillis: Long) : ReviewPlayerEvent
    data object Ended : ReviewPlayerEvent
    data object Failed : ReviewPlayerEvent
}

interface ReviewPlayer {
    val events: Flow<ReviewPlayerEvent>
    suspend fun load(
        source: ReviewMediaSource,
        kind: ReviewMediaKind,
        positionMillis: Long,
        playWhenReady: Boolean,
    )
    suspend fun play()
    suspend fun pause()
    suspend fun seekTo(positionMillis: Long)
    suspend fun releaseAndAwait()
}

data class ReviewUiState(
    val loading: Boolean = true,
    val songTitle: String = "",
    val durationMillis: Long = 0,
    val durationText: String = "00:00",
    val validationMessage: String = "正在检查录制文件",
    val draftState: DraftState? = null,
    val selectedMedia: ReviewMediaKind = ReviewMediaKind.VIDEO,
    val isPlaying: Boolean = false,
    val playbackPositionMillis: Long = 0,
    val canPlayback: Boolean = false,
    val canConfirm: Boolean = false,
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val navigateRecordingDraftId: String? = null,
    val navigatePreparationSongId: String? = null,
    val navigatePendingUploadDraftId: String? = null,
    val navigateBack: Boolean = false,
)

class ReviewViewModel(
    private val draftId: String,
    private val gateway: ReviewDraftGateway,
    private val player: ReviewPlayer,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val operationMutex = Mutex()
    private val generation = AtomicLong()
    private val left = AtomicBoolean()
    private val confirmStarted = AtomicBoolean()
    private var draft: ReviewDraft? = null
    private val mutableState = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = mutableState.asStateFlow()

    init {
        val eventGeneration = generation.get()
        scope.launch {
            player.events.collect { event ->
                if (!left.get() && generation.get() == eventGeneration) applyPlayerEvent(event)
            }
        }
    }

    suspend fun load() = operationMutex.withLock {
        if (left.get()) return@withLock
        try {
            val loaded = gateway.load(draftId)
            draft = loaded
            val valid = loaded.media as? ReviewMediaValidation.Valid
            val allowedState = loaded.state == DraftState.REVIEW_READY || loaded.state == DraftState.INTERRUPTED
            val canPlayback = valid != null && allowedState && loaded.durationMillis > 0
            mutableState.value = ReviewUiState(
                loading = false,
                songTitle = loaded.songTitle,
                durationMillis = loaded.durationMillis,
                durationText = formatDuration(loaded.durationMillis),
                validationMessage = if (canPlayback) "音视频检查通过" else "录制文件检查未通过",
                draftState = loaded.state,
                canPlayback = canPlayback,
                canConfirm = canPlayback && loaded.state == DraftState.REVIEW_READY,
                errorMessage = if (canPlayback) null else "录制文件不完整，请重新录制",
            )
            if (canPlayback) player.load(valid.video, ReviewMediaKind.VIDEO, 0, false)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failClosed("账号或录制文件已变化，请返回后重试")
        }
    }

    suspend fun playPause() = operationMutex.withLock {
        if (left.get() || !mutableState.value.canPlayback) return@withLock
        if (mutableState.value.isPlaying) player.pause() else player.play()
    }

    suspend fun seekTo(positionMillis: Long) = operationMutex.withLock {
        if (left.get() || !mutableState.value.canPlayback) return@withLock
        val safe = positionMillis.coerceIn(0, mutableState.value.durationMillis)
        player.seekTo(safe)
        mutableState.update { it.copy(playbackPositionMillis = safe) }
    }

    suspend fun switchMedia(kind: ReviewMediaKind) = operationMutex.withLock {
        if (left.get() || kind == mutableState.value.selectedMedia || !mutableState.value.canPlayback) return@withLock
        val valid = draft?.media as? ReviewMediaValidation.Valid ?: return@withLock
        val source = if (kind == ReviewMediaKind.VIDEO) valid.video else valid.audio
        val position = mutableState.value.playbackPositionMillis
        val playWhenReady = mutableState.value.isPlaying
        player.load(source, kind, position, playWhenReady)
        mutableState.update { it.copy(selectedMedia = kind) }
    }

    suspend fun confirm() = operationMutex.withLock {
        if (left.get() || !mutableState.value.canConfirm || !confirmStarted.compareAndSet(false, true)) return@withLock
        mutableState.update { it.copy(busy = true) }
        try {
            gateway.confirm(draftId)
            mutableState.update {
                it.copy(busy = false, canConfirm = false, navigatePendingUploadDraftId = draftId)
            }
        } catch (_: Exception) {
            confirmStarted.set(false)
            mutableState.update {
                it.copy(
                    busy = false,
                    canConfirm = draft?.state == DraftState.REVIEW_READY && draft?.media is ReviewMediaValidation.Valid,
                    errorMessage = "提交准备失败，请稍后重试",
                )
            }
        }
    }

    suspend fun rerecord() = operationMutex.withLock {
        if (left.get() || draft == null) return@withLock
        leavePlayer()
        try {
            val identity = gateway.prepareRerecord(draftId)
            mutableState.update {
                it.copy(
                    busy = false,
                    navigateRecordingDraftId = identity.draftId,
                    navigatePreparationSongId = identity.songId,
                )
            }
        } catch (_: Exception) {
            mutableState.update { it.copy(busy = false, canConfirm = false, errorMessage = "无法安全清理旧录制，请重试") }
        }
    }

    suspend fun delete() = operationMutex.withLock {
        if (left.get() || draft == null) return@withLock
        leavePlayer()
        try {
            gateway.delete(draftId)
            mutableState.update { it.copy(busy = false, navigateBack = true) }
        } catch (_: Exception) {
            mutableState.update { it.copy(busy = false, errorMessage = "删除草稿失败，请重试") }
        }
    }

    suspend fun leave() = operationMutex.withLock {
        if (!left.compareAndSet(false, true)) return@withLock
        generation.incrementAndGet()
        player.releaseAndAwait()
        mutableState.update { it.copy(isPlaying = false, canPlayback = false, canConfirm = false) }
    }

    fun consumeNavigation() {
        mutableState.update {
            it.copy(
                navigateRecordingDraftId = null,
                navigatePreparationSongId = null,
                navigatePendingUploadDraftId = null,
                navigateBack = false,
            )
        }
    }

    override fun onCleared() {
        if (left.compareAndSet(false, true)) {
            generation.incrementAndGet()
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch { player.releaseAndAwait() }
        }
        scope.cancel()
    }

    private suspend fun leavePlayer() {
        mutableState.update { it.copy(busy = true, isPlaying = false, canPlayback = false, canConfirm = false) }
        generation.incrementAndGet()
        player.releaseAndAwait()
    }

    private suspend fun failClosed(message: String) {
        generation.incrementAndGet()
        runCatching { player.releaseAndAwait() }
        mutableState.value = ReviewUiState(loading = false, validationMessage = "录制文件检查未通过", errorMessage = message)
    }

    private fun applyPlayerEvent(event: ReviewPlayerEvent) {
        when (event) {
            is ReviewPlayerEvent.Playing -> mutableState.update {
                it.copy(isPlaying = event.isPlaying, playbackPositionMillis = event.positionMillis.coerceAtLeast(0))
            }
            is ReviewPlayerEvent.Position -> mutableState.update {
                it.copy(playbackPositionMillis = event.positionMillis.coerceIn(0, it.durationMillis))
            }
            ReviewPlayerEvent.Ended -> mutableState.update { it.copy(isPlaying = false, playbackPositionMillis = 0) }
            ReviewPlayerEvent.Failed -> mutableState.update {
                it.copy(isPlaying = false, canPlayback = false, canConfirm = false, errorMessage = "录制文件无法播放，请重新录制")
            }
        }
    }
}

private fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
