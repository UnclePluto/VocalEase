package com.vocaease.patient.feature.history

import com.vocaease.patient.core.network.dto.AnalysisTaskStatus
import com.vocaease.patient.core.network.dto.AnalysisTaskType
import com.vocaease.patient.core.network.dto.SessionStatus
import com.vocaease.patient.core.network.dto.SingingAnalysisResult
import com.vocaease.patient.core.network.dto.SingingSession
import kotlin.math.roundToInt

enum class ResultContentState {
    PROCESSING,
    FAILED,
    COMPLETED,
    RESULT_SYNCING,
    CANCELLED,
    UNKNOWN,
}

data class PitchPoint(
    val timeMillis: Long,
    val pitchHz: Float,
)

data class ResultUiModel(
    val sessionId: String,
    val songTitle: String,
    val artist: String,
    val durationSeconds: Int?,
    val contentState: ResultContentState,
    val overallScore: String?,
    val mockLabel: String?,
    val componentScores: List<String>,
    val pitchPoints: List<PitchPoint>,
    val pitchMessage: String?,
    val safeFailureSummary: String?,
    val canRetry: Boolean,
    val analysisGeneration: Int,
)

object ResultMapper {
    const val MAX_PITCH_SAMPLES: Int = 10_000
    private const val NO_COMPONENT_SCORE = "暂无单项评分"
    private const val NO_PITCH = "暂无音准曲线"

    fun mockLabel(isMock: Boolean?): String? = if (isMock == true) "演示结果" else null

    fun overallScore(raw: Double?): String? = raw
        ?.takeIf { it.isFinite() && it in 0.0..100.0 }
        ?.roundToInt()
        ?.toString()

    fun map(session: SingingSession): ResultUiModel {
        val metricResult = session.analysisResults
            .asSequence()
            .filter { it.taskType == AnalysisTaskType.SINGING_AUDIO_METRICS }
            .maxWithOrNull(compareBy<SingingAnalysisResult> { it.generation }.thenBy { it.id })
        val state = when (session.status) {
            SessionStatus.UPLOADED, SessionStatus.PROCESSING -> ResultContentState.PROCESSING
            SessionStatus.FAILED -> ResultContentState.FAILED
            SessionStatus.CANCELLED -> ResultContentState.CANCELLED
            SessionStatus.COMPLETED -> if (
                metricResult?.status == AnalysisTaskStatus.SUCCEEDED
            ) ResultContentState.COMPLETED else ResultContentState.RESULT_SYNCING
            else -> ResultContentState.UNKNOWN
        }
        val pitch = if (state == ResultContentState.COMPLETED) metricResult.validPitch() else emptyList()
        return ResultUiModel(
            sessionId = session.id.toString(),
            songTitle = session.song.title,
            artist = session.song.artist,
            durationSeconds = session.durationSeconds?.takeIf { it >= 0 },
            contentState = state,
            overallScore = if (state == ResultContentState.COMPLETED) overallScore(session.score?.toDouble()) else null,
            mockLabel = if (state == ResultContentState.COMPLETED) mockLabel(metricResult?.isMock) else null,
            componentScores = List(3) { NO_COMPONENT_SCORE },
            pitchPoints = pitch,
            pitchMessage = if (state == ResultContentState.COMPLETED && pitch.isEmpty()) NO_PITCH else null,
            safeFailureSummary = if (state == ResultContentState.FAILED) safeSummary(metricResult?.errorSummary) else null,
            canRetry = state == ResultContentState.FAILED,
            analysisGeneration = session.analysisGeneration.coerceAtLeast(0),
        )
    }

    private fun SingingAnalysisResult?.validPitch(): List<PitchPoint> {
        val series = this?.timeSeries?.get("pitch_hz") ?: return emptyList()
        if (series.sampleIntervalMs <= 0 || series.values.isEmpty() || series.values.size > MAX_PITCH_SAMPLES) {
            return emptyList()
        }
        if (series.values.any { !it.isFinite() || it < 0.0 || it > Float.MAX_VALUE }) return emptyList()
        return series.values.mapIndexed { index, value ->
            val time = index.toLong() * series.sampleIntervalMs.toLong()
            if (time < 0) return emptyList()
            PitchPoint(time, value.toFloat())
        }
    }

    private fun safeSummary(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isBlank() || value.length > 120 || value.contains("http://") || value.contains("https://")) {
            return "分析暂时失败，请稍后重试"
        }
        return value
    }
}
