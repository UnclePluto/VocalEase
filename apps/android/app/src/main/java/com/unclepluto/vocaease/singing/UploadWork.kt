package com.unclepluto.vocaease.singing

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.unclepluto.vocaease.auth.ParticipantSessionStore
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class LocalUploadStatus {
    PENDING,
    UPLOADING,
    VERIFYING,
    SUBMITTED,
    FAILED,
}

data class PendingCapture(
    val sessionId: String,
    val filePath: String,
    val accompanimentStartFrame: Long,
    val audioStartMonotonicNs: Long,
    val accompanimentStartMonotonicNs: Long,
    val recordedFrameCount: Long,
    val status: LocalUploadStatus,
    val progress: Int,
    val qualitySummary: String,
    val confirmedAtEpochMs: Long?,
    val error: String?,
)

data class ActiveCapture(
    val sessionId: String,
    val filePath: String,
    val interruptionReason: InterruptionReason?,
)

class LocalCaptureStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("singing_capture_state", Context.MODE_PRIVATE)

    fun markActive(sessionId: String, file: File) {
        preferences.edit()
            .putString(
                ACTIVE_KEY,
                JSONObject().put("session_id", sessionId).put("file_path", file.absolutePath)
                    .toString(),
            )
            .apply()
    }

    fun markInterruptionPending(reason: InterruptionReason) {
        val current = active() ?: return
        preferences.edit()
            .putString(
                ACTIVE_KEY,
                JSONObject()
                    .put("session_id", current.sessionId)
                    .put("file_path", current.filePath)
                    .put("interruption_reason", reason.name)
                    .toString(),
            )
            .apply()
    }

    fun active(): ActiveCapture? {
        val json = preferences.getString(ACTIVE_KEY, null) ?: return null
        return runCatching {
            JSONObject(json).let {
                ActiveCapture(
                    sessionId = it.getString("session_id"),
                    filePath = it.getString("file_path"),
                    interruptionReason = it.optString("interruption_reason")
                        .takeIf(String::isNotBlank)
                        ?.let(InterruptionReason::valueOf),
                )
            }
        }.getOrNull()
    }

    fun clearActive(deletePartial: Boolean) {
        if (deletePartial) active()?.let { File(it.filePath).delete() }
        preferences.edit().remove(ACTIVE_KEY).apply()
    }

    fun saveCompleted(
        sessionId: String,
        file: File,
        accompanimentStartFrame: Long,
        audioStartMonotonicNs: Long,
        accompanimentStartMonotonicNs: Long,
        recordedFrameCount: Long,
        report: ClientQualityReport,
    ) {
        update(
            PendingCapture(
                sessionId = sessionId,
                filePath = file.absolutePath,
                accompanimentStartFrame = accompanimentStartFrame,
                audioStartMonotonicNs = audioStartMonotonicNs,
                accompanimentStartMonotonicNs = accompanimentStartMonotonicNs,
                recordedFrameCount = recordedFrameCount,
                status = LocalUploadStatus.PENDING,
                progress = 0,
                qualitySummary = report.participantSummary,
                confirmedAtEpochMs = null,
                error = null,
            )
        )
        saveQualityReport(sessionId, report)
        clearActive(deletePartial = false)
    }

    fun updateStatus(
        sessionId: String,
        status: LocalUploadStatus,
        progress: Int? = null,
        error: String? = null,
        confirmedAtEpochMs: Long? = null,
    ) {
        val current = get(sessionId) ?: return
        update(
            current.copy(
                status = status,
                progress = progress ?: current.progress,
                error = error,
                confirmedAtEpochMs = confirmedAtEpochMs ?: current.confirmedAtEpochMs,
            )
        )
    }

    fun get(sessionId: String): PendingCapture? =
        parse(preferences.getString(recordKey(sessionId), null))

    fun all(): List<PendingCapture> = preferences.all
        .filterKeys { it.startsWith(RECORD_PREFIX) }
        .values
        .mapNotNull { parse(it as? String) }

    fun remove(sessionId: String) {
        preferences.edit()
            .remove(recordKey(sessionId))
            .remove(qualityKey(sessionId))
            .apply()
    }

    fun qualitySubmission(sessionId: String): JSONObject? =
        preferences.getString(qualityKey(sessionId), null)?.let {
            runCatching { JSONObject(it).getJSONObject("submission") }.getOrNull()
        }

    private fun update(capture: PendingCapture) {
        val json = JSONObject()
            .put("session_id", capture.sessionId)
            .put("file_path", capture.filePath)
            .put("accompaniment_start_frame", capture.accompanimentStartFrame)
            .put("audio_start_monotonic_ns", capture.audioStartMonotonicNs)
            .put("accompaniment_start_monotonic_ns", capture.accompanimentStartMonotonicNs)
            .put("recorded_frame_count", capture.recordedFrameCount)
            .put("status", capture.status.name)
            .put("progress", capture.progress)
            .put("quality_summary", capture.qualitySummary)
            .put("confirmed_at", capture.confirmedAtEpochMs)
            .put("error", capture.error)
        preferences.edit().putString(recordKey(capture.sessionId), json.toString()).apply()
    }

    private fun saveQualityReport(sessionId: String, report: ClientQualityReport) {
        val markers = JSONArray()
        report.markers.forEach {
            markers.put(
                JSONObject()
                    .put("kind", it.kind)
                    .put("start_ms", it.startMs)
                    .put("end_ms", it.endMs)
                    .put("value", it.value)
            )
        }
        val json = JSONObject()
            .put("generated_at", report.generatedAt.toString())
            .put(
                "submission",
                JSONObject()
                    .put("algorithm_version", report.algorithmVersion)
                    .put("status", if (report.status == "ok") "ok" else "warning")
                    .put(
                        "metrics",
                        JSONObject()
                            .put("readable", report.readable)
                            .put("sample_rate", report.sampleRate ?: RAW_SAMPLE_RATE)
                            .put("channels", report.channels ?: 1)
                            .put("bit_depth", report.bitDepth ?: 16)
                            .put("duration_ms", report.durationMs ?: 0)
                            .put("rms_dbfs", report.rmsDbfs ?: -120.0)
                            .put("silent_sample_ratio", report.silenceRatio ?: 1.0)
                            .put("clipped_sample_ratio", report.clippingRatio ?: 0.0)
                            .put("stage_complete", report.stageComplete)
                            .put("used_headphones", report.usedHeadphones)
                            .put("route_risk", report.routeRisk)
                            .put("file_warnings", JSONArray(report.fileWarnings))
                            .put("markers", markers)
                    )
            )
        preferences.edit().putString(qualityKey(sessionId), json.toString()).apply()
    }

    private fun parse(raw: String?): PendingCapture? = raw?.let {
        runCatching {
            JSONObject(it).let { json ->
                PendingCapture(
                    sessionId = json.getString("session_id"),
                    filePath = json.getString("file_path"),
                    accompanimentStartFrame = json.getLong("accompaniment_start_frame"),
                    audioStartMonotonicNs = json.getLong("audio_start_monotonic_ns"),
                    accompanimentStartMonotonicNs =
                        json.getLong("accompaniment_start_monotonic_ns"),
                    recordedFrameCount = json.getLong("recorded_frame_count"),
                    status = LocalUploadStatus.valueOf(json.getString("status")),
                    progress = json.getInt("progress"),
                    qualitySummary = json.getString("quality_summary"),
                    confirmedAtEpochMs = json.optLong("confirmed_at").takeIf { value -> value > 0 },
                    error = json.optString("error").takeIf(String::isNotBlank),
                )
            }
        }.getOrNull()
    }

    private companion object {
        const val ACTIVE_KEY = "active_capture"
        const val RECORD_PREFIX = "capture."
        fun recordKey(sessionId: String) = "$RECORD_PREFIX$sessionId"
        fun qualityKey(sessionId: String) = "quality.$sessionId"
    }
}

class VoiceUploadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return@withContext Result.failure()
        val baseUrl = inputData.getString(KEY_BASE_URL) ?: return@withContext Result.failure()
        val store = LocalCaptureStore(applicationContext)
        val capture = store.get(sessionId) ?: return@withContext Result.failure()
        val token = ParticipantSessionStore(applicationContext).token()
            ?: return@withContext Result.failure()
        val file = File(capture.filePath)
        if (!file.isFile) {
            store.updateStatus(sessionId, LocalUploadStatus.FAILED, error = "本地原始录音不存在")
            return@withContext Result.failure()
        }
        val client = VoiceUploadClient(baseUrl)
        try {
            client.ensureCaptureCompleted(
                token,
                sessionId,
                capture.accompanimentStartFrame,
                capture.audioStartMonotonicNs,
                capture.accompanimentStartMonotonicNs,
                capture.recordedFrameCount,
            )
            val qualitySubmission = store.qualitySubmission(sessionId)
                ?: error("端侧技术质检报告不存在")
            client.submitClientQuality(token, sessionId, qualitySubmission)
            store.updateStatus(sessionId, LocalUploadStatus.UPLOADING, progress = 0)
            val upload = client.createOrResume(token, sessionId, file)
            client.uploadMissingChunks(token, upload, file) { sent, total ->
                store.updateStatus(
                    sessionId,
                    LocalUploadStatus.UPLOADING,
                    progress = sent * 100 / total,
                )
                setProgressAsync(Data.Builder().putInt(KEY_PROGRESS, sent * 100 / total).build())
            }
            store.updateStatus(sessionId, LocalUploadStatus.VERIFYING, progress = 100)
            val completed = client.complete(token, upload.id)
            check(completed.status == "verified") { "服务端尚未确认原始录音" }
            val confirmedAt = Clock.systemUTC().millis()
            store.updateStatus(
                sessionId,
                LocalUploadStatus.SUBMITTED,
                progress = 100,
                confirmedAtEpochMs = confirmedAt,
            )
            enqueueCleanup(applicationContext, sessionId)
            Result.success()
        } catch (error: Throwable) {
            when (uploadFailureDecision(runAttemptCount, MAX_RETRIES)) {
                UploadFailureDecision.RETRY_AUTOMATICALLY -> {
                    store.updateStatus(
                        sessionId,
                        LocalUploadStatus.PENDING,
                        error = "网络或服务暂不可用，将自动重试",
                    )
                    Result.retry()
                }
                UploadFailureDecision.EXPOSE_MANUAL_RETRY -> {
                    store.updateStatus(
                        sessionId,
                        LocalUploadStatus.FAILED,
                        error = error.message ?: "上传失败",
                    )
                    Result.failure()
                }
            }
        }
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"
        const val KEY_BASE_URL = "base_url"
        const val KEY_PROGRESS = "progress"
        const val MAX_RETRIES = 5
    }
}

class LocalRecordingCleanupWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        deleteExpiredRecordings(LocalCaptureStore(applicationContext), Clock.systemUTC())
        return Result.success()
    }
}

fun enqueueVoiceUpload(
    context: Context,
    sessionId: String,
    baseUrl: String,
    manualRetry: Boolean = false,
) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
    val request = OneTimeWorkRequestBuilder<VoiceUploadWorker>()
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
        .setInputData(
            Data.Builder()
                .putString(VoiceUploadWorker.KEY_SESSION_ID, sessionId)
                .putString(VoiceUploadWorker.KEY_BASE_URL, baseUrl)
                .build()
        )
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        "voice-upload-$sessionId",
        if (shouldReplaceUploadWork(manualRetry)) {
            ExistingWorkPolicy.REPLACE
        } else {
            ExistingWorkPolicy.KEEP
        },
        request,
    )
}

fun shouldReplaceUploadWork(manualRetry: Boolean): Boolean = manualRetry

fun enqueueCleanup(context: Context, sessionId: String) {
    val request = OneTimeWorkRequestBuilder<LocalRecordingCleanupWorker>()
        .setInitialDelay(RETENTION_DAYS, TimeUnit.DAYS)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        "voice-cleanup-$sessionId",
        ExistingWorkPolicy.KEEP,
        request,
    )
}

fun deleteExpiredRecordings(
    store: LocalCaptureStore,
    clock: Clock,
    retention: Duration = Duration.ofDays(RETENTION_DAYS),
): Int {
    var deleted = 0
    store.all().filter {
        it.status == LocalUploadStatus.SUBMITTED &&
            it.confirmedAtEpochMs != null &&
            isRetentionExpired(it.confirmedAtEpochMs, clock.instant(), retention)
    }.forEach {
        if (!File(it.filePath).exists() || File(it.filePath).delete()) {
            store.remove(it.sessionId)
            deleted++
        }
    }
    return deleted
}

fun isRetentionExpired(
    confirmedAtEpochMs: Long,
    now: Instant,
    retention: Duration = Duration.ofDays(RETENTION_DAYS),
): Boolean = confirmedAtEpochMs <= now.minus(retention).toEpochMilli()

fun uploadStatusLabel(capture: PendingCapture?): String = when (capture?.status) {
    LocalUploadStatus.PENDING -> "等待网络后自动上传"
    LocalUploadStatus.UPLOADING -> "正在上传 ${capture.progress}%"
    LocalUploadStatus.VERIFYING -> "服务端正在校验"
    LocalUploadStatus.SUBMITTED -> "已提交"
    LocalUploadStatus.FAILED -> "上传失败，请手动重试"
    null -> "正在准备上传"
}

private const val RETENTION_DAYS = 7L
