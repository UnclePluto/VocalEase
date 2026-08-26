package com.vocaease.patient.core.network

import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.HttpException

@Serializable
class ApiErrorEnvelope(
    val code: String,
    val message: String,
    private val data: JsonElement?,
    @kotlinx.serialization.SerialName("request_id") val requestId: String,
) {
    internal fun isSongValidation(): Boolean =
        code == "validation_error" && (data as? JsonObject)?.containsKey("song_id") == true
}

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
        error: Throwable,
        endpoint: ApiEndpoint,
    ): ApiFailure = when (error) {
        is HttpException -> mapHttp(error, endpoint)
        is IOException -> ApiFailure.Transport(
            diagnostic(
                endpoint = endpoint,
                status = null,
                code = "network_error",
                requestId = "",
            ),
        )
        is SerializationException -> ApiFailure.Malformed(
            diagnostic(
                endpoint = endpoint,
                status = null,
                code = "malformed_response",
                requestId = "",
            ),
        )
        else -> ApiFailure.Malformed(
            diagnostic(
                endpoint = endpoint,
                status = null,
                code = "unexpected_network_failure",
                requestId = "",
            ),
        )
    }

    fun map(
        status: Int,
        envelope: ApiErrorEnvelope,
        endpoint: ApiEndpoint,
    ): ApiFailure {
        val diagnostic = diagnostic(
            endpoint = endpoint,
            status = status,
            code = envelope.code,
            requestId = envelope.requestId,
        )
        return when {
            status == 401 -> ApiFailure.Unauthorized(diagnostic)
            status == 403 -> ApiFailure.Forbidden(diagnostic)
            status == 404 -> ApiFailure.NotFound(diagnostic)
            status == 429 -> ApiFailure.RateLimited(diagnostic)
            status == 503 -> ApiFailure.Unavailable(diagnostic)
            status == 409 && envelope.code.isSingingConflict() -> ApiFailure.SingingConflict(diagnostic)
            status == 409 -> ApiFailure.Conflict(diagnostic)
            envelope.isSongValidation() -> ApiFailure.SongUnavailable(diagnostic)
            envelope.code == "validation_error" -> ApiFailure.Validation(diagnostic)
            envelope.code == "song_unavailable" -> ApiFailure.SongUnavailable(diagnostic)
            envelope.code.isSingingConflict() -> ApiFailure.SingingConflict(diagnostic)
            else -> ApiFailure.Http(diagnostic)
        }
    }

    private fun mapHttp(error: HttpException, endpoint: ApiEndpoint): ApiFailure {
        val response = error.response()
        val status = error.code()
        val requestId = response?.headers()?.get("X-Request-ID").orEmpty()
        val body = runCatching { response?.errorBody()?.string() }.getOrNull()
        val envelope = body
            ?.takeIf(String::isNotBlank)
            ?.let { value ->
                runCatching { apiJson.decodeFromString<ApiErrorEnvelope>(value) }.getOrNull()
            }
        return envelope?.let { map(status, it, endpoint) }
            ?: mapStatusWithoutEnvelope(status, endpoint, requestId)
    }

    private fun mapStatusWithoutEnvelope(
        status: Int,
        endpoint: ApiEndpoint,
        requestId: String,
    ): ApiFailure {
        val diagnostic = diagnostic(
            endpoint = endpoint,
            status = status,
            code = "http_$status",
            requestId = requestId,
        )
        return when (status) {
            401 -> ApiFailure.Unauthorized(diagnostic)
            403 -> ApiFailure.Forbidden(diagnostic)
            404 -> ApiFailure.NotFound(diagnostic)
            409 -> ApiFailure.Conflict(diagnostic)
            429 -> ApiFailure.RateLimited(diagnostic)
            503 -> ApiFailure.Unavailable(diagnostic)
            else -> ApiFailure.Malformed(
                diagnostic(
                    endpoint = endpoint,
                    status = status,
                    code = "malformed_error_envelope",
                    requestId = requestId,
                ),
            )
        }
    }

    private fun diagnostic(
        endpoint: ApiEndpoint,
        status: Int?,
        code: String,
        requestId: String,
    ): NetworkDiagnostic = NetworkDiagnostic.safe(
        endpoint = endpoint,
        status = status,
        code = code,
        requestId = requestId,
    )

    private fun String.isSingingConflict(): Boolean =
        startsWith("singing_") && (endsWith("_conflict") || this == "singing_retry_exhausted")
}

class NetworkContractException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
