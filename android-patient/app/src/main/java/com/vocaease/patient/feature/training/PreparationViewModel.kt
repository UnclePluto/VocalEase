package com.vocaease.patient.feature.training

import androidx.lifecycle.ViewModel
import com.vocaease.patient.core.database.SessionBindingMismatchException
import com.vocaease.patient.core.database.StaleAccountScopeException
import com.vocaease.patient.core.database.PreparationDraftStatus
import com.vocaease.patient.core.media.PreviewSession
import com.vocaease.patient.core.media.PreviewState
import com.vocaease.patient.core.network.NetworkContractException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

data class PreparationSong(
    val id: UUID,
    val title: String,
    val artist: String,
    val durationSeconds: Int,
)

fun interface PreparationSongSource {
    suspend fun load(songId: String): PreparationSong
}

fun interface ReadinessSource {
    suspend fun inspect(durationSeconds: Long, previewBuffered: Boolean): DeviceReadiness
}

interface PreparationEnvironmentMonitor {
    fun start(onChanged: () -> Unit)
    fun stop()
}

data class CreatedTrainingSession(
    val sessionId: UUID,
    val patientId: UUID,
    val song: PreparationSong,
)

fun interface TrainingSessionCreator {
    suspend fun create(songId: UUID, creationKey: String): CreatedTrainingSession
}

data class PreparationDraft(
    val draftId: String,
    val songId: String,
    val songTitle: String,
    val songArtist: String,
    val songDurationSeconds: Int,
    val serverSessionId: String?,
    val creationKey: String,
    val status: PreparationDraftStatus,
    val createdAt: Long,
    val expiresAt: Long,
)

interface PreparationDraftStore {
    val accountScopeHash: String
    suspend fun find(draftId: String): PreparationDraft?
    suspend fun findActive(songId: String): PreparationDraft?
    suspend fun create(draft: PreparationDraft)
    suspend fun findOrCreate(candidate: PreparationDraft): PreparationDraft
    suspend fun bindServerSession(draftId: String, session: CreatedTrainingSession)
    suspend fun markHandoffPending(draftId: String, serverSessionId: String, publishNavigation: () -> Unit)
    suspend fun acknowledgeHandoff(draftId: String): Boolean
    suspend fun abandon(draftId: String): Boolean
}

fun interface PreparationDraftStoreProvider {
    fun current(): PreparationDraftStore
}

interface PreparationSavedState {
    var draftId: String?
    var accountScopeHash: String?
}

data class PreparationUiState(
    val song: PreparationSong? = null,
    val previewState: PreviewState = PreviewState.Idle,
    val preflight: PreflightResult = PreflightResult(
        blockers = setOf(
            PreflightBlocker.CAMERA_PERMISSION,
            PreflightBlocker.AUDIO_PERMISSION,
            PreflightBlocker.STORAGE,
            PreflightBlocker.PREVIEW_BUFFER,
            PreflightBlocker.OFFLINE,
            PreflightBlocker.FRONT_CAMERA,
        ),
        warning = null,
        openSettingsRequired = false,
    ),
    val isLoading: Boolean = false,
    val isCreatingSession: Boolean = false,
    val countdownSecond: Int? = null,
    val navigateToRecordingDraftId: String? = null,
    val errorMessage: String? = null,
)

class PreparationViewModel(
    private val songId: String,
    private val songSource: PreparationSongSource,
    private val readinessSource: ReadinessSource,
    private val environmentMonitor: PreparationEnvironmentMonitor,
    private val preview: PreviewSession,
    private val storageProvider: PreparationDraftStoreProvider,
    private val sessionCreator: TrainingSessionCreator,
    private val savedState: PreparationSavedState,
    private val countdownTick: suspend (Int) -> Unit,
    private val idFactory: () -> String,
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val creationMutex = Mutex()
    private val readinessMutex = Mutex()
    private val stateLock = Any()
    private val operationGeneration = AtomicLong()
    private val readinessGeneration = AtomicLong()
    private val environmentStarted = AtomicBoolean()
    private val mutableState = MutableStateFlow(PreparationUiState())
    val state: StateFlow<PreparationUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch(dispatcher) {
            preview.state.drop(1).collect { refreshReadiness() }
        }
    }

    suspend fun load() {
        updateState { it.copy(isLoading = true, errorMessage = null) }
        try {
            val song = songSource.load(songId)
            updateState { it.copy(song = song) }
            preview.prepare(song.id.toString())
            refreshReadiness()
            updateState { it.copy(isLoading = false) }
        } catch (error: CancellationException) {
            updateState { it.copy(isLoading = false) }
            throw error
        } catch (error: IOException) {
            publishLoadFailure()
        } catch (error: HttpException) {
            publishLoadFailure()
        } catch (error: SerializationException) {
            publishLoadFailure()
        } catch (error: NetworkContractException) {
            publishLoadFailure()
        }
    }

    suspend fun refreshReadiness() {
        val request = readinessGeneration.incrementAndGet()
        readinessMutex.withLock {
            if (request != readinessGeneration.get()) return@withLock
            val song = currentState().song ?: return@withLock
            val readiness = readinessSource.inspect(
                song.durationSeconds.toLong(),
                preview.state.value.isReadyForTraining(),
            )
            if (request != readinessGeneration.get()) return@withLock
            val currentPreview = preview.state.value
            updateState { current ->
                if (current.song?.id != song.id) current else current.copy(
                    previewState = currentPreview,
                    preflight = TrainingPreflight.evaluate(
                        readiness.copy(previewBuffered = currentPreview.isReadyForTraining()),
                    ),
                )
            }
        }
    }

    fun onEnvironmentStarted() {
        if (environmentStarted.compareAndSet(false, true)) {
            environmentMonitor.start(::scheduleReadinessRefresh)
        }
        scheduleReadinessRefresh()
    }

    fun onEnvironmentResumed() {
        scheduleReadinessRefresh()
    }

    fun onEnvironmentStopped() {
        if (environmentStarted.compareAndSet(true, false)) environmentMonitor.stop()
    }

    suspend fun startTraining() {
        creationMutex.withLock {
            if (currentState().navigateToRecordingDraftId != null) return@withLock
            refreshReadiness()
            val startingState = currentState()
            val song = startingState.song ?: return@withLock
            if (!startingState.preflight.canStart) return@withLock
            val operation = operationGeneration.incrementAndGet()
            updateState { it.copy(isCreatingSession = true, errorMessage = null) }
            try {
                val store = storageProvider.current()
                val expectedScope = savedState.accountScopeHash
                if (expectedScope != null && expectedScope != store.accountScopeHash) {
                    throw StaleAccountScopeException()
                }
                val savedDraftId = savedState.draftId
                val savedDraft = if (savedDraftId == null) null else store.find(savedDraftId)
                    ?.takeIf { it.isActiveFor(song) }
                var draft = savedDraft ?: store.findActive(song.id.toString()) ?: run {
                    val candidate = newDraft(store, idFactory(), song)
                    store.findOrCreate(candidate)
                }
                savedState.draftId = draft.draftId
                savedState.accountScopeHash = store.accountScopeHash
                if (
                    draft.songId != song.id.toString() ||
                    draft.creationKey != creationKey(store.accountScopeHash, draft.draftId) ||
                    draft.status !in ACTIVE_PREPARATION_STATUSES
                ) {
                    throw IllegalStateException("演唱草稿恢复信息不一致")
                }
                if (draft.status == PreparationDraftStatus.PENDING) {
                    checkOperation(operation)
                    val session = sessionCreator.create(song.id, draft.creationKey)
                    checkOperation(operation)
                    store.bindServerSession(draft.draftId, session)
                    draft = requireNotNull(store.find(draft.draftId))
                }
                val sessionId = draft.serverSessionId ?: throw SessionBindingMismatchException()
                if (draft.status != PreparationDraftStatus.HANDOFF_PENDING) {
                    for (second in 3 downTo 1) {
                        checkOperation(operation)
                        updateState { it.copy(countdownSecond = second) }
                        countdownTick(second)
                        checkOperation(operation)
                        val persisted = store.find(draft.draftId)
                            ?: throw SessionBindingMismatchException()
                        if (
                            persisted.serverSessionId != sessionId ||
                            persisted.status !in setOf(
                                PreparationDraftStatus.BOUND,
                                PreparationDraftStatus.HANDOFF_PENDING,
                            )
                        ) {
                            throw SessionBindingMismatchException()
                        }
                    }
                }
                checkOperation(operation)
                store.markHandoffPending(draft.draftId, sessionId) {
                    publishNavigation(operation, draft.draftId)
                }
            } catch (error: CancellationException) {
                updateState { it.copy(isCreatingSession = false, countdownSecond = null) }
                throw error
            } catch (_: StaleAccountScopeException) {
                publishCreationFailure("账号已切换，请重新进入演唱准备页")
            } catch (_: SessionBindingMismatchException) {
                publishCreationFailure("会话信息校验失败，请重新进入演唱准备页")
            } catch (_: PreparationOperationCancelledException) {
                updateState { it.copy(isCreatingSession = false, countdownSecond = null) }
            } catch (_: IOException) {
                publishCreationFailure("创建演唱会话失败，请重试")
            } catch (_: HttpException) {
                publishCreationFailure("创建演唱会话失败，请重试")
            } catch (_: SerializationException) {
                publishCreationFailure("创建演唱会话失败，请重试")
            } catch (_: NetworkContractException) {
                publishCreationFailure("创建演唱会话失败，请重试")
            }
        }
    }

    fun togglePreview() {
        when (preview.state.value) {
            PreviewState.Buffered -> preview.play()
            PreviewState.Playing -> preview.pause()
            else -> Unit
        }
    }

    suspend fun retryPreview() {
        val song = currentState().song ?: return
        preview.prepare(song.id.toString())
        refreshReadiness()
    }

    fun consumeRecordingNavigation() {
        updateState { it.copy(navigateToRecordingDraftId = null) }
    }

    fun cancelPreparation() {
        synchronized(stateLock) {
            operationGeneration.incrementAndGet()
            mutableState.value = mutableState.value.copy(isCreatingSession = false, countdownSecond = null)
        }
    }

    suspend fun abandonPreparation() {
        cancelPreparation()
        val draftId = savedState.draftId ?: currentState().song?.id?.toString()?.let { song ->
            runCatching { storageProvider.current().findActive(song)?.draftId }.getOrNull()
        } ?: return
        runCatching { storageProvider.current().abandon(draftId) }
        savedState.draftId = null
        savedState.accountScopeHash = null
    }

    override fun onCleared() {
        cancelPreparation()
        onEnvironmentStopped()
        preview.release()
    }

    private fun scheduleReadinessRefresh() {
        viewModelScope.launch(dispatcher) { refreshReadiness() }
    }

    private fun newDraft(
        store: PreparationDraftStore,
        draftId: String,
        song: PreparationSong,
    ): PreparationDraft {
        val createdAt = clock().coerceAtLeast(0)
        return PreparationDraft(
            draftId = draftId,
            songId = song.id.toString(),
            songTitle = song.title,
            songArtist = song.artist,
            songDurationSeconds = song.durationSeconds,
            serverSessionId = null,
            creationKey = creationKey(store.accountScopeHash, draftId),
            status = PreparationDraftStatus.PENDING,
            createdAt = createdAt,
            expiresAt = if (createdAt > Long.MAX_VALUE - RETENTION_MILLIS) {
                Long.MAX_VALUE
            } else {
                createdAt + RETENTION_MILLIS
            },
        )
    }

    private fun publishLoadFailure() {
        updateState { it.copy(isLoading = false, errorMessage = "演唱准备加载失败，请重试") }
    }

    private fun publishCreationFailure(message: String) {
        updateState {
            it.copy(isCreatingSession = false, countdownSecond = null, errorMessage = message)
        }
    }

    private fun creationKey(accountScopeHash: String, draftId: String) =
        "session-create:$accountScopeHash:$draftId"

    private suspend fun checkOperation(expected: Long) {
        currentCoroutineContext().ensureActive()
        if (operationGeneration.get() != expected) throw PreparationOperationCancelledException()
    }

    private fun publishNavigation(expectedOperation: Long, draftId: String) {
        synchronized(stateLock) {
            if (operationGeneration.get() != expectedOperation || !mutableState.value.isCreatingSession) {
                throw PreparationOperationCancelledException()
            }
            mutableState.value = mutableState.value.copy(
                isCreatingSession = false,
                countdownSecond = null,
                navigateToRecordingDraftId = draftId,
            )
        }
    }

    private fun currentState(): PreparationUiState = synchronized(stateLock) { mutableState.value }

    private inline fun updateState(transform: (PreparationUiState) -> PreparationUiState) {
        synchronized(stateLock) { mutableState.value = transform(mutableState.value) }
    }

    private companion object {
        const val RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    }
}

private class PreparationOperationCancelledException : IllegalStateException()

private val ACTIVE_PREPARATION_STATUSES = setOf(
    PreparationDraftStatus.PENDING,
    PreparationDraftStatus.BOUND,
    PreparationDraftStatus.HANDOFF_PENDING,
)

private fun PreparationDraft.isActiveFor(song: PreparationSong): Boolean =
    songId == song.id.toString() && status in ACTIVE_PREPARATION_STATUSES

private fun PreviewState.isReadyForTraining(): Boolean =
    this is PreviewState.Buffered || this is PreviewState.Playing
