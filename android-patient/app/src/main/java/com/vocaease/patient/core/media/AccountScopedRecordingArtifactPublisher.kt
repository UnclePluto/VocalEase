package com.vocaease.patient.core.media

import com.vocaease.patient.core.database.AccountScopedDraftStorage
import java.io.File

class AccountScopedRecordingArtifactPublisher(
    private val storage: AccountScopedDraftStorage,
    private val extractor: Mp4AudioTrackExtractor = Mp4AudioTrackExtractor(),
) : RecordingArtifactPublisher {
    override suspend fun publish(
        draftId: String,
        video: File,
        audio: File,
        durationMillis: Long,
        publicationActive: () -> Boolean,
        interruption: com.vocaease.patient.feature.training.RecordingInterruption?,
    ) {
        if (!publicationActive()) throw RecordingPublicationCancelledException()
        val extracted = extractor.extract(
            video,
            audio,
            expectedDurationMillis = durationMillis.takeIf { interruption == null },
        )
        if (
            extracted.videoContainerMimeType != "video/mp4" ||
            extracted.audioContainerMimeType != "audio/mp4" ||
            extracted.videoCodecMimeType != "video/avc" ||
            extracted.audioCodecMimeType != "audio/mp4a-latm"
        ) {
            throw MediaValidationException()
        }
        if (!publicationActive()) throw RecordingPublicationCancelledException()
        val persistedDurationMillis = if (interruption == null) {
            durationMillis
        } else {
            (extracted.sourceDurationUs / 1_000L).coerceAtLeast(1L)
        }
        if (interruption == null) {
            storage.publishRecordingMedia(draftId, video, audio, persistedDurationMillis, publicationActive)
        } else {
            storage.publishInterruptedRecordingMedia(
                draftId,
                video,
                audio,
                persistedDurationMillis,
                interruption.storageReason(),
                publicationActive,
            )
        }
        if (!publicationActive()) {
            storage.markRecordingInterrupted(draftId, persistedDurationMillis, "录制已取消")
            throw RecordingPublicationCancelledException()
        }
    }
}

private fun com.vocaease.patient.feature.training.RecordingInterruption.storageReason(): String = when (this) {
    com.vocaease.patient.feature.training.RecordingInterruption.CAMERA -> "相机录制中断"
    com.vocaease.patient.feature.training.RecordingInterruption.AUDIO -> "麦克风录制中断"
    com.vocaease.patient.feature.training.RecordingInterruption.FINALIZE -> "录制封装中断"
    else -> "录制中断"
}
