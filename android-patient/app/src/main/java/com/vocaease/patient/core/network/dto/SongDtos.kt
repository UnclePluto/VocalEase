package com.vocaease.patient.core.network.dto

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AnalysisStatus {
    @SerialName("pending") PENDING,
    @SerialName("processing") PROCESSING,
    @SerialName("succeeded") SUCCEEDED,
    @SerialName("failed") FAILED,
    @SerialName("retrying") RETRYING,
}

@Serializable
enum class PublicationStatus {
    @SerialName("draft") DRAFT,
    @SerialName("published") PUBLISHED,
}

@Serializable
data class SongDto(
    val id: String,
    val title: String,
    val artist: String,
    val genre: String,
    val language: String,
    @SerialName("duration_seconds") val durationSeconds: Int,
    @SerialName("analysis_status") val analysisStatus: AnalysisStatus? = null,
    @SerialName("publication_status") val publicationStatus: PublicationStatus? = null,
    @SerialName("uploaded_at") val uploadedAt: String,
)

@Serializable
data class SongPageDto(
    val count: Int,
    val page: Int,
    @SerialName("page_size") val pageSize: Int,
    val results: List<SongDto>,
)

data class Song(
    val id: UUID,
    val title: String,
    val artist: String,
    val genre: String,
    val language: String,
    val durationSeconds: Int,
    val analysisStatus: AnalysisStatus?,
    val publicationStatus: PublicationStatus?,
    val uploadedAt: Instant,
)

fun SongDto.toDomain(): Song = Song(
    id = id.asUuid("song.id"),
    title = title.requireNotBlank("song.title"),
    artist = artist.requireNotBlank("song.artist"),
    genre = genre,
    language = language,
    durationSeconds = durationSeconds,
    analysisStatus = analysisStatus,
    publicationStatus = publicationStatus,
    uploadedAt = uploadedAt.asInstant("song.uploaded_at"),
)
