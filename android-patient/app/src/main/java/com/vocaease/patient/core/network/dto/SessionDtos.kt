package com.vocaease.patient.core.network.dto

import com.vocaease.patient.core.network.NetworkContractException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class SessionStatus(val serializedValue: String) {
    @SerialName("created") CREATED("created"),
    @SerialName("awaiting_upload") AWAITING_UPLOAD("awaiting_upload"),
    @SerialName("uploaded") UPLOADED("uploaded"),
    @SerialName("processing") PROCESSING("processing"),
    @SerialName("completed") COMPLETED("completed"),
    @SerialName("failed") FAILED("failed"),
    @SerialName("cancelled") CANCELLED("cancelled"),
    ;

    override fun toString(): String = serializedValue
}

@Serializable
enum class MediaType(val serializedValue: String) {
    @SerialName("singing_audio") SINGING_AUDIO("singing_audio"),
    @SerialName("singing_video") SINGING_VIDEO("singing_video"),
    ;

    override fun toString(): String = serializedValue
}

enum class AnalysisTaskType(val serializedValue: String) {
    VOCAL_SEPARATION("vocal_separation"),
    ACCOMPANIMENT_GENERATION("accompaniment_generation"),
    LYRICS_RECOGNITION("lyrics_recognition"),
    SINGING_AUDIO_METRICS("singing_audio_metrics"),
    FACE_LANDMARKS("face_landmarks"),
    UNKNOWN("unknown"),
    ;

    companion object {
        internal fun fromSerialized(value: String): AnalysisTaskType = entries.firstOrNull {
            it != UNKNOWN && it.serializedValue == value
        } ?: UNKNOWN
    }
}

@Serializable
enum class AnalysisTaskStatus {
    @SerialName("pending") PENDING,
    @SerialName("processing") PROCESSING,
    @SerialName("succeeded") SUCCEEDED,
    @SerialName("failed") FAILED,
    @SerialName("retrying") RETRYING,
    @SerialName("superseded") SUPERSEDED,
}

@Serializable
data class CreateSessionRequestDto(
    @SerialName("song_id") val songId: String,
)

@Serializable
data class SessionUploadGrantRequestDto(
    @SerialName("media_type") val mediaType: MediaType,
    val mime: String,
    val size: Long,
)

@Serializable
data class PatientMediaUploadGrantRequestDto(
    @SerialName("owner_id") val ownerId: String,
    @SerialName("media_type") val mediaType: MediaType,
    val mime: String,
    val size: Long,
)

@Serializable
data class ConfirmSessionMediaRequestDto(
    @SerialName("asset_id") val assetId: String? = null,
    @SerialName("object_key") val objectKey: String? = null,
) {
    init {
        if (assetId == null && objectKey == null) {
            throw NetworkContractException("confirm_upload 至少需要 asset_id 或 object_key")
        }
        assetId?.asUuid("confirm_upload.asset_id")
        if (objectKey != null && (objectKey.isBlank() || objectKey.length > 255)) {
            throw NetworkContractException("confirm_upload.object_key 长度无效")
        }
    }
}

@Serializable
data class PatientSnapshotDto(
    val id: String,
    @SerialName("medical_record_no") val medicalRecordNo: String,
    val name: String,
)

@Serializable
data class SongSnapshotDto(
    val id: String,
    val title: String,
    val artist: String,
    @SerialName("duration_seconds") val durationSeconds: Int,
)

@Serializable
data class TreatmentPlanSnapshotDto(
    val id: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("cycle_weeks") val cycleWeeks: Int,
    @SerialName("target_session_count") val targetSessionCount: Int,
)

@Serializable
data class SessionMediaDto(
    @SerialName("asset_id") val assetId: String,
    @SerialName("media_type") val mediaType: MediaType,
    val status: String,
    val mime: String,
    val size: Long,
    @SerialName("confirmed_at") val confirmedAt: String? = null,
)

@Serializable
data class AnalysisTimeSeriesDto(
    @SerialName("sample_interval_ms") val sampleIntervalMs: Int,
    val values: List<Double>,
)

@Serializable
class SingingAnalysisResultDto(
    val id: String,
    @SerialName("task_type") val taskType: String,
    val status: AnalysisTaskStatus,
    val generation: Int,
    @SerialName("protocol_version") val protocolVersion: String,
    @SerialName("is_mock") val isMock: Boolean?,
    private val payload: JsonObject?,
    @SerialName("time_series") val timeSeries: Map<String, AnalysisTimeSeriesDto>,
    @SerialName("error_code") val errorCode: String,
    @SerialName("error_summary") val errorSummary: String,
    val attempt: Int,
) {
    internal fun toDomain(): SingingAnalysisResult = SingingAnalysisResult(
        id = id.asUuid("session.analysis_results.id"),
        taskType = AnalysisTaskType.fromSerialized(taskType),
        status = status,
        generation = generation,
        protocolVersion = protocolVersion,
        isMock = isMock,
        payload = payload.toTypedPayload(
            taskType = taskType,
            protocolVersion = protocolVersion,
            isMock = isMock,
        ),
        timeSeries = timeSeries.mapValues { (metric, value) ->
            if (value.sampleIntervalMs <= 0) {
                throw NetworkContractException("analysis.time_series.$metric.sample_interval_ms 必须大于 0")
            }
            AnalysisTimeSeries(value.sampleIntervalMs, value.values)
        },
        errorCode = errorCode,
        errorSummary = errorSummary,
        attempt = attempt,
    )
}

@Serializable
data class SingingSessionDto(
    val playback: PlaybackBindingDto? = null,
    val id: String,
    val patient: PatientSnapshotDto,
    val song: SongSnapshotDto,
    @SerialName("treatment_plan") val treatmentPlan: TreatmentPlanSnapshotDto?,
    val status: SessionStatus,
    val score: Int? = null,
    @SerialName("burp_count") val burpCount: Int? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("is_mock") val isMock: Boolean = false,
    @SerialName("created_source") val createdSource: String = "",
    @SerialName("analysis_generation") val analysisGeneration: Int = 0,
    @SerialName("submitted_at") val submittedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val media: List<SessionMediaDto>,
    @SerialName("analysis_task_ids") val analysisTaskIds: List<String>,
    @SerialName("analysis_results") val analysisResults: List<SingingAnalysisResultDto>,
)

@Serializable
data class SessionSummaryDto(
    val id: String,
    val patient: PatientSnapshotDto,
    val song: SongSnapshotDto,
    @SerialName("treatment_plan") val treatmentPlan: TreatmentPlanSnapshotDto?,
    val status: SessionStatus,
    val score: Int? = null,
    @SerialName("burp_count") val burpCount: Int? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("is_mock") val isMock: Boolean = false,
    @SerialName("created_source") val createdSource: String = "",
    @SerialName("analysis_generation") val analysisGeneration: Int = 0,
    @SerialName("submitted_at") val submittedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class SessionPageDto(
    val count: Int,
    val page: Int,
    @SerialName("page_size") val pageSize: Int,
    val results: List<SessionSummaryDto>,
)

@Serializable
data class SessionUploadGrantDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("asset_id") val assetId: String,
    @SerialName("object_key") val objectKey: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("upload_url") val uploadUrl: String,
    @SerialName("upload_token") val uploadToken: String,
    val fields: Map<String, String>,
)

@Serializable
data class PatientMediaUploadGrantDto(
    @SerialName("asset_id") val assetId: String,
    @SerialName("object_key") val objectKey: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("upload_url") val uploadUrl: String,
    @SerialName("upload_token") val uploadToken: String,
    val fields: Map<String, String>,
)

@Serializable
data class SessionMutationDto(
    @SerialName("session_id") val sessionId: String,
    val status: SessionStatus,
    @SerialName("analysis_task_ids") val analysisTaskIds: List<String>,
)

@Serializable
data class PrivateUrlDto(
    val url: String,
    @SerialName("expires_at") val expiresAt: String,
)

data class PatientSnapshot(
    val id: UUID,
    val medicalRecordNo: String,
    val name: String,
)

data class SongSnapshot(
    val id: UUID,
    val title: String,
    val artist: String,
    val durationSeconds: Int,
)

data class TreatmentPlanSnapshot(
    val id: UUID,
    val startDate: LocalDate,
    val cycleWeeks: Int,
    val targetSessionCount: Int,
)

data class SessionMedia(
    val assetId: UUID,
    val mediaType: MediaType,
    val status: String,
    val mime: String,
    val size: Long,
    val confirmedAt: Instant?,
)

sealed interface AnalysisPayload

enum class AnalysisPayloadIssue {
    NON_MOCK,
    UNKNOWN_TASK_TYPE,
    UNSUPPORTED_PROTOCOL,
    UNRECOGNIZED_STRUCTURE,
}

data class UnsupportedAnalysisPayload(
    val issue: AnalysisPayloadIssue,
) : AnalysisPayload

data object UnavailableAnalysisPayload : AnalysisPayload

data class SingingAudioAnalysisPayload(
    val protocolVersion: String,
    val isMock: Boolean,
    val score: Int,
    val burpEvents: List<Int>,
    val sampleIntervalMs: Int,
    val series: Map<String, List<Double>>,
) : AnalysisPayload

data class FaceLandmarksAnalysisPayload(
    val protocolVersion: String,
    val isMock: Boolean,
) : AnalysisPayload

data class AnalysisTimeSeries(
    val sampleIntervalMs: Int,
    val values: List<Double>,
)

data class SingingAnalysisResult(
    val id: UUID,
    val taskType: AnalysisTaskType,
    val status: AnalysisTaskStatus,
    val generation: Int,
    val protocolVersion: String,
    val isMock: Boolean?,
    val payload: AnalysisPayload,
    val timeSeries: Map<String, AnalysisTimeSeries>,
    val errorCode: String,
    val errorSummary: String,
    val attempt: Int,
)

data class SingingSession(
    val id: UUID,
    val patient: PatientSnapshot,
    val song: SongSnapshot,
    val treatmentPlan: TreatmentPlanSnapshot?,
    val status: SessionStatus,
    val score: Int?,
    val burpCount: Int?,
    val durationSeconds: Int?,
    val isMock: Boolean,
    val createdSource: String,
    val analysisGeneration: Int,
    val submittedAt: Instant?,
    val completedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val media: List<SessionMedia>,
    val analysisTaskIds: List<UUID>,
    val analysisResults: List<SingingAnalysisResult>,
    val playback: PlaybackBindingDto? = null,
)

data class SingingSessionSummary(
    val id: UUID,
    val patient: PatientSnapshot,
    val song: SongSnapshot,
    val treatmentPlan: TreatmentPlanSnapshot?,
    val status: SessionStatus,
    val score: Int?,
    val burpCount: Int?,
    val durationSeconds: Int?,
    val isMock: Boolean,
    val createdSource: String,
    val analysisGeneration: Int,
    val submittedAt: Instant?,
    val completedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class SingingSessionPage(
    val count: Int,
    val page: Int,
    val pageSize: Int,
    val results: List<SingingSessionSummary>,
)

data class SessionMutation(
    val sessionId: UUID,
    val status: SessionStatus,
    val analysisTaskIds: List<UUID>,
)

data class UploadGrant(
    val sessionId: UUID?,
    val assetId: UUID,
    val objectKey: String,
    val expiresAt: Instant,
    val uploadUrl: String,
    val uploadToken: String,
    val fields: Map<String, String>,
)

data class PrivateUrl(
    val url: String,
    val expiresAt: Instant,
)

fun SingingSessionDto.toDomain(): SingingSession = SingingSession(
    playback = playback,
    id = id.asUuid("session.id"),
    patient = patient.toDomain(),
    song = song.toDomain(),
    treatmentPlan = treatmentPlan?.toDomain(),
    status = status,
    score = score,
    burpCount = burpCount,
    durationSeconds = durationSeconds,
    isMock = isMock,
    createdSource = createdSource,
    analysisGeneration = analysisGeneration,
    submittedAt = submittedAt?.asInstant("session.submitted_at"),
    completedAt = completedAt?.asInstant("session.completed_at"),
    createdAt = createdAt.asInstant("session.created_at"),
    updatedAt = updatedAt.asInstant("session.updated_at"),
    media = media.map { value ->
        SessionMedia(
            assetId = value.assetId.asUuid("session.media.asset_id"),
            mediaType = value.mediaType,
            status = value.status,
            mime = value.mime,
            size = value.size,
            confirmedAt = value.confirmedAt?.asInstant("session.media.confirmed_at"),
        )
    },
    analysisTaskIds = analysisTaskIds.map { it.asUuid("session.analysis_task_ids") },
    analysisResults = analysisResults.map { it.toDomain() },
)

fun SessionSummaryDto.toDomain(): SingingSessionSummary = SingingSessionSummary(
    id = id.asUuid("session_summary.id"),
    patient = patient.toDomain(),
    song = song.toDomain(),
    treatmentPlan = treatmentPlan?.toDomain(),
    status = status,
    score = score,
    burpCount = burpCount,
    durationSeconds = durationSeconds,
    isMock = isMock,
    createdSource = createdSource,
    analysisGeneration = analysisGeneration,
    submittedAt = submittedAt?.asInstant("session_summary.submitted_at"),
    completedAt = completedAt?.asInstant("session_summary.completed_at"),
    createdAt = createdAt.asInstant("session_summary.created_at"),
    updatedAt = updatedAt.asInstant("session_summary.updated_at"),
)

fun SessionPageDto.toDomain(): SingingSessionPage {
    if (count < 0 || page <= 0 || pageSize <= 0) {
        throw NetworkContractException("session_page 分页边界无效")
    }
    return SingingSessionPage(
        count = count,
        page = page,
        pageSize = pageSize,
        results = results.map { it.toDomain() },
    )
}

fun SessionMutationDto.toDomain(): SessionMutation = SessionMutation(
    sessionId = sessionId.asUuid("session_mutation.session_id"),
    status = status,
    analysisTaskIds = analysisTaskIds.map { it.asUuid("session_mutation.analysis_task_ids") },
)

fun SessionUploadGrantDto.toDomain(): UploadGrant = UploadGrant(
    sessionId = sessionId.asUuid("grant.session_id"),
    assetId = assetId.asUuid("grant.asset_id"),
    objectKey = objectKey.requireNotBlank("grant.object_key"),
    expiresAt = expiresAt.asInstant("grant.expires_at"),
    uploadUrl = uploadUrl.requireNotBlank("grant.upload_url"),
    uploadToken = uploadToken,
    fields = fields,
)

fun PatientMediaUploadGrantDto.toDomain(): UploadGrant = UploadGrant(
    sessionId = null,
    assetId = assetId.asUuid("grant.asset_id"),
    objectKey = objectKey.requireNotBlank("grant.object_key"),
    expiresAt = expiresAt.asInstant("grant.expires_at"),
    uploadUrl = uploadUrl.requireNotBlank("grant.upload_url"),
    uploadToken = uploadToken,
    fields = fields,
)

fun PrivateUrlDto.toDomain(): PrivateUrl = PrivateUrl(
    url = url.requireNotBlank("private_url.url"),
    expiresAt = expiresAt.asInstant("private_url.expires_at"),
)

private fun PatientSnapshotDto.toDomain() = PatientSnapshot(
    id = id.asUuid("session.patient.id"),
    medicalRecordNo = medicalRecordNo.requireNotBlank("session.patient.medical_record_no"),
    name = name.requireNotBlank("session.patient.name"),
)

private fun SongSnapshotDto.toDomain() = SongSnapshot(
    id = id.asUuid("session.song.id"),
    title = title.requireNotBlank("session.song.title"),
    artist = artist.requireNotBlank("session.song.artist"),
    durationSeconds = durationSeconds,
)

private fun TreatmentPlanSnapshotDto.toDomain() = TreatmentPlanSnapshot(
    id = id.asUuid("session.treatment_plan.id"),
    startDate = startDate.asLocalDate("session.treatment_plan.start_date"),
    cycleWeeks = cycleWeeks,
    targetSessionCount = targetSessionCount,
)

private fun JsonObject?.toTypedPayload(
    taskType: String,
    protocolVersion: String,
    isMock: Boolean?,
): AnalysisPayload {
    if (this == null) return UnavailableAnalysisPayload
    if (isMock != true) return UnsupportedAnalysisPayload(AnalysisPayloadIssue.NON_MOCK)
    if (AnalysisTaskType.fromSerialized(taskType) == AnalysisTaskType.UNKNOWN) {
        return UnsupportedAnalysisPayload(AnalysisPayloadIssue.UNKNOWN_TASK_TYPE)
    }
    if (protocolVersion != SUPPORTED_ANALYSIS_PROTOCOL) {
        return UnsupportedAnalysisPayload(AnalysisPayloadIssue.UNSUPPORTED_PROTOCOL)
    }
    return try {
        when (AnalysisTaskType.fromSerialized(taskType)) {
            AnalysisTaskType.SINGING_AUDIO_METRICS -> toSingingAudioPayload()
            AnalysisTaskType.FACE_LANDMARKS -> toFaceLandmarksPayload()
            else -> UnsupportedAnalysisPayload(AnalysisPayloadIssue.UNRECOGNIZED_STRUCTURE)
        }
    } catch (_: RuntimeException) {
        UnsupportedAnalysisPayload(AnalysisPayloadIssue.UNRECOGNIZED_STRUCTURE)
    }
}

private fun JsonObject.toSingingAudioPayload(): SingingAudioAnalysisPayload {
    requireKeys("protocol_version", "is_mock", "score", "burp_events", "sample_interval_ms", "series")
    val protocolVersion = string("protocol_version")
    val isMock = boolean("is_mock")
    val score = integer("score")
    val burpEvents = array("burp_events").map { element ->
        element.jsonPrimitive.intOrNull
            ?: throw NetworkContractException("analysis.payload.burp_events 必须为整数数组")
    }
    val interval = integer("sample_interval_ms")
    val seriesObject = getValue("series").jsonObject
    val requiredMetrics = setOf("volume", "pitch_hz", "snr_db")
    if (seriesObject.keys != requiredMetrics) {
        throw NetworkContractException("analysis.payload.series 指标集合无效")
    }
    val series = seriesObject.mapValues { (metric, element) ->
        val values = element.jsonArray.map { value ->
            value.jsonPrimitive.doubleOrNull?.takeIf(Double::isFinite)
                ?: throw NetworkContractException("analysis.payload.series.$metric 含无效数值")
        }
        if (values.isEmpty() || values.size > 600) {
            throw NetworkContractException("analysis.payload.series.$metric 长度无效")
        }
        values
    }
    if (protocolVersion != "1.0" || !isMock || score !in 0..100 || interval <= 0) {
        throw NetworkContractException("analysis.payload 演唱音频结果边界无效")
    }
    if (burpEvents.any { it < 0 } || burpEvents != burpEvents.distinct().sorted()) {
        throw NetworkContractException("analysis.payload.burp_events 必须非负、唯一且有序")
    }
    if (series.values.map(List<Double>::size).distinct().size != 1) {
        throw NetworkContractException("analysis.payload.series 长度不一致")
    }
    return SingingAudioAnalysisPayload(protocolVersion, isMock, score, burpEvents, interval, series)
}

private fun JsonObject.toFaceLandmarksPayload(): FaceLandmarksAnalysisPayload {
    requireKeys("protocol_version", "is_mock", "landmarks")
    val protocolVersion = string("protocol_version")
    val isMock = boolean("is_mock")
    if (protocolVersion != "1.0" || !isMock || array("landmarks").isNotEmpty()) {
        throw NetworkContractException("analysis.payload 面部模拟结果无效")
    }
    return FaceLandmarksAnalysisPayload(protocolVersion, isMock)
}

private fun JsonObject.requireKeys(vararg expected: String) {
    if (keys != expected.toSet()) {
        throw NetworkContractException("analysis.payload 字段集合无效")
    }
}

private fun JsonObject.string(name: String): String =
    getValue(name).jsonPrimitive.content

private fun JsonObject.boolean(name: String): Boolean =
    getValue(name).jsonPrimitive.booleanOrNull
        ?: throw NetworkContractException("analysis.payload.$name 必须为布尔值")

private fun JsonObject.integer(name: String): Int =
    getValue(name).jsonPrimitive.intOrNull
        ?: throw NetworkContractException("analysis.payload.$name 必须为整数")

private fun JsonObject.array(name: String): JsonArray = try {
    getValue(name).jsonArray
} catch (error: IllegalArgumentException) {
    throw NetworkContractException("analysis.payload.$name 必须为数组", error)
}

private const val SUPPORTED_ANALYSIS_PROTOCOL = "1.0"
