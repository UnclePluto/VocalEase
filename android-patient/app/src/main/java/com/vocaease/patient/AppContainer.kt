package com.vocaease.patient

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.vocaease.patient.BuildConfig
import com.vocaease.patient.core.database.AccountScopedDraftStorageProvider
import com.vocaease.patient.core.database.AuthenticatedAccountLease
import com.vocaease.patient.core.database.AuthenticatedAccountSession
import com.vocaease.patient.core.database.VocaEaseDatabase
import com.vocaease.patient.core.cleanup.DailyDraftCleanupScheduler
import com.vocaease.patient.core.media.ExoPreviewEngine
import com.vocaease.patient.core.media.PreviewEngine
import com.vocaease.patient.core.media.RecordingPlaybackHandoff
import com.vocaease.patient.core.media.PrivateRecordingTempFiles
import com.vocaease.patient.core.media.RecordingStagingRecovery
import com.vocaease.patient.core.network.PatientApi
import com.vocaease.patient.core.network.SessionLifecycleEvent
import com.vocaease.patient.core.security.AndroidTokenVault
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import com.vocaease.patient.feature.auth.AuthRepository
import com.vocaease.patient.feature.catalog.PatientRepository
import com.vocaease.patient.feature.catalog.SongPagingSource
import com.vocaease.patient.feature.catalog.SongRepository
import com.vocaease.patient.feature.catalog.VocaEasePatientRemoteDataSource
import com.vocaease.patient.feature.catalog.VocaEaseSongRemoteDataSource
import com.vocaease.patient.feature.profile.PendingUploadCounter
import com.vocaease.patient.feature.profile.AccountScopedPendingUploadCounter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
    fun createPreviewEngine(): PreviewEngine
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
    val patientRepository: PatientRepository
    val songRepository: SongRepository
    val pendingUploadCounter: PendingUploadCounter
    val draftStorage: AccountScopedDraftStorageProvider
    val recordingPlaybackHandoff: RecordingPlaybackHandoff
    /** 预留给 Task10 上传协调器的单消费者会话失效队列；UI 使用 authRepository.events。 */
    val sessionEvents: Flow<SessionLifecycleEvent>

    companion object {
        fun unavailable(): AppContainer = UnavailableAppContainer
    }
}

class AndroidAppContainer(context: Context) : AppContainer {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokenVault = AndroidTokenVault(context)
    private val sessionGraph = createProductionSessionGraph(BuildConfig.API_BASE_URL, tokenVault)

    override val authRepository = sessionGraph.authRepository
    override val patientApi = sessionGraph.patientApi
    private val accountSession = object : AuthenticatedAccountSession {
        override fun current(): AuthenticatedAccountLease? = authRepository.currentAuthenticatedLease()
        override fun addLeaseChangedListener(listener: (AuthenticatedAccountLease?) -> Unit) =
            authRepository.addAuthenticatedLeaseChangedListener(listener)
        override suspend fun <T> withCurrentLease(
            expected: AuthenticatedAccountLease,
            operation: suspend () -> T,
        ): T = authRepository.withAuthenticatedLease(expected, operation)
    }
    override val patientRepository = PatientRepository(VocaEasePatientRemoteDataSource(patientApi), accountSession)
    override val songRepository = SongRepository(accountSession) { keyword ->
        SongPagingSource(VocaEaseSongRemoteDataSource(patientApi), keyword)
    }
    override val sessionEvents = sessionGraph.sessionEvents
    private val patientDatabase = VocaEaseDatabase.create(context)
    private val encryptedFileStore = ChunkedAesGcmFileStore(context)
    override val draftStorage = AccountScopedDraftStorageProvider(
        patientDatabase,
        encryptedFileStore,
        accountSession,
    )
    override val recordingPlaybackHandoff = RecordingPlaybackHandoff()
    override val pendingUploadCounter = AccountScopedPendingUploadCounter(draftStorage)
    override val clock = AppClock(System::currentTimeMillis)
    override val dispatchers = object : AppDispatchers {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }
    override val repositoryFactory = RepositoryFactory { name ->
        when (name) {
            "auth" -> authRepository
            "draft-storage" -> draftStorage
            "patient" -> patientRepository
            "songs" -> songRepository
            else -> error("仓库尚未提供：$name")
        }
    }
    override val mediaFactory = MediaFactory { ExoPreviewEngine(context.applicationContext) }
    override val uploadFactory = UploadFactory { error("上传能力将在后续任务中提供") }

    init {
        val cleanupScheduler = DailyDraftCleanupScheduler(context)
        val tempFiles = PrivateRecordingTempFiles(context)
        val stagingRecovery = RecordingStagingRecovery(tempFiles)
        var recoveryJob: Job? = null
        accountSession.addLeaseChangedListener {
            recordingPlaybackHandoff.discardAll()
            val storage = runCatching { draftStorage.current() }.getOrNull()
            cleanupScheduler.replaceFor(storage)
            recoveryJob?.cancel()
            recoveryJob = storage?.let { current ->
                applicationScope.launch { stagingRecovery.recover(current) }
            }
        }
        tempFiles.cleanupOrphans()
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
    override val authRepository: AuthRepository
        get() = unavailable()
    override val patientApi: PatientApi
        get() = unavailable()
    override val patientRepository: PatientRepository
        get() = unavailable()
    override val songRepository: SongRepository
        get() = unavailable()
    override val pendingUploadCounter: PendingUploadCounter
        get() = unavailable()
    override val draftStorage: AccountScopedDraftStorageProvider
        get() = unavailable()
    override val recordingPlaybackHandoff: RecordingPlaybackHandoff
        get() = unavailable()
    override val sessionEvents: Flow<SessionLifecycleEvent>
        get() = unavailable()
}
