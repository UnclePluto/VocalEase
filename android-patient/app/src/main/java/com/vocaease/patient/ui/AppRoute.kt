package com.vocaease.patient.ui

import kotlinx.serialization.Serializable

sealed interface AppRoute {
    @Serializable
    data object Login : AppRoute

    @Serializable
    data object ChangePassword : AppRoute

    @Serializable
    data object Catalog : AppRoute

    @Serializable
    data object Profile : AppRoute

    @Serializable
    data class Preparation(val songId: String) : AppRoute

    @Serializable
    data class Recording(val draftId: String) : AppRoute

    @Serializable
    data class Review(val draftId: String) : AppRoute

    @Serializable
    data object PendingUploads : AppRoute

    @Serializable
    data object History : AppRoute

    @Serializable
    data class Result(val sessionId: String) : AppRoute

    @Serializable
    data object Settings : AppRoute
}
