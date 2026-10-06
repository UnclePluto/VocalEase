package com.vocaease.patient.core.media

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlaybackAnchor(
    @SerialName("recording_ms") val recordingMs: Long,
    @SerialName("song_ms") val songMs: Long,
    val track: SongPlaybackMode,
    val playing: Boolean,
    val segment: Int,
)

@Serializable
data class ModeChange(@SerialName("recording_ms") val recordingMs: Long, val track: SongPlaybackMode)

@Serializable
data class PlaybackMetadata(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("sample_rate") val sampleRate: Int,
    @SerialName("source_asset_id") val sourceAssetId: String,
    @SerialName("accompaniment_asset_id") val accompanimentAssetId: String,
    @SerialName("reference_version") val referenceVersion: String?,
    val anchors: List<PlaybackAnchor>,
    @SerialName("mode_changes") val modeChanges: List<ModeChange>,
) {
    init {
        require(schemaVersion == 1 && sampleRate in setOf(44100, 48000))
        java.util.UUID.fromString(sourceAssetId); java.util.UUID.fromString(accompanimentAssetId)
        referenceVersion?.let(java.util.UUID::fromString)
        require(anchors.size in 1..10000 && modeChanges.size <= 1000)
        require(anchors.all { it.recordingMs >= 0 && it.songMs >= 0 && it.segment >= 0 })
        require(anchors.zipWithNext().all { (a, b) -> b.recordingMs > a.recordingMs && b.segment >= a.segment && (a.segment != b.segment || b.songMs >= a.songMs) })
    }
}
