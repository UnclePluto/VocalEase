package com.vocaease.patient.feature.training

import com.vocaease.patient.core.database.AccountScopedDraftStorage
import com.vocaease.patient.core.database.DraftState
import com.vocaease.patient.core.database.MediaType
import com.vocaease.patient.core.database.ReviewMediaInvalidException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

interface LocalReviewStore {
    suspend fun load(draftId: String): ReviewDraft
    suspend fun enqueue(draftId: String): Boolean
    suspend fun prepareRerecord(draftId: String): ReviewDraftIdentity
    suspend fun delete(draftId: String)
}

fun interface UploadQueueSignal {
    /** 只唤醒 Task10 的本地队列入口；实现不得在本调用内发网络请求。 */
    fun schedule(draftId: String)
}

/** Task 9 仅发布本地队列信号；Task 10 可作为单消费者接入，绝不在此执行网络。 */
object LocalUploadQueueSignals : UploadQueueSignal {
    private val mutableSignals = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val signals: SharedFlow<String> = mutableSignals.asSharedFlow()
    override fun schedule(draftId: String) {
        require(draftId.isNotBlank())
        mutableSignals.tryEmit(draftId)
    }
}

class DraftRepository(
    private val store: LocalReviewStore,
    private val uploadSignal: UploadQueueSignal,
) : ReviewDraftGateway {
    override suspend fun load(draftId: String): ReviewDraft = store.load(draftId)

    override suspend fun confirm(draftId: String): Boolean {
        val created = store.enqueue(draftId)
        if (created) uploadSignal.schedule(draftId)
        return created
    }

    override suspend fun prepareRerecord(draftId: String): ReviewDraftIdentity = store.prepareRerecord(draftId)

    override suspend fun delete(draftId: String) = store.delete(draftId)
}

class AccountScopedLocalReviewStore(
    private val storage: AccountScopedDraftStorage,
) : LocalReviewStore {
    override suspend fun load(draftId: String): ReviewDraft {
        val draft = storage.loadReviewDraft(draftId)
        val preparation = storage.findPreparationDraft(draftId) ?: throw ReviewMediaInvalidException()
        if (preparation.songId != draft.songId || preparation.serverSessionId != draft.sessionId ||
            preparation.creationKey != draft.creationKey
        ) throw ReviewMediaInvalidException()
        val video = draft.media.singleOrNull { it.type == MediaType.VIDEO }
        val audio = draft.media.singleOrNull { it.type == MediaType.AUDIO }
        return ReviewDraft(
            draftId = draft.draftId,
            songId = draft.songId,
            songTitle = preparation.songTitle,
            sessionId = draft.sessionId,
            creationKey = draft.creationKey,
            state = draft.state,
            durationMillis = draft.durationMs,
            media = if (video != null && audio != null) {
                ReviewMediaValidation.Valid(
                    ReviewMediaSource(
                        opaqueId = "video",
                        mimeType = video.mimeType,
                        plaintextSizeBytes = video.sizeBytes,
                        encryptedRelativePath = video.encryptedRelativePath,
                    ),
                    ReviewMediaSource(
                        opaqueId = "audio",
                        mimeType = audio.mimeType,
                        plaintextSizeBytes = audio.sizeBytes,
                        encryptedRelativePath = audio.encryptedRelativePath,
                    ),
                )
            } else ReviewMediaValidation.Missing,
        )
    }

    override suspend fun enqueue(draftId: String): Boolean = storage.enqueueReviewDraft(draftId)

    override suspend fun prepareRerecord(draftId: String): ReviewDraftIdentity {
        val reset = storage.prepareRerecord(draftId)
        return ReviewDraftIdentity(reset.draftId, reset.songId, reset.sessionId, reset.creationKey)
    }

    override suspend fun delete(draftId: String) {
        storage.deleteReviewDraft(draftId)
    }
}

object DraftCleanupPolicy {
    fun isEligible(state: DraftState, expiresAt: Long, now: Long): Boolean =
        expiresAt <= now && state in LOCAL_ONLY_STATES

    val LOCAL_ONLY_STATES: Set<DraftState> = setOf(
        DraftState.RECORDING,
        DraftState.REVIEW_READY,
        DraftState.INTERRUPTED,
    )
}
