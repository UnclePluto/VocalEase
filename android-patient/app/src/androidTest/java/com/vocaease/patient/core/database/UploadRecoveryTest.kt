package com.vocaease.patient.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import android.database.sqlite.SQLiteConstraintException
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UploadRecoveryTest {
    @Test
    fun localActionIntentAndEveryCleanupCheckpointSurviveReopenAndRejectSkippedStage() = runBlocking {
        var database = VocaEaseDatabase.create(context, databaseName, allowMainThreadQueries = true)
        database.draftDao().insert(draft())
        val intent = UploadLocalActionEntity(
            accountScope = "a",
            draftId = "d",
            action = UploadLocalActionType.CLEANUP_SUBMITTED,
            stage = UploadLocalActionStage.INTENT_WRITTEN,
            audioEncryptedRelativePath = "media/v1/${"a".repeat(32)}.vef",
            videoEncryptedRelativePath = "media/v1/${"b".repeat(32)}.vef",
        )
        database.uploadLocalActionDao().insert(intent)
        assertThrows(SQLiteConstraintException::class.java) {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE upload_local_actions SET stage='VIDEO_DELETED' WHERE account_scope='a' AND draft_id='d'",
            )
        }
        val stages = listOf(
            UploadLocalActionStage.MEDIA_INVALIDATED,
            UploadLocalActionStage.AUDIO_DELETED,
            UploadLocalActionStage.VIDEO_DELETED,
            UploadLocalActionStage.MEDIA_ROWS_DELETED,
            UploadLocalActionStage.DRAFT_FINALIZED,
        )
        var previous = UploadLocalActionStage.INTENT_WRITTEN
        stages.forEach { stage ->
            assertEquals(1, database.uploadLocalActionDao().advance("a", "d", previous, stage))
            database.close()
            database = VocaEaseDatabase.create(context, databaseName, allowMainThreadQueries = true)
            assertEquals(stage, database.uploadLocalActionDao().find("a", "d")?.stage)
            previous = stage
        }
        database.close()
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "upload-recovery-${System.nanoTime()}.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
        File(context.getDatabasePath(databaseName).path + "-wal").delete()
        File(context.getDatabasePath(databaseName).path + "-shm").delete()
    }

    @Test
    fun checkpointsResumeFromLastDurableSafePointAfterProcessReopen() = runBlocking {
        var database = VocaEaseDatabase.create(context, databaseName, allowMainThreadQueries = true)
        database.draftDao().insert(draft())
        val initial = UploadJobEntity.newPending("a", "d", "grant:d:audio", "grant:d:video", "submit:d")
        database.uploadDao().insert(initial)
        val waitingNetwork = initial.copy(overallState = UploadOverallState.WAITING_NETWORK, pipelineStage = UploadPipelineStage.WAITING_NETWORK)
        val requestingAudio = waitingNetwork.copy(
            overallState = UploadOverallState.UPLOADING, pipelineStage = UploadPipelineStage.REQUESTING_AUDIO_GRANT,
            audioGrantState = UploadStepState.REQUESTING_GRANT,
        )
        val uploadingAudio = requestingAudio.copy(
            pipelineStage = UploadPipelineStage.UPLOADING_AUDIO,
            audioGrantState = UploadStepState.GRANT_READY, audioUploadState = UploadStepState.UPLOADING,
            audioAssetKey = "asset-a", audioObjectKey = "object-a",
        )
        val waitingAudio = uploadingAudio.copy(
            overallState = UploadOverallState.WAITING_CALLBACK, pipelineStage = UploadPipelineStage.WAITING_AUDIO_RECEIPT,
            audioUploadState = UploadStepState.UPLOADED, audioReceiptState = UploadStepState.WAITING_RECEIPT,
            progressPercent = 50,
        )
        val confirmingAudio = waitingAudio.copy(
            overallState = UploadOverallState.CONFIRMING, pipelineStage = UploadPipelineStage.CONFIRMING_AUDIO,
            audioConfirmState = UploadStepState.CONFIRMING,
        )
        val requestingVideo = confirmingAudio.copy(
            overallState = UploadOverallState.UPLOADING, pipelineStage = UploadPipelineStage.REQUESTING_VIDEO_GRANT,
            audioReceiptState = UploadStepState.RECEIPT_RECEIVED, audioConfirmState = UploadStepState.CONFIRMED,
            audioReceipt = "trusted-callback", audioConfirmedAt = 10,
            videoGrantState = UploadStepState.REQUESTING_GRANT,
        )
        val uploadingVideo = requestingVideo.copy(
            pipelineStage = UploadPipelineStage.UPLOADING_VIDEO,
            videoGrantState = UploadStepState.GRANT_READY, videoUploadState = UploadStepState.UPLOADING,
            videoAssetKey = "asset-v", videoObjectKey = "object-v",
        )
        val waitingVideo = uploadingVideo.copy(
            overallState = UploadOverallState.WAITING_CALLBACK, pipelineStage = UploadPipelineStage.WAITING_VIDEO_RECEIPT,
            videoUploadState = UploadStepState.UPLOADED, videoReceiptState = UploadStepState.WAITING_RECEIPT,
            progressPercent = 100,
        )
        val confirmingVideo = waitingVideo.copy(
            overallState = UploadOverallState.CONFIRMING, pipelineStage = UploadPipelineStage.CONFIRMING_VIDEO,
            videoConfirmState = UploadStepState.CONFIRMING,
        )
        val submitting = confirmingVideo.copy(
            overallState = UploadOverallState.SUBMITTING, pipelineStage = UploadPipelineStage.SUBMITTING,
            videoReceiptState = UploadStepState.RECEIPT_RECEIVED, videoConfirmState = UploadStepState.CONFIRMED,
            videoReceipt = "trusted-callback", videoConfirmedAt = 11,
            submitState = UploadStepState.SUBMITTING,
        )
        val analyzing = submitting.copy(
            overallState = UploadOverallState.ANALYZING, pipelineStage = UploadPipelineStage.ANALYZING,
            submitState = UploadStepState.SUBMITTED,
        )
        listOf(
            waitingNetwork, requestingAudio, uploadingAudio, waitingAudio, confirmingAudio,
            requestingVideo, uploadingVideo, waitingVideo, confirmingVideo, submitting, analyzing,
        ).forEachIndexed { index, durable ->
            val expected = durable.copy(attemptCount = index + 1)
            assertEquals(1, checkpoint(database.uploadDao(), expected))
            database.close()
            database = VocaEaseDatabase.create(context, databaseName, allowMainThreadQueries = true)
            assertEquals(expected, database.uploadDao().find("a", "d"))
        }
        database.close()
    }

    private suspend fun checkpoint(dao: UploadDao, job: UploadJobEntity): Int = dao.checkpoint(
        accountScope = job.accountScope, draftId = job.draftId, overallState = job.overallState,
        pipelineStage = job.pipelineStage,
        audioGrantState = job.audioGrantState, videoGrantState = job.videoGrantState,
        audioUploadState = job.audioUploadState, videoUploadState = job.videoUploadState,
        audioReceiptState = job.audioReceiptState, videoReceiptState = job.videoReceiptState,
        audioConfirmState = job.audioConfirmState, videoConfirmState = job.videoConfirmState,
        submitState = job.submitState, audioGrantKey = job.audioGrantKey, videoGrantKey = job.videoGrantKey,
        submitKey = job.submitKey, audioAssetKey = job.audioAssetKey, videoAssetKey = job.videoAssetKey,
        audioObjectKey = job.audioObjectKey, videoObjectKey = job.videoObjectKey,
        audioReceipt = job.audioReceipt, videoReceipt = job.videoReceipt,
        audioConfirmedAt = job.audioConfirmedAt, videoConfirmedAt = job.videoConfirmedAt,
        attemptCount = job.attemptCount, nextRetryAt = job.nextRetryAt, lastSafeError = job.lastSafeError,
        progressPercent = job.progressPercent, receiptWaitAttempt = job.receiptWaitAttempt,
        resumePipelineStage = job.resumePipelineStage,
    )

    private fun draft() = DraftEntity(
        accountScope = "a", draftId = "d", songId = "song", sessionId = "session", creationKey = "create",
        state = DraftState.READY_TO_UPLOAD, durationMs = 1, createdAt = 0, expiresAt = DraftEntity.MAX_RETENTION_MILLIS,
        interruptionReason = null,
    )

}
