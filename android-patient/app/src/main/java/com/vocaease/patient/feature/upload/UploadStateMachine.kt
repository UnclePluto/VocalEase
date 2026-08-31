package com.vocaease.patient.feature.upload

import com.vocaease.patient.core.database.UploadPipelineStage
import com.vocaease.patient.core.database.UploadPipelineTransitionPolicy
import java.io.Closeable
import java.io.File

enum class UploadStage {
    PAUSED,
    WAITING_NETWORK,
    REQUESTING_AUDIO_GRANT,
    UPLOADING_AUDIO,
    WAITING_AUDIO_RECEIPT,
    CONFIRMING_AUDIO,
    REQUESTING_VIDEO_GRANT,
    UPLOADING_VIDEO,
    WAITING_VIDEO_RECEIPT,
    CONFIRMING_VIDEO,
    SUBMITTING,
    ANALYZING,
    FAILED,
}

enum class UploadMediaKind { AUDIO, VIDEO }

data class UploadMedia(
    val mimeType: String,
    val sizeBytes: Long,
) {
    init {
        require(mimeType in setOf("audio/mp4", "video/mp4"))
        require(sizeBytes > 0)
    }
}

/** 可持久化的非敏感媒体绑定。 */
data class UploadBinding(
    val sessionId: String,
    val assetId: String,
    val objectKey: String,
    val mimeType: String,
    val sizeBytes: Long,
)

/** 仅限当前调用栈的短期凭证，禁止放入 Room、日志、WorkManager Data 或错误文案。 */
data class UploadGrant(
    val binding: UploadBinding,
    val expiresAtEpochMillis: Long,
    val uploadUrl: String,
    val uploadToken: String,
) {
    init {
        require(expiresAtEpochMillis > 0)
        require(uploadUrl.isNotBlank())
        require(uploadToken.isNotBlank())
    }
}

data class UploadRecord(
    val accountScopeHash: String,
    val draftId: String,
    val sessionId: String,
    val songTitle: String,
    val stage: UploadStage,
    val audio: UploadMedia,
    val video: UploadMedia,
    val audioGrantKey: String,
    val videoGrantKey: String,
    val submitKey: String,
    val audioBinding: UploadBinding? = null,
    val videoBinding: UploadBinding? = null,
    val audioUploaded: Boolean = false,
    val videoUploaded: Boolean = false,
    val audioConfirmed: Boolean = false,
    val videoConfirmed: Boolean = false,
    val receiptWaitAttempt: Int = 0,
    val progressPercent: Int = 0,
    val safeError: String? = null,
    val attemptCount: Int = 0,
    val nextRetryAtEpochMillis: Long? = null,
    val resumeStage: UploadStage? = null,
) {
    init {
        require(accountScopeHash.matches(Regex("[0-9a-f]{64}")) || accountScopeHash == "scope-hash")
        require(draftId.isNotBlank() && sessionId.isNotBlank())
        require(audioGrantKey == "grant:$draftId:audio")
        require(videoGrantKey == "grant:$draftId:video")
        require(submitKey == "submit:$draftId")
        require(receiptWaitAttempt in 0..4)
        require(progressPercent in 0..100)
        require(safeError == null || safeError.length <= 256)
        require(attemptCount >= 0)
        require(nextRetryAtEpochMillis == null || nextRetryAtEpochMillis >= 0)
    }
}

interface UploadStore {
    suspend fun load(): UploadRecord
    suspend fun checkpoint(record: UploadRecord)
    suspend fun checkpointProgress(progressPercent: Int)
    suspend fun finishLocalCleanup()
}

data class UploadGrantRequest(
    val sessionId: String,
    val kind: UploadMediaKind,
    val media: UploadMedia,
    val idempotencyKey: String,
)

data class UploadConfirmRequest(
    val sessionId: String,
    val kind: UploadMediaKind,
    val binding: UploadBinding,
)

sealed interface UploadConfirmResult {
    data class Confirmed(val binding: UploadBinding) : UploadConfirmResult
    data object CallbackPending : UploadConfirmResult
}

sealed interface UploadSubmitResult {
    data class Accepted(val sessionId: String) : UploadSubmitResult
    data object Conflict : UploadSubmitResult
}

enum class RemoteSessionState { CREATED, AWAITING_UPLOAD, UPLOADED, PROCESSING, COMPLETED, FAILED, CANCELLED, UNKNOWN }

data class UploadSessionDetail(
    val sessionId: String,
    val state: RemoteSessionState,
    val media: List<UploadSessionMedia>,
)

data class UploadSessionMedia(
    val assetId: String,
    val kind: UploadMediaKind,
    val mimeType: String,
    val sizeBytes: Long,
    val status: RemoteMediaState,
)

enum class RemoteMediaState { UPLOADING, READY, UNKNOWN }

interface UploadRemote {
    suspend fun grant(request: UploadGrantRequest): UploadGrant
    suspend fun confirm(request: UploadConfirmRequest): UploadConfirmResult
    suspend fun submit(sessionId: String, idempotencyKey: String): UploadSubmitResult
    suspend fun sessionDetail(sessionId: String): UploadSessionDetail
}

data class QiniuUploadRequest(
    val kind: UploadMediaKind,
    val grant: UploadGrant,
    val file: File,
    val mimeType: String,
)

sealed interface QiniuUploadResult {
    data class Completed(val objectKey: String) : QiniuUploadResult
    data object Cancelled : QiniuUploadResult
}

interface QiniuUploader {
    suspend fun upload(request: QiniuUploadRequest, onProgress: (Int) -> Unit): QiniuUploadResult
    fun cancel()
}

interface PlaintextUploadLease : Closeable {
    val file: File
}

fun interface PlaintextUploadLeaseProvider {
    suspend fun open(kind: UploadMediaKind, media: UploadMedia): PlaintextUploadLease
}

sealed interface UploadRunResult {
    data object Analyzing : UploadRunResult
    data object Paused : UploadRunResult
    data object Retry : UploadRunResult
    data class TerminalFailure(val safeReason: String) : UploadRunResult
}

internal class UploadContractViolation(message: String) : IllegalStateException(message)
internal class UploadRemoteRetryableException : java.io.IOException("上传服务暂时不可用")
internal class UploadRemoteTerminalException : IllegalStateException("上传服务拒绝了当前任务")

internal object UploadTransitions {
    fun requireAllowed(from: UploadStage, to: UploadStage) {
        require(
            UploadPipelineTransitionPolicy.allows(
                UploadPipelineStage.valueOf(from.name),
                UploadPipelineStage.valueOf(to.name),
            ),
        ) { "非法上传状态转换：$from -> $to" }
    }
}
