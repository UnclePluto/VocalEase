package com.vocaease.patient.feature.upload

import com.qiniu.android.http.ResponseInfo
import com.qiniu.android.http.metrics.UploadSingleRequestMetrics
import com.qiniu.android.http.request.IRequestClient
import com.qiniu.android.http.request.Request as QiniuRequest
import java.io.IOException
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject

fun interface QiniuRequestUrlPolicy {
    fun allows(url: HttpUrl): Boolean
}

object OfficialQiniuRequestUrlPolicy : QiniuRequestUrlPolicy {
    override fun allows(url: HttpUrl): Boolean =
        url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            OfficialQiniuUploadHostAllowlist.allows(url.host)
}

/**
 * 七牛 SDK 的受控请求层。每个请求发送前都重新检查目的地，并从客户端层禁用 HTTP/HTTPS 重定向，
 * 防止短期上传凭证或分片内容被 30x 响应带到非可信主机。
 */
internal class SafeQiniuRequestClient(
    private val policy: QiniuRequestUrlPolicy = OfficialQiniuRequestUrlPolicy,
    client: OkHttpClient = OkHttpClient(),
) : IRequestClient() {
    private val httpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val activeCall = AtomicReference<Call?>(null)

    override fun request(
        request: QiniuRequest,
        options: Options?,
        progress: Progress?,
        complete: CompleteHandler,
    ) {
        val url = request.urlString.toHttpUrlOrNullSafely()
        if (url == null || !policy.allows(url)) {
            complete.complete(ResponseInfo.invalidArgument("上传请求地址无效"), UploadSingleRequestMetrics(), null)
            return
        }
        val metrics = UploadSingleRequestMetrics().apply {
            start()
            setRequest(request)
            requestStartDate = Date()
            clientName = "vocaease-okhttp"
        }
        val finished = AtomicBoolean(false)
        fun finish(responseInfo: ResponseInfo, responseJson: JSONObject?) {
            if (!finished.compareAndSet(false, true)) return
            metrics.requestEndDate = Date()
            metrics.end()
            complete.complete(responseInfo, metrics, responseJson)
        }

        val call = runCatching {
            val builder = Request.Builder().url(url)
            request.allHeaders.forEach { (name, value) -> builder.header(name, value) }
            val body = request.httpBody
            val requestBody = if (body == null) null else ProgressRequestBody(
                bytes = body,
                contentType = request.allHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }
                    ?.value?.toMediaTypeOrNull(),
                progress = progress,
            )
            builder.method(request.httpMethod, requestBody).build()
            val configured = httpClient.newBuilder()
                .connectTimeout(request.connectTimeout.toLong(), TimeUnit.SECONDS)
                .readTimeout(request.readTimeout.toLong(), TimeUnit.SECONDS)
                .writeTimeout(request.writeTimeout.toLong(), TimeUnit.SECONDS)
                .build()
            configured.newCall(builder.build())
        }.getOrElse {
            finish(ResponseInfo.invalidArgument("上传请求无效"), null)
            return
        }
        activeCall.getAndSet(call)?.cancel()
        val callback = object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activeCall.compareAndSet(call, null)
                finish(
                    if (call.isCanceled()) ResponseInfo.cancelled()
                    else ResponseInfo.networkError("上传网络请求失败"),
                    null,
                )
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    activeCall.compareAndSet(call, null)
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    metrics.countOfRequestBodyBytesSent = request.httpBody?.size?.toLong() ?: 0
                    metrics.countOfResponseBodyBytesReceived = bytes.size.toLong()
                    val json = bytes.takeIf { it.isNotEmpty() }?.let {
                        runCatching { JSONObject(it.toString(Charsets.UTF_8)) }.getOrNull()
                    }
                    val headers = response.headers.toMultimap().mapValues { it.value.joinToString(",") }
                    finish(
                        ResponseInfo.create(request, response.code, headers, json, null),
                        json,
                    )
                }
            }
        }
        if (options?.isAsync != false) {
            call.enqueue(callback)
        } else {
            runCatching { call.execute() }
                .onSuccess { callback.onResponse(call, it) }
                .onFailure { callback.onFailure(call, it as? IOException ?: IOException("上传网络请求失败")) }
        }
    }

    override fun cancel() {
        activeCall.getAndSet(null)?.cancel()
    }

    override fun getClientId(): String = "vocaease-okhttp"

    private fun String.toHttpUrlOrNullSafely(): HttpUrl? = runCatching { toHttpUrlOrNull() }.getOrNull()

    private class ProgressRequestBody(
        private val bytes: ByteArray,
        private val contentType: okhttp3.MediaType?,
        private val progress: Progress?,
    ) : RequestBody() {
        override fun contentType() = contentType
        override fun contentLength() = bytes.size.toLong()
        override fun writeTo(sink: BufferedSink) {
            var offset = 0
            while (offset < bytes.size) {
                val count = minOf(64 * 1024, bytes.size - offset)
                sink.write(bytes, offset, count)
                offset += count
                progress?.progress(offset.toLong(), bytes.size.toLong())
            }
        }
    }
}
