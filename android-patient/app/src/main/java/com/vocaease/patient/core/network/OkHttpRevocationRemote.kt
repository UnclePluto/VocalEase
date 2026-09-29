package com.vocaease.patient.core.network

import com.vocaease.patient.core.security.RevocationRemoteResult
import com.vocaease.patient.core.security.RevocationRemote
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 只负责旧 refresh family 的专用吊销调用，不参与普通认证请求图。 */
internal class OkHttpRevocationRemote(
    baseUrl: String,
    private val client: OkHttpClient,
) : RevocationRemote {
    private val endpoint: HttpUrl = trustedEndpoint(baseUrl)

    override suspend fun revoke(accessToken: String, refreshToken: String): RevocationRemoteResult {
        require(accessToken.isNotBlank() && refreshToken.isNotBlank())
        val body = "{\"client_kind\":\"android\",\"refresh\":${refreshToken.jsonString()}}"
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $accessToken")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> RevocationRemoteResult.Success
                        response.code in INVALID_STATUS_CODES &&
                            response.peekBody(MAX_ERROR_BODY_BYTES).string().errorCode() == "token_not_valid" ->
                            RevocationRemoteResult.InvalidOrExpired
                        else -> RevocationRemoteResult.Retryable
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            RevocationRemoteResult.Retryable
        }
    }

    private fun trustedEndpoint(rawBaseUrl: String): HttpUrl {
        val base = rawBaseUrl.toHttpUrl()
        require(base.username.isEmpty() && base.password.isEmpty())
        require(base.query == null && base.fragment == null)
        return base.newBuilder()
            .encodedPath("/api/v1/auth/logout/")
            .query(null)
            .fragment(null)
            .build()
    }

    private fun String.errorCode(): String? = runCatching {
        apiJson.parseToJsonElement(this).jsonObject["code"]?.jsonPrimitive?.content
    }.getOrNull()

    private fun String.jsonString(): String = buildString(length + 2) {
        append('"')
        for (character in this@jsonString) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val INVALID_STATUS_CODES = setOf(400, 401, 403)
        const val MAX_ERROR_BODY_BYTES = 64L * 1024L
    }
}
