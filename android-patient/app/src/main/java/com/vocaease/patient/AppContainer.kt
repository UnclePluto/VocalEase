package com.vocaease.patient

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.vocaease.patient.BuildConfig
import com.vocaease.patient.core.database.VocaEaseDatabase
import com.vocaease.patient.core.network.PatientApi
import com.vocaease.patient.core.network.SessionLifecycleEvent
import com.vocaease.patient.core.security.AndroidTokenVault
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import com.vocaease.patient.feature.auth.AuthRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

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
    val patientApi: PatientApi
    val patientDatabase: VocaEaseDatabase
    val encryptedFileStore: ChunkedAesGcmFileStore
    /** 预留给 Task10 上传协调器的单消费者会话失效队列；UI 使用 authRepository.events。 */
    val sessionEvents: Flow<SessionLifecycleEvent>

    companion object {
        fun unavailable(): AppContainer = UnavailableAppContainer
    }
}

class AndroidAppContainer(context: Context) : AppContainer {
    private val tokenVault = AndroidTokenVault(context)
    private val sessionGraph = createProductionSessionGraph(BuildConfig.API_BASE_URL, tokenVault)

    override val authRepository = sessionGraph.authRepository
    override val patientApi = sessionGraph.patientApi
    override val sessionEvents = sessionGraph.sessionEvents
    override val patientDatabase = VocaEaseDatabase.create(context)
    override val encryptedFileStore = ChunkedAesGcmFileStore(context)
    override val clock = AppClock(System::currentTimeMillis)
    override val dispatchers = object : AppDispatchers {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }
    override val repositoryFactory = RepositoryFactory { name ->
        when (name) {
            "auth" -> authRepository
            "draft-storage" -> patientDatabase
            else -> error("仓库尚未提供：$name")
        }
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
    override val patientApi: PatientApi
        get() = unavailable()
    override val patientDatabase: VocaEaseDatabase
        get() = unavailable()
    override val encryptedFileStore: ChunkedAesGcmFileStore
        get() = unavailable()
    override val sessionEvents: Flow<SessionLifecycleEvent>
        get() = unavailable()
}
