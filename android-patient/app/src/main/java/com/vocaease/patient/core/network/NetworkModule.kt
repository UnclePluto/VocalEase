package com.vocaease.patient.core.network

import com.vocaease.patient.BuildConfig
import com.vocaease.patient.core.security.TokenVault
import java.io.IOException
import java.util.logging.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import okhttp3.Interceptor
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Authenticator
import okhttp3.Protocol
import retrofit2.Invocation
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HEAD
import retrofit2.http.HTTP
import retrofit2.http.OPTIONS
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT

enum class ApiEndpoint(
    val method: String,
    val pathTemplate: String,
) {
    AUTH_LOGIN("POST", "/api/v1/auth/login/"),
    AUTH_REFRESH("POST", "/api/v1/auth/refresh/"),
    AUTH_CHANGE_PASSWORD("POST", "/api/v1/auth/change-password/"),
    AUTH_LOGOUT("POST", "/api/v1/auth/logout/"),
    PATIENT_ME("GET", "/api/v1/patient/me/"),
    SONG_LIST("GET", "/api/v1/patient/songs/"),
    SONG_DETAIL("GET", "/api/v1/patient/songs/{song_id}/"),
    SONG_REFERENCE_PITCH("GET", "/api/v1/patient/songs/{song_id}/reference-pitch/"),
    SESSION_SONG_PLAYBACK("POST", "/api/v1/patient/singing-sessions/{session_id}/song-playback/"),
    SONG_PREVIEW("POST", "/api/v1/patient/songs/{song_id}/preview/"),
    SESSION_LIST("GET", "/api/v1/patient/singing-sessions/"),
    SESSION_CREATE("POST", "/api/v1/patient/singing-sessions/"),
    SESSION_DETAIL("GET", "/api/v1/patient/singing-sessions/{session_id}/"),
    SESSION_UPLOAD_GRANT("POST", "/api/v1/patient/singing-sessions/{session_id}/upload-grants/"),
    PATIENT_MEDIA_UPLOAD_GRANT("POST", "/api/v1/patient/media/upload-grants/"),
    SESSION_CONFIRM_UPLOAD("POST", "/api/v1/patient/singing-sessions/{session_id}/confirm-upload/"),
    SESSION_SUBMIT("POST", "/api/v1/patient/singing-sessions/{session_id}/submit/"),
    SESSION_CANCEL("POST", "/api/v1/patient/singing-sessions/{session_id}/cancel/"),
    SESSION_RETRY("POST", "/api/v1/patient/singing-sessions/{session_id}/retry/"),
    PATIENT_MEDIA_PRIVATE_URL("POST", "/api/v1/patient/media/{asset_id}/private-url/"),
    UNKNOWN("UNKNOWN", "/unknown"),
    ;

    companion object {
        internal fun from(method: String, pathTemplate: String): ApiEndpoint = entries.firstOrNull {
            it.method == method && it.pathTemplate == pathTemplate
        } ?: UNKNOWN
    }
}

class NetworkDiagnostic private constructor(
    val endpoint: ApiEndpoint,
    val status: Int?,
    val code: String,
    val requestId: String,
) {
    val method: String
        get() = endpoint.method

    val pathTemplate: String
        get() = endpoint.pathTemplate

    fun toLogLine(): String =
        "method=$method path=$pathTemplate status=${status ?: "-"} code=$code requestId=$requestId"

    companion object {
        fun safe(
            endpoint: ApiEndpoint,
            status: Int?,
            code: String,
            requestId: String,
        ): NetworkDiagnostic = NetworkDiagnostic(
            endpoint = endpoint,
            status = status,
            code = code.sanitizeDiagnostic(defaultValue = "unknown", maxLength = 64),
            requestId = requestId.sanitizeDiagnostic(defaultValue = "", maxLength = 64),
        )
    }
}

object NetworkModule {
    internal fun createApi(
        baseUrl: String = BuildConfig.API_BASE_URL,
        client: OkHttpClient = createHttpClient(),
    ): VocaEaseApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(apiJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(VocaEaseApi::class.java)

    fun createHttpClient(
        diagnosticSink: (NetworkDiagnostic) -> Unit = DefaultDiagnosticSink::accept,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(SafeNetworkDiagnosticInterceptor(diagnosticSink))
        .build()

    internal fun createAuthenticatedHttpClient(
        tokenVault: TokenVault,
        diagnosticSink: (NetworkDiagnostic) -> Unit = DefaultDiagnosticSink::accept,
    ): OkHttpClient = OkHttpClient.Builder()
        .dispatcher(
            Dispatcher().apply {
                maxRequests = MAX_AUTHENTICATED_REQUESTS
                maxRequestsPerHost = MAX_AUTHENTICATED_REQUESTS
            },
        )
        .addInterceptor(AuthInterceptor(tokenVault))
        .addInterceptor(SafeNetworkDiagnosticInterceptor(diagnosticSink))
        .addNetworkInterceptor(RetryRequestSingleAttemptInterceptor())
        .build()

    /**
     * 待撤销凭据只能经这个无认证拦截器、无重定向、无连接重试的客户端发送。
     * 一次执行因此最多产生一个物理请求，也不会把凭据带往响应指定的其它地址。
     */
    internal fun createRevocationHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .protocols(listOf(Protocol.HTTP_1_1))
        .addNetworkInterceptor(RevocationSingleExchangeInterceptor())
        .build()
}

/** 去掉 OkHttp 用于 503 follow-up 的信号；HTTP/1.1 同时排除 421 合并连接重发。 */
private class RevocationSingleExchangeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        return if (response.code == 503 && response.header("Retry-After") == "0") {
            response.newBuilder().removeHeader("Retry-After").build()
        } else {
            response
        }
    }
}

private const val MAX_AUTHENTICATED_REQUESTS = 64

private object DefaultDiagnosticSink {
    private val logger = Logger.getLogger("VocaEaseNetwork")

    fun accept(diagnostic: NetworkDiagnostic) {
        logger.info(diagnostic.toLogLine())
    }
}

private class SafeNetworkDiagnosticInterceptor(
    private val sink: (NetworkDiagnostic) -> Unit,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val endpoint = request.tag(Invocation::class.java)
            ?.method()
            ?.apiEndpoint()
            ?: ApiEndpoint.UNKNOWN
        return try {
            chain.proceed(request).also { response ->
                val envelope = response.safeDiagnosticEnvelope()
                sink(
                    NetworkDiagnostic.safe(
                        endpoint = endpoint,
                        status = response.code,
                        code = envelope?.code.orEmpty(),
                        requestId = envelope?.requestId ?: response.header("X-Request-ID").orEmpty(),
                    ),
                )
            }
        } catch (error: IOException) {
            sink(
                NetworkDiagnostic.safe(
                    endpoint = endpoint,
                    status = null,
                    code = "network_error",
                    requestId = "",
                ),
            )
            throw error
        }
    }
}

@Serializable
private data class DiagnosticEnvelope(
    val code: String,
    val message: String,
    val data: JsonElement?,
    @SerialName("request_id") val requestId: String,
)

private fun Response.safeDiagnosticEnvelope(): DiagnosticEnvelope? = runCatching {
    apiJson.decodeFromString<DiagnosticEnvelope>(peekBody(MAX_DIAGNOSTIC_ENVELOPE_BYTES).string())
}.getOrNull()

private fun java.lang.reflect.Method.apiEndpoint(): ApiEndpoint {
    annotations.forEach { annotation ->
        val result = when (annotation) {
            is GET -> "GET" to annotation.value
            is POST -> "POST" to annotation.value
            is PUT -> "PUT" to annotation.value
            is PATCH -> "PATCH" to annotation.value
            is DELETE -> "DELETE" to annotation.value
            is HEAD -> "HEAD" to annotation.value
            is OPTIONS -> "OPTIONS" to annotation.value
            is HTTP -> annotation.method to annotation.path
            else -> null
        }
        if (result != null) {
            val path = if (result.second.startsWith('/')) result.second else "/${result.second}"
            return ApiEndpoint.from(result.first, path.substringBefore('?'))
        }
    }
    return ApiEndpoint.UNKNOWN
}

private fun String.sanitizeDiagnostic(defaultValue: String, maxLength: Int): String {
    if (isEmpty()) return defaultValue
    val sanitized = take(maxLength).map { character ->
        if (character.isLetterOrDigit() || character in "._:/{}-") character else '_'
    }.joinToString("")
    return sanitized.ifEmpty { defaultValue }
}

private const val MAX_DIAGNOSTIC_ENVELOPE_BYTES = 64L * 1024L
