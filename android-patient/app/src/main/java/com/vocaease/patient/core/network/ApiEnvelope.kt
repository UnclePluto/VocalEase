package com.vocaease.patient.core.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ApiEnvelope<T>(
    val code: String,
    val message: String,
    val data: T,
    @SerialName("request_id") val requestId: String,
)

@Serializable
data class EmptyDataDto(
    private val unused: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
val apiJson: Json = Json {
    ignoreUnknownKeys = false
    explicitNulls = true
    exceptionsWithDebugInfo = false
}
