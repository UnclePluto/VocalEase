package com.vocaease.patient.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import retrofit2.HttpException

@Serializable
class ApiErrorEnvelope(
    val code: String,
    val message: String,
    private val data: JsonElement?,
    @kotlinx.serialization.SerialName("request_id") val requestId: String,
)

sealed class ApiFailure(
    open val userMessage: String,
    open val diagnostic: NetworkDiagnostic,
) {
    data class Unauthorized(override val diagnostic: NetworkDiagnostic) :
        ApiFailure("登录状态已失效，请重新登录", diagnostic)

    data class Forbidden(override val diagnostic: NetworkDiagnostic) :
        ApiFailure("当前账号无权执行此操作", diagnostic)

    data class NotFound(override val diagnostic: NetworkDiagnostic) :
        ApiFailure("请求的内容不存在或已不可用", diagnostic)

    data class Conflict(override val diagnostic: NetworkDiagnostic) :
        ApiFailure("当前状态已发生变化，请刷新后重试", diagnostic)

    data class RateLimited(override val diagnostic: NetworkDiagnostic) :
        ApiFailure("操作过于频繁，请稍后重试", diagnostic)

    data class Unavailable(override val diagnostic: NetworkDiagnostic) :
        ApiFailure("服务暂时不可用，请稍后重试", diagnostic)

    data class Validation(override val diagnostic: NetworkDiagnostic) :
        ApiFailure("提交的信息有误，请检查后重试", diagnostic)

    data class SongUnavailable(override val diagnostic: NetworkDiagnostic) :
        ApiFailure("歌曲当前不可用，请选择其他歌曲", diagnostic)

    data class SingingConflict(override val diagnostic: NetworkDiagnostic) :
        ApiFailure("演唱记录状态冲突，请刷新后重试", diagnostic)

    data class Http(override val diagnostic: NetworkDiagnostic) :
        ApiFailure("请求失败，请稍后重试", diagnostic)

    data class Transport(override val diagnostic: NetworkDiagnostic) :
        ApiFailure("网络连接失败，请检查网络后重试", diagnostic)

    data class Malformed(override val diagnostic: NetworkDiagnostic) :
        ApiFailure("服务返回的数据无法识别，请稍后重试", diagnostic)
}

object ApiErrorMapper {
    fun map(
        error: HttpException,
        method: String,
        pathTemplate: String,
    ): ApiFailure {
        val envelope = runCatching {
            error.response()?.errorBody()?.string()?.let {
                apiJson.decodeFromString<ApiErrorEnvelope>(it)
            }
        }.getOrNull()
        if (envelope != null) return map(error.code(), envelope, method, pathTemplate)

        return ApiFailure.Malformed(
            NetworkDiagnostic.safe(
                method = method,
                pathTemplate = pathTemplate,
                status = error.code(),
                code = "malformed_error_envelope",
                requestId = error.response()?.headers()?.get("X-Request-ID").orEmpty(),
            ),
        )
    }

    fun map(
        status: Int,
        envelope: ApiErrorEnvelope,
        method: String,
        pathTemplate: String,
    ): ApiFailure {
        val diagnostic = NetworkDiagnostic.safe(
            method = method,
            pathTemplate = pathTemplate,
            status = status,
            code = envelope.code,
            requestId = envelope.requestId,
        )
        return when {
            envelope.code == "validation_error" -> ApiFailure.Validation(diagnostic)
            envelope.code == "song_unavailable" -> ApiFailure.SongUnavailable(diagnostic)
            envelope.code.startsWith("singing_") &&
                (envelope.code.endsWith("_conflict") || envelope.code == "singing_retry_exhausted") ->
                ApiFailure.SingingConflict(diagnostic)
            status == 401 -> ApiFailure.Unauthorized(diagnostic)
            status == 403 -> ApiFailure.Forbidden(diagnostic)
            status == 404 -> ApiFailure.NotFound(diagnostic)
            status == 409 -> ApiFailure.Conflict(diagnostic)
            status == 429 -> ApiFailure.RateLimited(diagnostic)
            status == 503 -> ApiFailure.Unavailable(diagnostic)
            else -> ApiFailure.Http(diagnostic)
        }
    }
}

class NetworkContractException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
