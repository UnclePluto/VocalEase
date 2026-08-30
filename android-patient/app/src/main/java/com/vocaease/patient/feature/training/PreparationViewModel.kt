package com.vocaease.patient.feature.training

import androidx.lifecycle.ViewModel
import com.vocaease.patient.core.database.SessionBindingMismatchException
import com.vocaease.patient.core.database.StaleAccountScopeException
import com.vocaease.patient.core.media.PreviewSession
import com.vocaease.patient.core.media.PreviewState
import com.vocaease.patient.core.network.NetworkContractException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
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
    val createdAt: Long,
    val expiresAt: Long,
)

interface PreparationDraftStore {
    val accountScopeHash: String
    suspend fun find(draftId: String): PreparationDraft?
    suspend fun create(draft: PreparationDraft)
    suspend fun bindServerSession(draftId: String, session: CreatedTrainingSession)
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
    private val operationGeneration = AtomicLong()
    private val mutableState = MutableStateFlow(PreparationUiState())
    val state: StateFlow<PreparationUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch(dispatcher) {
            preview.state.drop(1).collect { refreshReadiness() }
        }
    }

    suspend fun load() {
        mutableState.value = mutableState.value.copy(isLoading = true, errorMessage = null)
        try {
            val song = songSource.load(songId)
            preview.prepare(song.id.toString())
            val readiness = readinessSource.inspect(
                song.durationSeconds.toLong(),
                preview.state.value is PreviewState.Buffered,
            )
            mutableState.value = mutableState.value.copy(
                song = song,
                previewState = preview.state.value,
                preflight = TrainingPreflight.evaluate(readiness),
                isLoading = false,
            )
        } catch (error: CancellationException) {
            mutableState.value = mutableState.value.copy(isLoading = false)
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
        val song = mutableState.value.song ?: return
        val readiness = readinessSource.inspect(
            song.durationSeconds.toLong(),
            preview.state.value is PreviewState.Buffered,
        )
        mutableState.value = mutableState.value.copy(
            previewState = preview.state.value,
            preflight = TrainingPreflight.evaluate(readiness),
        )
    }

    suspend fun startTraining() {
        creationMutex.withLock {
            if (mutableState.value.navigateToRecordingDraftId != null) return@withLock
            refreshReadiness()
            val song = mutableState.value.song ?: return@withLock
            if (!mutableState.value.preflight.canStart) return@withLock
            val operation = operationGeneration.incrementAndGet()
            mutableState.value = mutableState.value.copy(isCreatingSession = true, errorMessage = null)
            try {
                val store = storageProvider.current()
                val expectedScope = savedState.accountScopeHash
                if (expectedScope != null && expectedScope != store.accountScopeHash) {
                    throw StaleAccountScopeException()
                }
                val draftId = savedState.draftId ?: idFactory().also {
                    savedState.draftId = it
                    savedState.accountScopeHash = store.accountScopeHash
                }
                val draft = store.find(draftId) ?: run {
                    val created = newDraft(store, draftId, song)
                    store.create(created)
                    created
                }
                if (
                    draft.songId != song.id.toString() ||
                    draft.creationKey != creationKey(store.accountScopeHash, draftId)
                ) {
                    throw IllegalStateException("演唱草稿恢复信息不一致")
                }
                if (draft.serverSessionId == null) {
                    checkOperation(operation)
                    val session = sessionCreator.create(song.id, draft.creationKey)
                    checkOperation(operation)
                    store.bindServerSession(draftId, session)
                }
                for (second in 3 downTo 1) {
                    checkOperation(operation)
                    mutableState.value = mutableState.value.copy(countdownSecond = second)
                    countdownTick(second)
                }
                mutableState.value = mutableState.value.copy(
                    isCreatingSession = false,
                    countdownSecond = null,
                    navigateToRecordingDraftId = draftId,
                )
            } catch (error: CancellationException) {
                mutableState.value = mutableState.value.copy(isCreatingSession = false, countdownSecond = null)
                throw error
            } catch (_: StaleAccountScopeException) {
                publishCreationFailure("账号已切换，请重新进入演唱准备页")
            } catch (_: SessionBindingMismatchException) {
                publishCreationFailure("会话信息校验失败，请重新进入演唱准备页")
            } catch (_: PreparationOperationCancelledException) {
                mutableState.value = mutableState.value.copy(isCreatingSession = false, countdownSecond = null)
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

    fun consumeRecordingNavigation() {
        mutableState.value = mutableState.value.copy(navigateToRecordingDraftId = null)
    }

    fun cancelPreparation() {
        operationGeneration.incrementAndGet()
        mutableState.value = mutableState.value.copy(isCreatingSession = false, countdownSecond = null)
    }

    override fun onCleared() {
        cancelPreparation()
        preview.release()
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
            createdAt = createdAt,
            expiresAt = if (createdAt > Long.MAX_VALUE - RETENTION_MILLIS) {
                Long.MAX_VALUE
            } else {
                createdAt + RETENTION_MILLIS
            },
        )
    }

    private fun publishLoadFailure() {
        mutableState.value = mutableState.value.copy(
            isLoading = false,
            errorMessage = "演唱准备加载失败，请重试",
        )
    }

    private fun publishCreationFailure(message: String) {
        mutableState.value = mutableState.value.copy(
            isCreatingSession = false,
            countdownSecond = null,
            errorMessage = message,
        )
    }

    private fun creationKey(accountScopeHash: String, draftId: String) =
        "session-create:$accountScopeHash:$draftId"

    private suspend fun checkOperation(expected: Long) {
        currentCoroutineContext().ensureActive()
        if (operationGeneration.get() != expected) throw PreparationOperationCancelledException()
    }

    private companion object {
        const val RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    }
}

private class PreparationOperationCancelledException : IllegalStateException()
