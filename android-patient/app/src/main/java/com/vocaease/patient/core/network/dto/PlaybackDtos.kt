package com.vocaease.patient.core.network.dto

import com.vocaease.patient.core.media.PlaybackMetadata
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlaybackBindingDto(
    @SerialName("source_asset_id") val sourceAssetId: String? = null,
    @SerialName("accompaniment_asset_id") val accompanimentAssetId: String? = null,
    @SerialName("reference_version") val referenceVersion: String? = null,
    @SerialName("combined_available") val combinedAvailable: Boolean = false,
    val metadata: PlaybackMetadata? = null,
    @SerialName("accompaniment_preview_available") val accompanimentPreviewAvailable: Boolean = false,
    @SerialName("accompaniment_preview_asset_id") val accompanimentPreviewAssetId: String? = null,
    @SerialName("alignment_verified") val alignmentVerified:Boolean=false,
    @SerialName("accompaniment_offset_ms") val accompanimentOffsetMs:Long?=null,
)

@Serializable
data class SongPlaybackGrantDto(
    @SerialName("asset_id") val assetId: String,
    val url: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class ReferenceNoteDto(@SerialName("start_ms") val startMs: Long, @SerialName("end_ms") val endMs: Long, @SerialName("midi_note") val midiNote: Float, val confidence: Float)

@Serializable
data class ReferencePitchDto(
    val status: String,
    val version: String?,
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val notes: List<ReferenceNoteDto> = emptyList(),
    val origin: kotlinx.serialization.json.JsonObject? = null,
)


@Serializable
data class SubmitSessionRequestDto(@SerialName("playback_metadata") val playbackMetadata: PlaybackMetadata? = null)
