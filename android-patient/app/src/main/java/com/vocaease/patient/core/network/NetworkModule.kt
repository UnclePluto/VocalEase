package com.vocaease.patient.core.network

import com.vocaease.patient.BuildConfig
import java.io.IOException
import java.util.logging.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
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

class NetworkDiagnostic private constructor(
    val method: String,
    val pathTemplate: String,
    val status: Int?,
    val code: String,
    val requestId: String,
) {
    fun toLogLine(): String =
        "method=$method path=$pathTemplate status=${status ?: "-"} code=$code requestId=$requestId"

    companion object {
        fun safe(
            method: String,
            pathTemplate: String,
            status: Int?,
            code: String,
            requestId: String,
        ): NetworkDiagnostic = NetworkDiagnostic(
            method = method.sanitizeDiagnostic(defaultValue = "UNKNOWN", maxLength = 12),
            pathTemplate = pathTemplate.sanitizeDiagnostic(defaultValue = "/unknown", maxLength = 180),
            status = status,
            code = code.sanitizeDiagnostic(defaultValue = "unknown", maxLength = 64),
            requestId = requestId.sanitizeDiagnostic(defaultValue = "", maxLength = 64),
        )
    }
}

object NetworkModule {
    fun createApi(
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
}

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
        val (method, template) = request.tag(Invocation::class.java)
            ?.method()
            ?.httpTemplate()
            ?: (request.method to "/unknown")
        return try {
            chain.proceed(request).also { response ->
                val envelope = response.safeDiagnosticEnvelope()
                sink(
                    NetworkDiagnostic.safe(
                        method = method,
                        pathTemplate = template,
                        status = response.code,
                        code = envelope?.code.orEmpty(),
                        requestId = envelope?.requestId ?: response.header("X-Request-ID").orEmpty(),
                    ),
                )
            }
        } catch (error: IOException) {
            sink(
                NetworkDiagnostic.safe(
                    method = method,
                    pathTemplate = template,
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

private fun java.lang.reflect.Method.httpTemplate(): Pair<String, String>? {
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
            return result.first to path.substringBefore('?')
        }
    }
    return null
}

private fun String.sanitizeDiagnostic(defaultValue: String, maxLength: Int): String {
    if (isEmpty()) return defaultValue
    val sanitized = take(maxLength).map { character ->
        if (character.isLetterOrDigit() || character in "._:/{}-") character else '_'
    }.joinToString("")
    return sanitized.ifEmpty { defaultValue }
}

private const val MAX_DIAGNOSTIC_ENVELOPE_BYTES = 64L * 1024L
