package com.vocaease.patient.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UploadRecoveryTest {
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
        val checkpoints = listOf(
            job("uploaded", UploadOverallState.WAITING_CALLBACK, UploadStepState.UPLOADED, UploadStepState.PENDING, UploadStepState.PENDING),
            job("receipt", UploadOverallState.CONFIRMING, UploadStepState.UPLOADED, UploadStepState.RECEIPT_RECEIVED, UploadStepState.PENDING, audioReceipt = "etag-a"),
            job("one-confirmed", UploadOverallState.CONFIRMING, UploadStepState.UPLOADED, UploadStepState.RECEIPT_RECEIVED, UploadStepState.CONFIRMED, audioReceipt = "etag-a", audioConfirmedAt = 10),
            job(
                "ready",
                UploadOverallState.READY_TO_SUBMIT,
                UploadStepState.UPLOADED,
                UploadStepState.RECEIPT_RECEIVED,
                UploadStepState.CONFIRMED,
                audioReceipt = "etag-a",
                videoReceipt = "etag-v",
                audioConfirmedAt = 10,
                videoConfirmedAt = 11,
                videoConfirmed = true,
            ),
        )
        database.uploadDao().insert(checkpoints.first())
        checkpoints.drop(1).forEach { expected ->
            assertEquals(1, checkpoint(database.uploadDao(), expected))
            database.close()
            database = VocaEaseDatabase.create(context, databaseName, allowMainThreadQueries = true)
            assertEquals(expected, database.uploadDao().find("a", "d"))
        }
        database.close()
    }

    private suspend fun checkpoint(dao: UploadDao, job: UploadJobEntity): Int = dao.checkpoint(
        accountScope = job.accountScope, draftId = job.draftId, overallState = job.overallState,
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
    )

    private fun draft() = DraftEntity(
        accountScope = "a", draftId = "d", songId = "song", sessionId = "session", creationKey = "create",
        state = DraftState.READY_TO_UPLOAD, durationMs = 1, createdAt = 0, expiresAt = DraftEntity.MAX_RETENTION_MILLIS,
        interruptionReason = null,
    )

    private fun job(
        marker: String,
        overall: UploadOverallState,
        upload: UploadStepState,
        receipt: UploadStepState,
        confirm: UploadStepState,
        audioReceipt: String? = null,
        videoReceipt: String? = null,
        audioConfirmedAt: Long? = null,
        videoConfirmedAt: Long? = null,
        videoConfirmed: Boolean = false,
    ) = UploadJobEntity(
        accountScope = "a", draftId = "d", overallState = overall,
        audioGrantState = UploadStepState.GRANT_READY, videoGrantState = UploadStepState.GRANT_READY,
        audioUploadState = upload, videoUploadState = if (videoConfirmed) UploadStepState.UPLOADED else UploadStepState.PENDING,
        audioReceiptState = receipt, videoReceiptState = if (videoConfirmed) UploadStepState.RECEIPT_RECEIVED else UploadStepState.PENDING,
        audioConfirmState = confirm, videoConfirmState = if (videoConfirmed) UploadStepState.CONFIRMED else UploadStepState.PENDING,
        submitState = UploadStepState.PENDING,
        audioGrantKey = "audio-grant", videoGrantKey = "video-grant", submitKey = "submit",
        audioAssetKey = "asset-a", videoAssetKey = if (videoConfirmed) "asset-v" else null,
        audioObjectKey = "object-a", videoObjectKey = if (videoConfirmed) "object-v" else null,
        audioReceipt = audioReceipt, videoReceipt = videoReceipt,
        audioConfirmedAt = audioConfirmedAt, videoConfirmedAt = videoConfirmedAt,
        attemptCount = marker.length, nextRetryAt = null, lastSafeError = null,
    )
}
