package com.vocaease.patient

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineDispatcher

fun interface AppClock {
    fun nowEpochMilliseconds(): Long
}

interface AppDispatchers {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
}

fun interface RepositoryFactory {
    fun create(name: String): Any
}

fun interface MediaFactory {
    fun create(): Any
}

fun interface UploadFactory {
    fun create(): Any
}

interface AppContainer {
    val clock: AppClock
    val dispatchers: AppDispatchers
    val repositoryFactory: RepositoryFactory
    val mediaFactory: MediaFactory
    val uploadFactory: UploadFactory

    companion object {
        fun unavailable(): AppContainer = UnavailableAppContainer
    }
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("尚未提供 AppContainer")
}

private object UnavailableAppContainer : AppContainer {
    private fun unavailable(): Nothing = error("该依赖将在后续任务中提供")

    override val clock: AppClock
        get() = unavailable()
    override val dispatchers: AppDispatchers
        get() = unavailable()
    override val repositoryFactory: RepositoryFactory
        get() = unavailable()
    override val mediaFactory: MediaFactory
        get() = unavailable()
    override val uploadFactory: UploadFactory
        get() = unavailable()
}
