package com.vocaease.patient.core.network.dto
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class LyricLineDto(@SerialName("time_ms") val timeMs: Long, val text: String)

@Serializable
data class SongLyricsDto(val lines: List<LyricLineDto>)
