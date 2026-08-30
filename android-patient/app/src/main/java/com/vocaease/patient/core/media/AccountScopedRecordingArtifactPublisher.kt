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
    ) {
        if (!publicationActive()) throw RecordingPublicationCancelledException()
        val extracted = extractor.extract(video, audio, durationMillis)
        if (
            extracted.videoContainerMimeType != "video/mp4" ||
            extracted.audioContainerMimeType != "audio/mp4" ||
            extracted.videoCodecMimeType != "video/avc" ||
            extracted.audioCodecMimeType != "audio/mp4a-latm"
        ) {
            throw MediaValidationException()
        }
        if (!publicationActive()) throw RecordingPublicationCancelledException()
        storage.publishRecordingMedia(draftId, video, audio, durationMillis, publicationActive)
        if (!publicationActive()) {
            storage.markRecordingInterrupted(draftId, durationMillis, "录制已取消")
            throw RecordingPublicationCancelledException()
        }
    }
}
