package com.vocaease.patient

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.vocaease.patient.core.network.NetworkModule
import com.vocaease.patient.core.network.RefreshCoordinator
import com.vocaease.patient.core.security.AndroidTokenVault
import com.vocaease.patient.feature.auth.AuthRepository
import com.vocaease.patient.feature.auth.VocaEaseAuthRemoteDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

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
    val authRepository: AuthRepository
    val refreshCoordinator: RefreshCoordinator

    companion object {
        fun unavailable(): AppContainer = UnavailableAppContainer
    }
}

class AndroidAppContainer(context: Context) : AppContainer {
    private val tokenVault = AndroidTokenVault(context)
    private val httpClient = NetworkModule.createAuthenticatedHttpClient(tokenVault)
    private val api = NetworkModule.createApi(client = httpClient)
    private val authRemote = VocaEaseAuthRemoteDataSource(api)
    override val refreshCoordinator = RefreshCoordinator(tokenVault, authRemote)

    override val authRepository = AuthRepository(tokenVault, authRemote, refreshCoordinator)
    override val clock = AppClock(System::currentTimeMillis)
    override val dispatchers = object : AppDispatchers {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }
    override val repositoryFactory = RepositoryFactory { name ->
        if (name == "auth") authRepository else error("仓库尚未提供：$name")
    }
    override val mediaFactory = MediaFactory { error("媒体能力将在后续任务中提供") }
    override val uploadFactory = UploadFactory { error("上传能力将在后续任务中提供") }
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
    override val authRepository: AuthRepository
        get() = unavailable()
    override val refreshCoordinator: RefreshCoordinator
        get() = unavailable()
}
