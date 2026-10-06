package com.vocaease.patient.core.media

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SongPlaybackMode(val wire: String, val label: String) {
    @SerialName("source") ORIGINAL("source", "原唱"),
    @SerialName("accompaniment") ACCOMPANIMENT("accompaniment", "伴奏"),
}
