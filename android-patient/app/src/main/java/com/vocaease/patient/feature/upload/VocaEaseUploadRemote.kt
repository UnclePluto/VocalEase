package com.vocaease.patient.feature.upload

import com.vocaease.patient.core.network.ApiErrorEnvelope
import com.vocaease.patient.core.network.PatientApi
import com.vocaease.patient.core.network.apiJson
import com.vocaease.patient.core.network.dto.ConfirmSessionMediaRequestDto
import com.vocaease.patient.core.network.dto.MediaType
import com.vocaease.patient.core.network.dto.SessionStatus
import com.vocaease.patient.core.network.dto.SessionUploadGrantRequestDto
import com.vocaease.patient.core.network.dto.SingingSessionDto
import com.vocaease.patient.core.network.dto.toDomain
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import retrofit2.HttpException

class VocaEaseUploadRemote(
    private val api: PatientApi,
    private val expectedAccountScopeHash: String,
) : UploadRemote {
    init {
        require(expectedAccountScopeHash.matches(Regex("[0-9a-f]{64}")))
    }

    override suspend fun grant(request: UploadGrantRequest): UploadGrant = protect {
        val dto = api.sessionUploadGrant(
            request.sessionId,
            request.idempotencyKey,
            SessionUploadGrantRequestDto(request.kind.toDto(), request.media.mimeType, request.media.sizeBytes),
        ).data
        val domain = dto.toDomain()
        if (domain.sessionId?.toString() != request.sessionId || domain.uploadToken.isBlank()) throw UploadContractViolation("凭证身份不匹配")
        UploadGrant(
            binding = UploadBinding(
                request.sessionId,
                domain.assetId.toString(),
                domain.objectKey,
                request.media.mimeType,
                request.media.sizeBytes,
            ),
            expiresAtEpochMillis = domain.expiresAt.toEpochMilli(),
            uploadUrl = domain.uploadUrl,
            uploadToken = domain.uploadToken,
        )
    }

    override suspend fun confirm(request: UploadConfirmRequest): UploadConfirmResult {
        return try {
            val session = api.confirmSessionMedia(
                request.sessionId,
                ConfirmSessionMediaRequestDto(assetId = request.binding.assetId, objectKey = request.binding.objectKey),
            ).data
            validateSessionOwner(session, request.sessionId)
            val media = session.media.singleOrNull { it.assetId == request.binding.assetId && it.mediaType == request.kind.toDto() }
                ?: throw UploadContractViolation("确认媒体缺失")
            if (media.status != "ready" || media.mime != request.binding.mimeType || media.size != request.binding.sizeBytes || media.confirmedAt == null) {
                throw UploadContractViolation("确认媒体不匹配")
            }
            UploadConfirmResult.Confirmed(request.binding)
        } catch (error: HttpException) {
            if (error.code() == 409 && error.safeCode() == "singing_media_conflict") UploadConfirmResult.CallbackPending
            else throw error.classified()
        } catch (_: IOException) {
            throw UploadRemoteRetryableException()
        }
    }

    override suspend fun submit(sessionId: String, idempotencyKey: String): UploadSubmitResult {
        return try {
            val mutation = api.submitSession(sessionId, idempotencyKey).data
            if (mutation.sessionId != sessionId || mutation.status !in setOf(SessionStatus.PROCESSING, SessionStatus.COMPLETED)) {
                throw UploadContractViolation("提交响应不匹配")
            }
            UploadSubmitResult.Accepted(sessionId)
        } catch (error: HttpException) {
            if (error.code() == 409) UploadSubmitResult.Conflict else throw error.classified()
        } catch (_: IOException) {
            throw UploadRemoteRetryableException()
        }
    }

    override suspend fun sessionDetail(sessionId: String): UploadSessionDetail = protect {
        val session = api.session(sessionId).data
        validateSessionOwner(session, sessionId)
        UploadSessionDetail(
            sessionId = session.id,
            state = session.status.toRemote(),
            media = session.media.map { media ->
                UploadSessionMedia(
                    assetId = media.assetId,
                    kind = when (media.mediaType) {
                        MediaType.SINGING_AUDIO -> UploadMediaKind.AUDIO
                        MediaType.SINGING_VIDEO -> UploadMediaKind.VIDEO
                    },
                    mimeType = media.mime,
                    sizeBytes = media.size,
                )
            },
        )
    }

    private fun validateSessionOwner(session: SingingSessionDto, expectedSessionId: String) {
        if (session.id != expectedSessionId || sha256(session.patient.id) != expectedAccountScopeHash) {
            throw UploadContractViolation("会话归属不匹配")
        }
    }

    private suspend fun <T> protect(call: suspend () -> T): T = try {
        call()
    } catch (error: UploadContractViolation) {
        throw error
    } catch (error: HttpException) {
        throw error.classified()
    } catch (_: IOException) {
        throw UploadRemoteRetryableException()
    }

    private fun HttpException.classified(): Exception =
        if (code() == 408 || code() == 429 || code() >= 500) UploadRemoteRetryableException() else UploadRemoteTerminalException()

    private fun HttpException.safeCode(): String? = runCatching {
        response()?.errorBody()?.string()?.let { apiJson.decodeFromString<ApiErrorEnvelope>(it).code }
    }.getOrNull()

    private fun UploadMediaKind.toDto() = when (this) {
        UploadMediaKind.AUDIO -> MediaType.SINGING_AUDIO
        UploadMediaKind.VIDEO -> MediaType.SINGING_VIDEO
    }

    private fun SessionStatus.toRemote() = when (this) {
        SessionStatus.CREATED -> RemoteSessionState.CREATED
        SessionStatus.AWAITING_UPLOAD -> RemoteSessionState.AWAITING_UPLOAD
        SessionStatus.UPLOADED -> RemoteSessionState.UPLOADED
        SessionStatus.PROCESSING -> RemoteSessionState.PROCESSING
        SessionStatus.COMPLETED -> RemoteSessionState.COMPLETED
        SessionStatus.FAILED -> RemoteSessionState.FAILED
        SessionStatus.CANCELLED -> RemoteSessionState.CANCELLED
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
