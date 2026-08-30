package com.vocaease.patient.feature.upload

import com.vocaease.patient.core.database.AccountScopedDraftStorage
import com.vocaease.patient.core.database.MediaType
import com.vocaease.patient.core.database.UploadCheckpoint
import com.vocaease.patient.core.database.UploadOverallState
import com.vocaease.patient.core.database.UploadPipelineStage
import com.vocaease.patient.core.database.UploadStepState

class RoomUploadStore(
    private val storage: AccountScopedDraftStorage,
    private val draftId: String,
    private val nowEpochMillis: () -> Long,
) : UploadStore {
    suspend fun pause() {
        val job = storage.findUploadJob(draftId) ?: return
        storage.checkpointUpload(
            draftId,
            job.checkpoint(UploadPipelineStage.PAUSED, UploadOverallState.PAUSED, USER_PAUSED_MARKER),
        )
    }

    suspend fun resumeFromPause() {
        val job = storage.findUploadJob(draftId) ?: return
        require(job.pipelineStage == UploadPipelineStage.PAUSED)
        val stage = when {
            job.submitState == UploadStepState.SUBMITTING -> UploadPipelineStage.SUBMITTING
            job.videoConfirmState == UploadStepState.CONFIRMING -> UploadPipelineStage.CONFIRMING_VIDEO
            job.videoReceiptState == UploadStepState.WAITING_RECEIPT -> UploadPipelineStage.WAITING_VIDEO_RECEIPT
            job.videoUploadState == UploadStepState.UPLOADING -> UploadPipelineStage.UPLOADING_VIDEO
            job.videoGrantState == UploadStepState.REQUESTING_GRANT -> UploadPipelineStage.REQUESTING_VIDEO_GRANT
            job.audioConfirmState == UploadStepState.CONFIRMING -> UploadPipelineStage.CONFIRMING_AUDIO
            job.audioReceiptState == UploadStepState.WAITING_RECEIPT -> UploadPipelineStage.WAITING_AUDIO_RECEIPT
            job.audioUploadState == UploadStepState.UPLOADING -> UploadPipelineStage.UPLOADING_AUDIO
            job.audioGrantState == UploadStepState.REQUESTING_GRANT -> UploadPipelineStage.REQUESTING_AUDIO_GRANT
            else -> UploadPipelineStage.WAITING_NETWORK
        }
        storage.checkpointUpload(draftId, job.checkpoint(stage, stage.overall(), null))
    }

    override suspend fun load(): UploadRecord {
        val bundle = storage.loadUploadBundle(draftId)
        val audio = bundle.media.single { it.type == MediaType.AUDIO }
        val video = bundle.media.single { it.type == MediaType.VIDEO }
        val job = bundle.job
        return UploadRecord(
            accountScopeHash = bundle.accountScopeHash,
            draftId = bundle.draftId,
            sessionId = bundle.sessionId,
            songTitle = bundle.songTitle,
            stage = job.pipelineStage.toFeature(),
            audio = UploadMedia(audio.mimeType, audio.sizeBytes),
            video = UploadMedia(video.mimeType, video.sizeBytes),
            audioGrantKey = job.audioGrantKey,
            videoGrantKey = job.videoGrantKey,
            submitKey = job.submitKey,
            audioBinding = job.binding(UploadMediaKind.AUDIO, bundle.sessionId, audio.mimeType, audio.sizeBytes),
            videoBinding = job.binding(UploadMediaKind.VIDEO, bundle.sessionId, video.mimeType, video.sizeBytes),
            audioUploaded = job.audioUploadState == UploadStepState.UPLOADED,
            videoUploaded = job.videoUploadState == UploadStepState.UPLOADED,
            audioConfirmed = job.audioConfirmState == UploadStepState.CONFIRMED,
            videoConfirmed = job.videoConfirmState == UploadStepState.CONFIRMED,
            receiptWaitAttempt = job.receiptWaitAttempt,
            progressPercent = job.progressPercent,
            safeError = job.lastSafeError,
        )
    }

    override suspend fun checkpoint(record: UploadRecord) {
        require(record.draftId == draftId && record.accountScopeHash == storage.accountScopeHash)
        val now = nowEpochMillis()
        val checkpoint = UploadCheckpoint(
            overallState = record.stage.overall(),
            pipelineStage = record.stage.toDatabase(),
            audioGrantState = grantState(record, UploadMediaKind.AUDIO),
            videoGrantState = grantState(record, UploadMediaKind.VIDEO),
            audioUploadState = uploadState(record, UploadMediaKind.AUDIO),
            videoUploadState = uploadState(record, UploadMediaKind.VIDEO),
            audioReceiptState = receiptState(record, UploadMediaKind.AUDIO),
            videoReceiptState = receiptState(record, UploadMediaKind.VIDEO),
            audioConfirmState = confirmState(record, UploadMediaKind.AUDIO),
            videoConfirmState = confirmState(record, UploadMediaKind.VIDEO),
            submitState = when (record.stage) {
                UploadStage.SUBMITTING -> UploadStepState.SUBMITTING
                UploadStage.ANALYZING -> UploadStepState.SUBMITTED
                UploadStage.FAILED -> UploadStepState.TERMINAL_FAILURE
                else -> UploadStepState.PENDING
            },
            audioAssetKey = record.audioBinding?.assetId,
            videoAssetKey = record.videoBinding?.assetId,
            audioObjectKey = record.audioBinding?.objectKey,
            videoObjectKey = record.videoBinding?.objectKey,
            audioReceipt = if (record.audioConfirmed) "trusted-callback" else null,
            videoReceipt = if (record.videoConfirmed) "trusted-callback" else null,
            audioConfirmedAt = if (record.audioConfirmed) now else null,
            videoConfirmedAt = if (record.videoConfirmed) now else null,
            attemptCount = 0,
            nextRetryAt = null,
            lastSafeError = record.safeError,
            progressPercent = record.progressPercent,
            receiptWaitAttempt = record.receiptWaitAttempt,
        )
        storage.checkpointUpload(draftId, checkpoint)
    }

    override suspend fun checkpointProgress(progressPercent: Int) {
        val current = load()
        if (current.stage !in setOf(UploadStage.UPLOADING_AUDIO, UploadStage.UPLOADING_VIDEO) ||
            progressPercent <= current.progressPercent
        ) return
        checkpoint(current.copy(progressPercent = progressPercent))
    }

    override suspend fun finishLocalCleanup() {
        storage.deleteSubmittedUploadMedia(draftId)
    }

    fun plaintextLeaseProvider(root: java.io.File): PlaintextUploadLeaseProvider {
        val opaqueJob = sha256("${storage.accountScopeHash}\u0000$draftId")
        return PlaintextUploadLeaseManager(
            root = root,
            opaqueJobId = opaqueJob,
            source = { kind, destination ->
                storage.copyUploadMediaTo(
                    draftId,
                    if (kind == UploadMediaKind.AUDIO) MediaType.AUDIO else MediaType.VIDEO,
                    destination,
                )
            },
            nowEpochMillis = nowEpochMillis,
        )
    }

    private fun com.vocaease.patient.core.database.UploadJobEntity.binding(
        kind: UploadMediaKind,
        sessionId: String,
        mime: String,
        size: Long,
    ): UploadBinding? {
        val asset = if (kind == UploadMediaKind.AUDIO) audioAssetKey else videoAssetKey
        val key = if (kind == UploadMediaKind.AUDIO) audioObjectKey else videoObjectKey
        return if (asset == null || key == null) null else UploadBinding(sessionId, asset, key, mime, size)
    }

    private fun com.vocaease.patient.core.database.UploadJobEntity.checkpoint(
        stage: UploadPipelineStage,
        overall: UploadOverallState,
        error: String?,
    ) = UploadCheckpoint(
        overallState = overall, pipelineStage = stage,
        audioGrantState = audioGrantState, videoGrantState = videoGrantState,
        audioUploadState = audioUploadState, videoUploadState = videoUploadState,
        audioReceiptState = audioReceiptState, videoReceiptState = videoReceiptState,
        audioConfirmState = audioConfirmState, videoConfirmState = videoConfirmState,
        submitState = submitState,
        audioAssetKey = audioAssetKey, videoAssetKey = videoAssetKey,
        audioObjectKey = audioObjectKey, videoObjectKey = videoObjectKey,
        audioReceipt = audioReceipt, videoReceipt = videoReceipt,
        audioConfirmedAt = audioConfirmedAt, videoConfirmedAt = videoConfirmedAt,
        attemptCount = attemptCount, nextRetryAt = nextRetryAt, lastSafeError = error,
        progressPercent = progressPercent, receiptWaitAttempt = receiptWaitAttempt,
    )

    private fun grantState(record: UploadRecord, kind: UploadMediaKind): UploadStepState {
        val binding = if (kind == UploadMediaKind.AUDIO) record.audioBinding else record.videoBinding
        val requesting = record.stage == if (kind == UploadMediaKind.AUDIO) UploadStage.REQUESTING_AUDIO_GRANT else UploadStage.REQUESTING_VIDEO_GRANT
        return when {
            binding != null -> UploadStepState.GRANT_READY
            record.stage == UploadStage.FAILED -> UploadStepState.TERMINAL_FAILURE
            requesting -> UploadStepState.REQUESTING_GRANT
            else -> UploadStepState.PENDING
        }
    }

    private fun uploadState(record: UploadRecord, kind: UploadMediaKind): UploadStepState {
        val uploaded = if (kind == UploadMediaKind.AUDIO) record.audioUploaded else record.videoUploaded
        val uploading = record.stage == if (kind == UploadMediaKind.AUDIO) UploadStage.UPLOADING_AUDIO else UploadStage.UPLOADING_VIDEO
        return when {
            uploaded -> UploadStepState.UPLOADED
            record.stage == UploadStage.FAILED -> UploadStepState.TERMINAL_FAILURE
            uploading -> UploadStepState.UPLOADING
            else -> UploadStepState.PENDING
        }
    }

    private fun receiptState(record: UploadRecord, kind: UploadMediaKind): UploadStepState {
        val confirmed = if (kind == UploadMediaKind.AUDIO) record.audioConfirmed else record.videoConfirmed
        val waitingOrConfirming = record.stage in if (kind == UploadMediaKind.AUDIO) {
            setOf(UploadStage.WAITING_AUDIO_RECEIPT, UploadStage.CONFIRMING_AUDIO)
        } else setOf(UploadStage.WAITING_VIDEO_RECEIPT, UploadStage.CONFIRMING_VIDEO)
        return when {
            confirmed -> UploadStepState.RECEIPT_RECEIVED
            record.stage == UploadStage.FAILED -> UploadStepState.TERMINAL_FAILURE
            waitingOrConfirming -> UploadStepState.WAITING_RECEIPT
            else -> UploadStepState.PENDING
        }
    }

    private fun confirmState(record: UploadRecord, kind: UploadMediaKind): UploadStepState {
        val confirmed = if (kind == UploadMediaKind.AUDIO) record.audioConfirmed else record.videoConfirmed
        val confirming = record.stage == if (kind == UploadMediaKind.AUDIO) UploadStage.CONFIRMING_AUDIO else UploadStage.CONFIRMING_VIDEO
        return when {
            confirmed -> UploadStepState.CONFIRMED
            record.stage == UploadStage.FAILED -> UploadStepState.TERMINAL_FAILURE
            confirming || (record.receiptWaitAttempt > 0 && waitingFor(record.stage, kind)) -> UploadStepState.CONFIRMING
            else -> UploadStepState.PENDING
        }
    }

    private fun waitingFor(stage: UploadStage, kind: UploadMediaKind): Boolean =
        stage == if (kind == UploadMediaKind.AUDIO) UploadStage.WAITING_AUDIO_RECEIPT else UploadStage.WAITING_VIDEO_RECEIPT

    private fun UploadStage.overall() = when (this) {
        UploadStage.PAUSED -> UploadOverallState.PAUSED
        UploadStage.WAITING_NETWORK -> UploadOverallState.WAITING_NETWORK
        UploadStage.REQUESTING_AUDIO_GRANT, UploadStage.UPLOADING_AUDIO,
        UploadStage.REQUESTING_VIDEO_GRANT, UploadStage.UPLOADING_VIDEO -> UploadOverallState.UPLOADING
        UploadStage.WAITING_AUDIO_RECEIPT, UploadStage.WAITING_VIDEO_RECEIPT -> UploadOverallState.WAITING_CALLBACK
        UploadStage.CONFIRMING_AUDIO, UploadStage.CONFIRMING_VIDEO -> UploadOverallState.CONFIRMING
        UploadStage.SUBMITTING -> UploadOverallState.SUBMITTING
        UploadStage.ANALYZING -> UploadOverallState.ANALYZING
        UploadStage.FAILED -> UploadOverallState.FAILED
    }

    private fun UploadPipelineStage.toFeature() = UploadStage.valueOf(name)
    private fun UploadStage.toDatabase() = UploadPipelineStage.valueOf(name)

    private fun UploadPipelineStage.overall() = when (this) {
        UploadPipelineStage.PAUSED -> UploadOverallState.PAUSED
        UploadPipelineStage.WAITING_NETWORK -> UploadOverallState.WAITING_NETWORK
        UploadPipelineStage.REQUESTING_AUDIO_GRANT, UploadPipelineStage.UPLOADING_AUDIO,
        UploadPipelineStage.REQUESTING_VIDEO_GRANT, UploadPipelineStage.UPLOADING_VIDEO -> UploadOverallState.UPLOADING
        UploadPipelineStage.WAITING_AUDIO_RECEIPT, UploadPipelineStage.WAITING_VIDEO_RECEIPT -> UploadOverallState.WAITING_CALLBACK
        UploadPipelineStage.CONFIRMING_AUDIO, UploadPipelineStage.CONFIRMING_VIDEO -> UploadOverallState.CONFIRMING
        UploadPipelineStage.SUBMITTING -> UploadOverallState.SUBMITTING
        UploadPipelineStage.ANALYZING -> UploadOverallState.ANALYZING
        UploadPipelineStage.FAILED -> UploadOverallState.FAILED
    }

    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val USER_PAUSED_MARKER = "已由用户暂停"
    }
}
