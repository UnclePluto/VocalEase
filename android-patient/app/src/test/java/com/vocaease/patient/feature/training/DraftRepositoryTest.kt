package com.vocaease.patient.feature.training

import com.vocaease.patient.core.database.DraftState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftRepositoryTest {
    @Test
    fun `确认只触发本地原子入队和一次调度信号`() = runBlocking {
        val store = FakeLocalReviewStore()
        val signal = FakeUploadSignal()
        val repository = DraftRepository(store, signal)

        assertTrue(repository.confirm("draft-1"))
        assertFalse(repository.confirm("draft-1"))

        assertEquals(2, store.enqueueAttempts)
        assertEquals(listOf("draft-1"), signal.drafts)
        assertEquals(0, store.networkCalls)
    }

    @Test
    fun `七天边界只允许未入队本地态清理`() {
        val now = 700_000L
        assertFalse(DraftCleanupPolicy.isEligible(DraftState.REVIEW_READY, now + 1, now))
        assertTrue(DraftCleanupPolicy.isEligible(DraftState.REVIEW_READY, now, now))
        assertTrue(DraftCleanupPolicy.isEligible(DraftState.INTERRUPTED, now - 1, now))
        assertTrue(DraftCleanupPolicy.isEligible(DraftState.RECORDING, now - 1, now))
        listOf(
            DraftState.READY_TO_UPLOAD,
            DraftState.UPLOADING,
            DraftState.FAILED,
            DraftState.SUBMITTED,
        ).forEach { state -> assertFalse("$state 不得被草稿清理", DraftCleanupPolicy.isEligible(state, 0, now)) }
    }
}

private class FakeLocalReviewStore : LocalReviewStore {
    var enqueueAttempts = 0
    var networkCalls = 0
    private var enqueued = false

    override suspend fun load(draftId: String): ReviewDraft = error("本测试不使用")

    override suspend fun enqueue(draftId: String): Boolean {
        enqueueAttempts += 1
        return if (enqueued) false else true.also { enqueued = true }
    }

    override suspend fun prepareRerecord(draftId: String): ReviewDraftIdentity = error("本测试不使用")
    override suspend fun delete(draftId: String) = Unit
}

private class FakeUploadSignal : UploadQueueSignal {
    val drafts = mutableListOf<String>()
    override fun schedule(draftId: String) { drafts += draftId }
}
