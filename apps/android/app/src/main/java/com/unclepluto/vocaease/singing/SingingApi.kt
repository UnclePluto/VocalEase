package com.unclepluto.vocaease.singing

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.math.ceil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class DeviceSnapshot(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val appVersion: String,
    val inputType: String,
    val outputRoute: String,
    val bluetoothMode: String?,
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("manufacturer", manufacturer.take(80))
        .put("model", model.take(120))
        .put("android_version", androidVersion.take(40))
        .put("app_version", appVersion.take(40))
        .put("input_type", inputType.take(40))
        .put("output_route", outputRoute.take(40))
        .put("bluetooth_mode", bluetoothMode)
        .put("sample_rate", sampleRate)
        .put("channels", channels)
        .put("bit_depth", bitDepth)
}

data class RemoteSingingSession(
    val id: String,
    val status: String,
    val preDurationMs: Long,
    val songDurationMs: Long,
    val postDurationMs: Long,
)

data class RemoteUpload(
    val id: String,
    val status: String,
    val expectedChunks: Int,
    val receivedChunks: Set<Int>,
    val qualityReport: JSONObject? = null,
)

data class PlaybackMix(
    val status: String,
    val mediaReady: Boolean,
    val failureMessage: String?,
)

data class PlaybackAccess(
    val url: String,
    val expiresInSeconds: Int,
)

interface SingingGateway {
    fun createSession(
        token: String,
        songId: String,
        backingTrackId: String,
        lyricVersionId: String,
        usedHeadphones: Boolean,
        headphoneRiskConfirmed: Boolean,
        snapshot: DeviceSnapshot,
    ): RemoteSingingSession

    fun completeCapture(
        token: String,
        sessionId: String,
        accompanimentStartFrame: Long,
        audioStartMonotonicNs: Long,
        accompanimentStartMonotonicNs: Long,
        recordedFrameCount: Long,
    )
    fun interrupt(token: String, sessionId: String, reason: InterruptionReason)
}

enum class InterruptionReason(val wireValue: String) {
    USER_CANCELLED("user_cancelled"),
    APP_BACKGROUNDED("app_backgrounded"),
    AUDIO_FOCUS_LOST("audio_focus_lost"),
    ROUTE_CHANGED("route_changed"),
    PROCESS_RECOVERED("process_recovered"),
    CAPTURE_ERROR("capture_error"),
}

class HttpSingingGateway(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
) : SingingGateway {
    override fun createSession(
        token: String,
        songId: String,
        backingTrackId: String,
        lyricVersionId: String,
        usedHeadphones: Boolean,
        headphoneRiskConfirmed: Boolean,
        snapshot: DeviceSnapshot,
    ): RemoteSingingSession {
        val payload = JSONObject()
            .put("song_id", songId)
            .put("backing_track_id", backingTrackId)
            .put("lyric_version_id", lyricVersionId)
            .put("used_headphones", usedHeadphones)
            .put("headphone_risk_confirmed", headphoneRiskConfirmed)
            .put("device_snapshot", snapshot.toJson())
        return parseSession(post("/api/v1/singing-sessions", token, payload))
    }

    override fun completeCapture(
        token: String,
        sessionId: String,
        accompanimentStartFrame: Long,
        audioStartMonotonicNs: Long,
        accompanimentStartMonotonicNs: Long,
        recordedFrameCount: Long,
    ) {
        post(
            "/api/v1/singing-sessions/$sessionId/capture-completed",
            token,
            JSONObject()
                .put("accompaniment_start_frame", accompanimentStartFrame)
                .put("audio_start_monotonic_ns", audioStartMonotonicNs)
                .put("accompaniment_start_monotonic_ns", accompanimentStartMonotonicNs)
                .put("recorded_frame_count", recordedFrameCount),
        )
    }

    override fun interrupt(token: String, sessionId: String, reason: InterruptionReason) {
        post(
            "/api/v1/singing-sessions/$sessionId/interrupt",
            token,
            JSONObject().put("reason", reason.wireValue),
        )
    }

    private fun post(path: String, token: String, payload: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            check(response.isSuccessful) { apiError(response.code, body) }
            return JSONObject(body.ifBlank { "{}" })
        }
    }

    private fun parseSession(body: JSONObject): RemoteSingingSession = RemoteSingingSession(
        id = body.getString("id"),
        status = body.getString("status"),
        preDurationMs = body.getLong("pre_duration_ms"),
        songDurationMs = body.getLong("song_duration_ms"),
        postDurationMs = body.getLong("post_duration_ms"),
    )

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

class VoiceUploadClient(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
) {
    fun ensureCaptureCompleted(
        token: String,
        sessionId: String,
        accompanimentStartFrame: Long,
        audioStartMonotonicNs: Long,
        accompanimentStartMonotonicNs: Long,
        recordedFrameCount: Long,
    ) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/v1/singing-sessions/$sessionId")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val status = executeObject(request).getString("status")
        if (status == "recording") {
            post(
                "/api/v1/singing-sessions/$sessionId/capture-completed",
                token,
                JSONObject()
                    .put("accompaniment_start_frame", accompanimentStartFrame)
                    .put("audio_start_monotonic_ns", audioStartMonotonicNs)
                    .put("accompaniment_start_monotonic_ns", accompanimentStartMonotonicNs)
                    .put("recorded_frame_count", recordedFrameCount),
            )
        } else {
            check(status in setOf("pending_upload", "uploading", "upload_failed", "submitted")) {
                "演唱会话状态不能上传：$status"
            }
        }
    }

    fun submitClientQuality(token: String, sessionId: String, report: JSONObject) {
        post(
            "/api/v1/singing-sessions/$sessionId/quality-reports/client",
            token,
            report,
        )
    }

    fun createOrResume(token: String, sessionId: String, file: File): RemoteUpload {
        val chunkCount = ceil(file.length().toDouble() / CHUNK_BYTES).toInt().coerceAtLeast(1)
        val payload = JSONObject()
            .put("expected_chunks", chunkCount)
            .put("total_bytes", file.length())
            .put("total_sha256", sha256(file))
        return post(
            "/api/v1/singing-sessions/$sessionId/upload",
            token,
            payload,
        ).toUpload()
    }

    fun uploadMissingChunks(
        token: String,
        upload: RemoteUpload,
        file: File,
        onProgress: (uploaded: Int, total: Int) -> Unit,
    ) {
        RandomAccessFile(file, "r").use { input ->
            repeat(upload.expectedChunks) { index ->
                if (index in upload.receivedChunks) {
                    onProgress(index + 1, upload.expectedChunks)
                    return@repeat
                }
                val offset = index.toLong() * CHUNK_BYTES
                input.seek(offset)
                val length = minOf(CHUNK_BYTES.toLong(), file.length() - offset).toInt()
                val content = ByteArray(length)
                input.readFully(content)
                val request = Request.Builder()
                    .url(baseUrl.trimEnd('/') + "/api/v1/voice-uploads/${upload.id}/chunks/$index")
                    .header("Authorization", "Bearer $token")
                    .header("X-Chunk-SHA256", sha256(content))
                    .put(content.toRequestBody(OCTETS))
                    .build()
                executeObject(request)
                onProgress(index + 1, upload.expectedChunks)
            }
        }
    }

    fun complete(token: String, uploadId: String): RemoteUpload {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/v1/voice-uploads/$uploadId/complete")
            .header("Authorization", "Bearer $token")
            .post(ByteArray(0).toRequestBody(OCTETS))
            .build()
        return executeObject(request).toUpload()
    }

    private fun post(path: String, token: String, payload: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        return executeObject(request)
    }

    private fun executeObject(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            check(response.isSuccessful) { apiError(response.code, body) }
            return JSONObject(body.ifBlank { "{}" })
        }
    }

    private fun JSONObject.toUpload(): RemoteUpload {
        val received = getJSONArray("received_chunks")
        return RemoteUpload(
            id = getString("id"),
            status = getString("status"),
            expectedChunks = getInt("expected_chunks"),
            receivedChunks = buildSet {
                repeat(received.length()) { add(received.getInt(it)) }
            },
            qualityReport = optJSONObject("quality_report"),
        )
    }

    companion object {
        const val CHUNK_BYTES = 2 * 1024 * 1024
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTETS = "application/octet-stream".toMediaType()
    }
}

class PlaybackMixClient(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
) {
    fun status(token: String, sessionId: String): PlaybackMix {
        val request = request(
            token,
            "/api/v1/singing-sessions/$sessionId/playback-mix",
            post = false,
        )
        val body = execute(request)
        return PlaybackMix(
            status = body.getString("status"),
            mediaReady = body.getBoolean("media_ready"),
            failureMessage = body.optString("failure_message").takeIf(String::isNotBlank),
        )
    }

    fun retry(token: String, sessionId: String): PlaybackMix {
        val body = execute(
            request(
                token,
                "/api/v1/singing-sessions/$sessionId/playback-mix/retry",
                post = true,
            )
        )
        return PlaybackMix(
            status = body.getString("status"),
            mediaReady = body.getBoolean("media_ready"),
            failureMessage = body.optString("failure_message").takeIf(String::isNotBlank),
        )
    }

    fun access(token: String, sessionId: String): PlaybackAccess {
        val body = execute(
            request(
                token,
                "/api/v1/singing-sessions/$sessionId/playback-mix/access",
                post = true,
            )
        )
        val path = body.getString("url")
        return PlaybackAccess(
            url = java.net.URI(baseUrl.trimEnd('/') + "/").resolve(path).toString(),
            expiresInSeconds = body.getInt("expires_in_seconds"),
        )
    }

    private fun request(token: String, path: String, post: Boolean): Request {
        val builder = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $token")
        return if (post) {
            builder.post(ByteArray(0).toRequestBody(OCTETS)).build()
        } else {
            builder.get().build()
        }
    }

    private fun execute(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            check(response.isSuccessful) { apiError(response.code, body) }
            return JSONObject(body)
        }
    }

    private companion object {
        val OCTETS = "application/octet-stream".toMediaType()
    }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun sha256(content: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(content)
        .joinToString("") { "%02x".format(it) }

private fun apiError(code: Int, body: String): String {
    val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
    return detail?.takeIf(String::isNotBlank) ?: "请求失败（$code）"
}
