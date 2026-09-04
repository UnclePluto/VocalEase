package com.vocaease.patient

import android.content.Context
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import androidx.media3.ui.PlayerView
import androidx.compose.runtime.staticCompositionLocalOf
import com.vocaease.patient.core.database.AccountScopedDraftStorageProvider
import com.vocaease.patient.core.database.AccountScopedDraftStorage
import com.vocaease.patient.core.database.AuthenticatedAccountLease
import com.vocaease.patient.core.database.AuthenticatedAccountSession
import com.vocaease.patient.core.database.AccountLeaseListenerRegistration
import com.vocaease.patient.core.database.VocaEaseDatabase
import com.vocaease.patient.core.cleanup.DailyDraftCleanupScheduler
import com.vocaease.patient.core.media.ExoPreviewEngine
import com.vocaease.patient.core.media.LinearizedReviewPlayer
import com.vocaease.patient.core.media.Media3ReviewPlayerEngine
import com.vocaease.patient.core.media.PreviewPlayer
import com.vocaease.patient.core.media.PreviewSession
import com.vocaease.patient.core.media.RecordingPlaybackHandoff
import com.vocaease.patient.core.media.PrivateRecordingTempFiles
import com.vocaease.patient.core.media.RecordingStagingRecovery
import com.vocaease.patient.core.media.CameraXRecordingCapture
import com.vocaease.patient.core.media.RecordingCapture
import com.vocaease.patient.core.network.PatientApi
import com.vocaease.patient.core.network.NetworkModule
import com.vocaease.patient.core.network.OkHttpRevocationRemote
import com.vocaease.patient.core.network.SessionLifecycleEvent
import com.vocaease.patient.core.security.AndroidTokenVault
import com.vocaease.patient.core.security.AndroidRevocationVault
import com.vocaease.patient.core.security.AndroidRevocationScheduler
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import com.vocaease.patient.core.security.RevocationExecutor
import com.vocaease.patient.core.security.RevocationWorkerGateway
import com.vocaease.patient.feature.auth.AuthRepository
import com.vocaease.patient.feature.catalog.PatientRepository
import com.vocaease.patient.feature.catalog.SongPagingSource
import com.vocaease.patient.feature.catalog.SongRepository
import com.vocaease.patient.feature.catalog.VocaEasePatientRemoteDataSource
import com.vocaease.patient.feature.catalog.VocaEaseSongRemoteDataSource
import com.vocaease.patient.feature.profile.PendingUploadCounter
import com.vocaease.patient.feature.profile.AccountScopedPendingUploadCounter
import com.vocaease.patient.feature.profile.ProductionAccountExitManager
import com.vocaease.patient.feature.profile.ProductionSettingsAccountActions
import com.vocaease.patient.feature.profile.SettingsAccountActions
import com.vocaease.patient.feature.training.LocalUploadQueueSignals
import com.vocaease.patient.feature.training.ReviewPlayer
import com.vocaease.patient.feature.training.VocaEasePreviewGrantSource
import com.vocaease.patient.feature.training.AndroidAppConnectivity
import com.vocaease.patient.feature.training.AppConnectivity
import com.vocaease.patient.feature.upload.UploadCoordinator
import com.vocaease.patient.feature.upload.QiniuUploader
import com.vocaease.patient.feature.upload.QiniuV2Uploader
import com.vocaease.patient.feature.upload.VocaEaseUploadRemote
import com.vocaease.patient.feature.history.AccountScopedAnalysisAccount
import com.vocaease.patient.feature.history.AnalysisSyncCoordinator
import com.vocaease.patient.feature.history.AndroidAnalysisWorkScheduler
import com.vocaease.patient.feature.history.ProductionAnalysisSessionSynchronizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.io.File

fun interface AppClock {
    fun nowEpochMilliseconds(): Long
}

fun interface AppMonotonicClock {
    fun nowNanoseconds(): Long
}

fun interface RecordingCaptureFactory {
    fun create(context: Context, lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider): RecordingCapture
}

fun interface QiniuUploaderFactory {
    fun create(recorderDirectory: File): QiniuUploader
}

/** 所有设备/网络/SDK 外部边界均从容器装配；默认值保持生产真实实现。 */
data class AndroidAppDependencies(
    val apiBaseUrl: String = BuildConfig.API_BASE_URL,
    val authenticatedClientFactory: (com.vocaease.patient.core.security.TokenVault) -> OkHttpClient =
        NetworkModule::createAuthenticatedHttpClient,
    val clock: AppClock = AppClock(System::currentTimeMillis),
    val monotonicClock: AppMonotonicClock = AppMonotonicClock(System::nanoTime),
    val connectivityFactory: (Context) -> AppConnectivity = ::AndroidAppConnectivity,
    val recordingCaptureFactory: RecordingCaptureFactory = RecordingCaptureFactory { context, owner, surface ->
        CameraXRecordingCapture(context, owner, surface)
    },
    val qiniuUploaderFactory: QiniuUploaderFactory = QiniuUploaderFactory(::QiniuV2Uploader),
)

interface AppDispatchers {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
}

fun interface RepositoryFactory {
    fun create(name: String): Any
}

interface MediaFactory {
    fun createPreviewSession(): PreviewSession
    fun createReviewPlayer(storage: AccountScopedDraftStorage, playerView: PlayerView? = null): ReviewPlayer
}

fun interface UploadFactory {
    fun create(): Any
}

interface AppContainer {
    val clock: AppClock
    val monotonicClock: AppMonotonicClock
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
    val settingsAccountActions: SettingsAccountActions
    val connectivity: AppConnectivity
        get() = error("尚未提供网络状态边界")
    val recordingCaptureFactory: RecordingCaptureFactory
        get() = error("尚未提供相机边界")
    val qiniuUploaderFactory: QiniuUploaderFactory
        get() = error("尚未提供七牛上传边界")
    /** 预留给 Task10 上传协调器的单消费者会话失效队列；UI 使用 authRepository.events。 */
    val sessionEvents: Flow<SessionLifecycleEvent>
    val revocationWorkerGateway: RevocationWorkerGateway?
        get() = null

    companion object {
        fun unavailable(): AppContainer = UnavailableAppContainer
    }
}

class AndroidAppContainer(
    context: Context,
    dependencies: AndroidAppDependencies = AndroidAppDependencies(),
) : AppContainer, AutoCloseable {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokenVault = AndroidTokenVault(context)
    private val revocationVault = AndroidRevocationVault(context)
    private val revocationScheduler = AndroidRevocationScheduler(context)
    private val revocationRemote = OkHttpRevocationRemote(
        dependencies.apiBaseUrl,
        NetworkModule.createRevocationHttpClient(),
    )
    private val revocationExecutor = RevocationExecutor(revocationVault, revocationVault, revocationRemote)
    override val revocationWorkerGateway = RevocationWorkerGateway(revocationExecutor::revoke)
    private val sessionGraph = createProductionSessionGraph(
        dependencies.apiBaseUrl,
        tokenVault,
        clientOverride = dependencies.authenticatedClientFactory(tokenVault),
        revocationTokenSink = revocationVault,
        revocationRemote = revocationRemote,
        revocationScheduler = revocationScheduler,
    )

    override val authRepository = sessionGraph.authRepository
    override val patientApi = sessionGraph.patientApi
    private val accountSession = object : AuthenticatedAccountSession {
        override fun current(): AuthenticatedAccountLease? = authRepository.currentAuthenticatedLease()
        override fun addLeaseChangedListener(
            listener: (AuthenticatedAccountLease?) -> Unit,
        ): AccountLeaseListenerRegistration =
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
    override val clock = dependencies.clock
    override val monotonicClock = dependencies.monotonicClock
    override val connectivity = dependencies.connectivityFactory(context.applicationContext)
    override val recordingCaptureFactory = dependencies.recordingCaptureFactory
    override val qiniuUploaderFactory = dependencies.qiniuUploaderFactory
    override val dispatchers = object : AppDispatchers {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }
    private val analysisSessionSynchronizer = ProductionAnalysisSessionSynchronizer(patientApi)
    private val analysisCoordinator = AnalysisSyncCoordinator(
        scopeProvider = { AccountScopedAnalysisAccount(draftStorage.current()) },
        remoteFactory = { scope ->
            analysisSessionSynchronizer.analysisRemote(scope.accountScopeHash, scope.incarnationProof)
        },
        scheduler = AndroidAnalysisWorkScheduler(context),
        nowEpochMillis = clock::nowEpochMilliseconds,
    )
    override val repositoryFactory = RepositoryFactory { name ->
        when (name) {
            "auth" -> authRepository
            "draft-storage" -> draftStorage
            "patient" -> patientRepository
            "songs" -> songRepository
            "analysis-worker" -> analysisCoordinator
            "analysis-session-sync" -> analysisSessionSynchronizer
            else -> error("仓库尚未提供：$name")
        }
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override val mediaFactory = object : MediaFactory {
        override fun createPreviewSession(): PreviewSession = PreviewPlayer(
            ExoPreviewEngine(context.applicationContext),
            VocaEasePreviewGrantSource(patientApi),
        )

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun createReviewPlayer(storage: AccountScopedDraftStorage, playerView: PlayerView?): ReviewPlayer =
            LinearizedReviewPlayer(Media3ReviewPlayerEngine(context.applicationContext, storage, playerView))
    }
    private val uploadCoordinator = UploadCoordinator(
        context = context,
        storageProvider = draftStorage,
        remoteFactory = { scopeHash -> VocaEaseUploadRemote(patientApi, scopeHash) },
        nowEpochMillis = clock::nowEpochMilliseconds,
        uploaderFactory = qiniuUploaderFactory::create,
        onAnalyzing = analysisCoordinator::schedule,
    )
    override val uploadFactory = UploadFactory { uploadCoordinator }
    internal val accountExitManager = ProductionAccountExitManager(
        context = context.applicationContext,
        authRepository = authRepository,
        storageProvider = draftStorage,
        database = patientDatabase,
        fileStore = encryptedFileStore,
        uploadCoordinator = uploadCoordinator,
        analysisCoordinator = analysisCoordinator,
        recordingPlaybackHandoff = recordingPlaybackHandoff,
    )
    override val settingsAccountActions: SettingsAccountActions = ProductionSettingsAccountActions(accountExitManager)

    init {
        authRepository.registerPreparedLogoutRecovery(accountExitManager::recoverAuthenticationFinalization)
        val cleanupScheduler = DailyDraftCleanupScheduler(context)
        val tempFiles = PrivateRecordingTempFiles(context)
        val stagingRecovery = RecordingStagingRecovery(tempFiles)
        var accountRecoveryJob: Job? = null
        var previousUploadScope: String? = null
        accountSession.addLeaseChangedListener {
            recordingPlaybackHandoff.discardAll()
            previousUploadScope?.let(uploadCoordinator::cancelAccount)
            previousUploadScope?.let(analysisCoordinator::cancelAccount)
            accountRecoveryJob?.cancel()
            accountRecoveryJob = applicationScope.launch {
                val storage = runCatching { draftStorage.current() }.getOrNull()
                previousUploadScope = storage?.accountScopeHash
                if (storage == null) {
                    cleanupScheduler.replaceFor(null)
                    return@launch
                }
                val normalWorkSuppressed = AccountStartupRecovery(
                    recoverExit = accountExitManager::recoverCurrentExit,
                    startNormalWork = {
                        cleanupScheduler.replaceFor(storage)
                        stagingRecovery.recover(storage)
                        storage.recoverUploadLocalActions()
                        storage.observeUploadJobs().first().forEach { job ->
                            runCatching { uploadCoordinator.schedule(job.draftId) }
                        }
                    },
                ).run()
                if (normalWorkSuppressed) {
                    cleanupScheduler.replaceFor(null)
                }
            }
        }
        applicationScope.launch {
            runCatching { revocationVault.handles() }.getOrDefault(emptyList())
                .forEach(revocationScheduler::schedule)
        }
        applicationScope.launch {
            LocalUploadQueueSignals.signals.collect { draftId ->
                runCatching { uploadCoordinator.schedule(draftId) }
            }
        }
        tempFiles.cleanupOrphans()
    }

    override fun close() {
        applicationScope.cancel()
        patientDatabase.close()
    }
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("尚未提供 AppContainer")
}

private object UnavailableAppContainer : AppContainer {
    private fun unavailable(): Nothing = error("该依赖将在后续任务中提供")

    override val clock: AppClock
        get() = unavailable()
    override val monotonicClock: AppMonotonicClock
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
    override val settingsAccountActions: SettingsAccountActions
        get() = unavailable()
    override val connectivity: AppConnectivity
        get() = unavailable()
    override val recordingCaptureFactory: RecordingCaptureFactory
        get() = unavailable()
    override val qiniuUploaderFactory: QiniuUploaderFactory
        get() = unavailable()
    override val sessionEvents: Flow<SessionLifecycleEvent>
        get() = unavailable()
}
