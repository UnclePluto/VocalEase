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
        while (true) {
            val current = load()
            if (current.stage == UploadStage.PAUSED) return
            try {
                checkpoint(
                    current,
                    current.copy(
                        stage = UploadStage.PAUSED,
                        safeError = USER_PAUSED_MARKER,
                        resumeStage = current.resumeStage ?: current.stage,
                        operationVersion = current.operationVersion + 1,
                    ),
                )
                return
            } catch (_: UploadSupersededException) {
                continue
            }
        }
    }

    suspend fun resumeFromPause(): Boolean {
        while (true) {
            val expected = load()
            if (expected.stage != UploadStage.PAUSED) return false
            val stage = expected.resumeStage ?: UploadStage.WAITING_NETWORK
            try {
                checkpoint(
                    expected,
                    expected.copy(
                        stage = stage, safeError = null, resumeStage = null,
                        operationVersion = expected.operationVersion + 1,
                    ),
                )
                return true
            } catch (_: UploadSupersededException) {
                // 只有仍处于 PAUSED 的最新版本才能被显式继续。
            }
        }
    }

    suspend fun manualRetry(): Boolean {
        while (true) {
            val expected = load()
            val resume = when (expected.stage) {
                UploadStage.WAITING_NETWORK -> expected.resumeStage ?: UploadStage.REQUESTING_AUDIO_GRANT
                UploadStage.FAILED -> {
                    if (expected.nextRetryAtEpochMillis == null) return false
                    UploadStage.REQUESTING_AUDIO_GRANT
                }
                else -> return false
            }
            try {
                checkpoint(
                    expected,
                    expected.copy(
                        stage = UploadStage.WAITING_NETWORK,
                        safeError = null,
                        resumeStage = resume,
                        nextRetryAtEpochMillis = null,
                        operationVersion = expected.operationVersion + 1,
                    ),
                )
                return true
            } catch (_: UploadSupersededException) {
                // 用户暂停、删除或另一个立即重试先完成时，重新读取后再裁决。
            }
        }
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
            attemptCount = job.attemptCount,
            nextRetryAtEpochMillis = job.nextRetryAt,
            resumeStage = job.resumePipelineStage?.toFeature(),
            operationVersion = job.operationVersion,
        )
    }

    override suspend fun checkpoint(expected: UploadRecord, next: UploadRecord): UploadRecord {
        require(expected.draftId == draftId && expected.accountScopeHash == storage.accountScopeHash)
        require(next.draftId == draftId && next.accountScopeHash == storage.accountScopeHash)
        require(next.operationVersion == expected.operationVersion + 1)
        val now = nowEpochMillis()
        val checkpoint = UploadCheckpoint(
            overallState = next.stage.overall(),
            pipelineStage = next.stage.toDatabase(),
            audioGrantState = grantState(next, UploadMediaKind.AUDIO),
            videoGrantState = grantState(next, UploadMediaKind.VIDEO),
            audioUploadState = uploadState(next, UploadMediaKind.AUDIO),
            videoUploadState = uploadState(next, UploadMediaKind.VIDEO),
            audioReceiptState = receiptState(next, UploadMediaKind.AUDIO),
            videoReceiptState = receiptState(next, UploadMediaKind.VIDEO),
            audioConfirmState = confirmState(next, UploadMediaKind.AUDIO),
            videoConfirmState = confirmState(next, UploadMediaKind.VIDEO),
            submitState = when (next.activeStage()) {
                UploadStage.SUBMITTING -> UploadStepState.SUBMITTING
                UploadStage.ANALYZING -> UploadStepState.SUBMITTED
                UploadStage.FAILED -> UploadStepState.TERMINAL_FAILURE
                else -> UploadStepState.PENDING
            },
            audioAssetKey = next.audioBinding?.assetId,
            videoAssetKey = next.videoBinding?.assetId,
            audioObjectKey = next.audioBinding?.objectKey,
            videoObjectKey = next.videoBinding?.objectKey,
            audioReceipt = if (next.audioConfirmed) "trusted-callback" else null,
            videoReceipt = if (next.videoConfirmed) "trusted-callback" else null,
            audioConfirmedAt = if (next.audioConfirmed) now else null,
            videoConfirmedAt = if (next.videoConfirmed) now else null,
            attemptCount = next.attemptCount,
            nextRetryAt = next.nextRetryAtEpochMillis,
            lastSafeError = next.safeError,
            progressPercent = next.progressPercent,
            receiptWaitAttempt = next.receiptWaitAttempt,
            resumePipelineStage = next.resumeStage?.toDatabase(),
        )
        if (storage.checkpointUpload(draftId, expected.operationVersion, checkpoint) != 1) {
            throw UploadSupersededException()
        }
        return next
    }

    override suspend fun checkpointProgress(expected: UploadRecord, progressPercent: Int): UploadRecord {
        if (expected.stage !in setOf(UploadStage.UPLOADING_AUDIO, UploadStage.UPLOADING_VIDEO) ||
            progressPercent <= expected.progressPercent
        ) return expected
        return checkpoint(
            expected,
            expected.copy(progressPercent = progressPercent, operationVersion = expected.operationVersion + 1),
        )
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
        resumePipelineStage: UploadPipelineStage? = this.resumePipelineStage,
        nextRetryAt: Long? = this.nextRetryAt,
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
        resumePipelineStage = resumePipelineStage,
    )

    private fun grantState(record: UploadRecord, kind: UploadMediaKind): UploadStepState {
        val binding = if (kind == UploadMediaKind.AUDIO) record.audioBinding else record.videoBinding
        val requesting = record.activeStage() == if (kind == UploadMediaKind.AUDIO) UploadStage.REQUESTING_AUDIO_GRANT else UploadStage.REQUESTING_VIDEO_GRANT
        return when {
            binding != null -> UploadStepState.GRANT_READY
            record.stage == UploadStage.FAILED -> UploadStepState.TERMINAL_FAILURE
            requesting -> UploadStepState.REQUESTING_GRANT
            else -> UploadStepState.PENDING
        }
    }

    private fun uploadState(record: UploadRecord, kind: UploadMediaKind): UploadStepState {
        val uploaded = if (kind == UploadMediaKind.AUDIO) record.audioUploaded else record.videoUploaded
        val uploading = record.activeStage() == if (kind == UploadMediaKind.AUDIO) UploadStage.UPLOADING_AUDIO else UploadStage.UPLOADING_VIDEO
        return when {
            uploaded -> UploadStepState.UPLOADED
            record.stage == UploadStage.FAILED -> UploadStepState.TERMINAL_FAILURE
            uploading -> UploadStepState.UPLOADING
            else -> UploadStepState.PENDING
        }
    }

    private fun receiptState(record: UploadRecord, kind: UploadMediaKind): UploadStepState {
        val confirmed = if (kind == UploadMediaKind.AUDIO) record.audioConfirmed else record.videoConfirmed
        val waitingOrConfirming = record.activeStage() in if (kind == UploadMediaKind.AUDIO) {
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
        val activeStage = record.activeStage()
        val confirming = activeStage == if (kind == UploadMediaKind.AUDIO) UploadStage.CONFIRMING_AUDIO else UploadStage.CONFIRMING_VIDEO
        return when {
            confirmed -> UploadStepState.CONFIRMED
            record.stage == UploadStage.FAILED -> UploadStepState.TERMINAL_FAILURE
            confirming || (record.receiptWaitAttempt > 0 && waitingFor(activeStage, kind)) -> UploadStepState.CONFIRMING
            else -> UploadStepState.PENDING
        }
    }

    private fun waitingFor(stage: UploadStage, kind: UploadMediaKind): Boolean =
        stage == if (kind == UploadMediaKind.AUDIO) UploadStage.WAITING_AUDIO_RECEIPT else UploadStage.WAITING_VIDEO_RECEIPT

    private fun UploadRecord.activeStage(): UploadStage =
        if (stage in setOf(UploadStage.PAUSED, UploadStage.WAITING_NETWORK)) resumeStage ?: stage else stage

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
