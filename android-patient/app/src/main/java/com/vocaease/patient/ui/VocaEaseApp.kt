package com.vocaease.patient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.camera.view.PreviewView
import androidx.media3.ui.PlayerView
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vocaease.patient.AppContainer
import com.vocaease.patient.LocalAppContainer
import com.vocaease.patient.feature.auth.AuthFlow
import com.vocaease.patient.feature.catalog.CatalogScreen
import com.vocaease.patient.feature.catalog.CatalogViewModel
import com.vocaease.patient.feature.profile.ProfileScreen
import com.vocaease.patient.feature.profile.ProfileViewModel
import com.vocaease.patient.feature.profile.SettingsScreen
import com.vocaease.patient.feature.profile.SettingsViewModel
import com.vocaease.patient.core.media.AccountScopedRecordingArtifactPublisher
import com.vocaease.patient.core.media.AndroidRecordingAudioFocus
import com.vocaease.patient.core.media.DefaultRecordingCoordinator
import com.vocaease.patient.core.media.PrivateRecordingTempFiles
import com.vocaease.patient.core.media.RecordingPlayback
import com.vocaease.patient.core.media.Media3PrivateVideoEngine
import com.vocaease.patient.core.media.RecordingEnvironmentInterruptionCoordinator
import com.vocaease.patient.feature.training.AccountScopedPreparationDraftStoreProvider
import com.vocaease.patient.feature.training.AndroidReadinessSource
import com.vocaease.patient.feature.training.PreparationScreen
import com.vocaease.patient.feature.training.PreparationViewModel
import com.vocaease.patient.feature.training.SavedStatePreparationState
import com.vocaease.patient.feature.training.VocaEasePreparationSongSource
import com.vocaease.patient.feature.training.VocaEaseTrainingSessionCreator
import com.vocaease.patient.feature.training.AccountScopedRecordingDraftGateway
import com.vocaease.patient.feature.training.RecordingScreen
import com.vocaease.patient.feature.training.RecordingViewModel
import com.vocaease.patient.feature.training.RecordingSystemChrome
import com.vocaease.patient.feature.training.AccountScopedLocalReviewStore
import com.vocaease.patient.feature.training.DraftRepository
import com.vocaease.patient.feature.training.LocalUploadQueueSignals
import com.vocaease.patient.feature.training.ReviewScreen
import com.vocaease.patient.feature.training.ReviewViewModel
import com.vocaease.patient.feature.upload.PendingUploadsScreen
import com.vocaease.patient.feature.upload.PendingUploadsViewModel
import com.vocaease.patient.feature.upload.UploadCoordinator
import com.vocaease.patient.feature.upload.UploadNotificationPermission
import com.vocaease.patient.feature.history.AccountScopedHistoryLocalSource
import com.vocaease.patient.feature.history.AnalysisRetryCoordinator
import com.vocaease.patient.feature.history.AnalysisRetryOutcome
import com.vocaease.patient.feature.history.AnalysisSyncCoordinator
import com.vocaease.patient.feature.history.HistoryRepository
import com.vocaease.patient.feature.history.HistoryScreen
import com.vocaease.patient.feature.history.HistoryViewModel
import com.vocaease.patient.feature.history.PrivateSessionVideoPlayer
import com.vocaease.patient.feature.history.ProductionAnalysisSessionSynchronizer
import com.vocaease.patient.feature.history.PrivateVideoRouteLifecycle
import com.vocaease.patient.feature.history.PrivateVideoState
import com.vocaease.patient.feature.history.ResultContentState
import com.vocaease.patient.feature.history.ResultScreen
import com.vocaease.patient.feature.history.ResultViewModel
import com.vocaease.patient.feature.history.VocaEaseAnalysisRetryRemote
import com.vocaease.patient.feature.history.VocaEaseHistoryRemoteSource
import com.vocaease.patient.feature.history.VocaEasePrivateVideoRemote
import com.vocaease.patient.ui.theme.AppBackground
import com.vocaease.patient.ui.theme.AppWhite
import com.vocaease.patient.ui.theme.BrandGreen
import com.vocaease.patient.ui.theme.TextSecondary
import com.vocaease.patient.ui.theme.VocaEaseTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import java.util.UUID

typealias CatalogContent = @Composable ((String) -> Unit) -> Unit
typealias ProfileContent = @Composable (ProfileNavigation) -> Unit
typealias PreparationContent = @Composable (String, () -> Unit, (String) -> Unit) -> Unit
typealias RecordingContent = @Composable (String, () -> Unit, (String) -> Unit) -> Unit
typealias ReviewContent = @Composable (String, () -> Unit, (String) -> Unit, (String) -> Unit) -> Unit
typealias HistoryContent = @Composable (() -> Unit, (String) -> Unit) -> Unit
typealias ResultContent = @Composable (String, () -> Unit) -> Unit
typealias SettingsContent = @Composable (() -> Unit) -> Unit

private sealed interface RecordingPlaybackClaim {
    data object Loading : RecordingPlaybackClaim
    data object Missing : RecordingPlaybackClaim
    data class Ready(val playback: RecordingPlayback) : RecordingPlaybackClaim
}

data class ProfileNavigation(
    val openHistory: () -> Unit,
    val openResult: (String) -> Unit,
    val openTreatmentPlan: () -> Unit,
    val openPendingUploads: () -> Unit,
    val openSettings: () -> Unit,
)

@Composable
fun VocaEaseApp(
    container: AppContainer,
    initialRoute: AppRoute? = null,
) {
    VocaEaseTheme {
        CompositionLocalProvider(LocalAppContainer provides container) {
            if (initialRoute == null) {
                AuthFlow(repository = container.authRepository) {
                    AuthenticatedApp(
                        initialRoute = AppRoute.Catalog,
                        preparationContent = { songId, onBack, onRecording ->
                            PreparationRoute(songId, onBack, onRecording)
                        },
                        recordingContent = { draftId, onBack, onReview -> RecordingRoute(draftId, onBack, onReview) },
                        reviewContent = { draftId, onBack, onRerecord, onPending ->
                            ReviewRoute(draftId, onBack, onRerecord, onPending)
                        },
                    )
                }
            } else {
                AuthenticatedApp(
                    initialRoute,
                    preparationContent = { songId, onBack, onRecording ->
                        PreparationRoute(songId, onBack, onRecording)
                    },
                    recordingContent = { draftId, onBack, onReview -> RecordingRoute(draftId, onBack, onReview) },
                    reviewContent = { draftId, onBack, onRerecord, onPending ->
                        ReviewRoute(draftId, onBack, onRerecord, onPending)
                    },
                )
            }
        }
    }
}

@Composable
internal fun AuthenticatedApp(
    initialRoute: AppRoute,
    catalogContent: CatalogContent = { onSongClick -> CatalogRoute(onSongClick) },
    profileContent: ProfileContent = { navigation -> ProfileRoute(navigation) },
    preparationContent: PreparationContent = { _, _, _ -> PlaceholderScreen("准备演唱") },
    recordingContent: RecordingContent = { _, _, _ -> PlaceholderScreen("正在录制") },
    reviewContent: ReviewContent = { _, _, _, _ -> PlaceholderScreen("本地回看") },
    historyContent: HistoryContent = { onBack, onResult -> HistoryRoute(onBack, onResult) },
    resultContent: ResultContent = { sessionId, onBack -> ResultRoute(sessionId, onBack) },
    settingsContent: SettingsContent = { onBack -> SettingsRoute(onBack) },
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val recordingRoute = destination?.hasRoute<AppRoute.Recording>() == true ||
        (destination == null && initialRoute is AppRoute.Recording)
    val showBottomBar = destination?.isMainDestination()
        ?: (initialRoute == AppRoute.Catalog || initialRoute == AppRoute.Profile)

    Scaffold(
        containerColor = if (recordingRoute) Color(0xFF06100B) else AppBackground,
        bottomBar = {
            if (showBottomBar) MainNavigationBar(navController, destination)
        },
    ) { padding ->
        AppNavHost(
            navController = navController,
            initialRoute = initialRoute,
            padding = padding,
            catalogContent = catalogContent,
            profileContent = profileContent,
            preparationContent = preparationContent,
            recordingContent = recordingContent,
            reviewContent = reviewContent,
            historyContent = historyContent,
            resultContent = resultContent,
            settingsContent = settingsContent,
        )
    }
}

private fun NavDestination.isMainDestination(): Boolean =
    hasRoute<AppRoute.Catalog>() || hasRoute<AppRoute.Profile>()

@Composable
private fun MainNavigationBar(
    navController: NavHostController,
    destination: NavDestination?,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppBackground)
            .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 10.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().height(70.dp),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = AppWhite),
            elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                MainTab(
                    icon = "♪",
                    label = "去唱歌",
                    selected = destination?.hasRoute<AppRoute.Catalog>() == true,
                    onClick = { navController.navigateToMainDestination(AppRoute.Catalog) },
                    modifier = Modifier.weight(1f),
                )
                MainTab(
                    icon = "♙",
                    label = "我的",
                    selected = destination?.hasRoute<AppRoute.Profile>() == true,
                    onClick = { navController.navigateToMainDestination(AppRoute.Profile) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MainTab(
    icon: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val color = if (selected) BrandGreen else TextSecondary
    Column(
        modifier = modifier
            .fillMaxSize()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(icon, color = color, fontSize = 22.sp, lineHeight = 24.sp)
        Text(
            label,
            color = color,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

private fun NavHostController.navigateToMainDestination(route: AppRoute) {
    navigate(route) {
        popUpTo(AppRoute.Catalog) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    initialRoute: AppRoute,
    padding: PaddingValues,
    catalogContent: CatalogContent,
    profileContent: ProfileContent,
    preparationContent: PreparationContent,
    recordingContent: RecordingContent,
    reviewContent: ReviewContent,
    historyContent: HistoryContent,
    resultContent: ResultContent,
    settingsContent: SettingsContent,
) {
    NavHost(
        navController = navController,
        startDestination = initialRoute,
        modifier = Modifier.fillMaxSize().padding(padding),
    ) {
        composable<AppRoute.Login> { PlaceholderScreen("登录") }
        composable<AppRoute.ChangePassword> { PlaceholderScreen("修改密码") }
        composable<AppRoute.Catalog> {
            catalogContent { songId -> navController.navigate(AppRoute.Preparation(songId)) }
        }
        composable<AppRoute.Profile> {
            profileContent(
                ProfileNavigation(
                    openHistory = { navController.navigate(AppRoute.History) },
                    openResult = { sessionId -> navController.navigate(AppRoute.Result(sessionId)) },
                    openTreatmentPlan = { navController.navigate(AppRoute.TreatmentPlan) },
                    openPendingUploads = { navController.navigate(AppRoute.PendingUploads) },
                    openSettings = { navController.navigate(AppRoute.Settings) },
                ),
            )
        }
        composable<AppRoute.Preparation> { entry ->
            val route = entry.toRoute<AppRoute.Preparation>()
            preparationContent(
                route.songId,
                { navController.popBackStack() },
                { draftId -> navController.navigate(AppRoute.Recording(draftId)) { launchSingleTop = true } },
            )
        }
        composable<AppRoute.Recording> { entry ->
            val route = entry.toRoute<AppRoute.Recording>()
            recordingContent(
                route.draftId,
                { navController.popBackStack() },
                { draftId ->
                    navController.navigate(AppRoute.Review(draftId)) {
                        popUpTo(AppRoute.Recording(draftId)) { inclusive = true }
                    }
                },
            )
        }
        composable<AppRoute.Review> { entry ->
            val route = entry.toRoute<AppRoute.Review>()
            reviewContent(
                route.draftId,
                { navController.popBackStack() },
                { songId ->
                    navController.navigate(AppRoute.Preparation(songId)) {
                        popUpTo(AppRoute.Review(route.draftId)) { inclusive = true }
                    }
                },
                {
                    navController.navigate(AppRoute.PendingUploads) {
                        popUpTo(AppRoute.Review(route.draftId)) { inclusive = true }
                    }
                },
            )
        }
        composable<AppRoute.PendingUploads> { PendingUploadsRoute { navController.popBackStack() } }
        composable<AppRoute.TreatmentPlan> { PlaceholderScreen("治疗计划") }
        composable<AppRoute.History> {
            historyContent(
                { navController.popBackStack() },
                { sessionId -> navController.navigate(AppRoute.Result(sessionId)) },
            )
        }
        composable<AppRoute.Result> { entry ->
            val route = entry.toRoute<AppRoute.Result>()
            resultContent(route.sessionId) { navController.popBackStack() }
        }
        composable<AppRoute.Settings> { settingsContent { navController.popBackStack() } }
    }
}

@Composable
private fun SettingsRoute(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(container.settingsAccountActions),
    )
    val state by settingsViewModel.state.collectAsState()
    SettingsScreen(
        state = state,
        onBack = onBack,
        onOldPasswordChange = settingsViewModel::updateOldPassword,
        onNewPasswordChange = settingsViewModel::updateNewPassword,
        onConfirmationChange = settingsViewModel::updateConfirmation,
        onToggleOldPassword = settingsViewModel::toggleOldPassword,
        onToggleNewPassword = settingsViewModel::toggleNewPassword,
        onToggleConfirmation = settingsViewModel::toggleConfirmation,
        onSubmitPassword = settingsViewModel::submitPasswordChange,
        onRequestLogout = settingsViewModel::requestLogout,
        onConfirmLogout = settingsViewModel::confirmLogout,
        onChooseRetain = settingsViewModel::chooseRetain,
        onChooseDelete = settingsViewModel::chooseDelete,
        onDismissLogout = settingsViewModel::dismissLogout,
    )
}

@Composable
private fun CatalogRoute(onSongClick: (String) -> Unit) {
    val container = LocalAppContainer.current
    val catalogViewModel: CatalogViewModel = viewModel(
        factory = CatalogViewModel.factory(container.patientRepository, container.songRepository),
    )
    val state by catalogViewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(catalogViewModel) { catalogViewModel.start() }
    CatalogScreen(
        state = state,
        onSearch = { keyword -> scope.launch { catalogViewModel.search(keyword) } },
        onPatientRetry = { scope.launch { catalogViewModel.retryPatient() } },
        onRetry = { scope.launch { catalogViewModel.retrySongs() } },
        onLoadMore = { scope.launch { catalogViewModel.loadMore() } },
        onSongClick = { song -> onSongClick(song.id.toString()) },
    )
}

@Composable
private fun ProfileRoute(navigation: ProfileNavigation) {
    val container = LocalAppContainer.current
    val profileViewModel: ProfileViewModel = viewModel(
        factory = ProfileViewModel.factory(container.patientRepository, container.pendingUploadCounter),
    )
    val state by profileViewModel.state.collectAsState()
    val storage = remember(container) { container.draftStorage.current() }
    val historyRepository = remember(storage, container) {
        HistoryRepository(
            AccountScopedHistoryLocalSource(storage),
            VocaEaseHistoryRemoteSource(container.patientApi, storage.accountScopeHash),
            storage::isLeaseActive,
        )
    }
    val historyViewModel: HistoryViewModel = viewModel(
        key = "profile-history:${storage.accountScopeHash}:${storage.cleanupScopeToken}",
        factory = HistoryViewModel.factory(historyRepository, container.dispatchers.io),
    )
    val historyState by historyViewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(profileViewModel) { profileViewModel.start() }
    LaunchedEffect(historyViewModel) { historyViewModel.start() }
    ProfileScreen(
        state = state,
        onHistoryClick = navigation.openHistory,
        onTreatmentPlanClick = navigation.openTreatmentPlan,
        onPendingUploadsClick = navigation.openPendingUploads,
        onSettingsClick = navigation.openSettings,
        onRetry = { scope.launch { profileViewModel.refresh() } },
        historyItems = historyState.items,
        onHistoryItemClick = navigation.openResult,
    )
}

@Composable
private fun PendingUploadsRoute(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val storage = remember(container) { container.draftStorage.current() }
    val coordinator = remember(container) { container.uploadFactory.create() as UploadCoordinator }
    val pendingViewModel: PendingUploadsViewModel = viewModel(
        key = "pending-uploads:${storage.accountScopeHash}",
        factory = PendingUploadsViewModel.factory(storage, coordinator),
    )
    val state by pendingViewModel.state.collectAsState()
    PendingUploadsScreen(
        state = state,
        onBack = onBack,
        onPause = pendingViewModel::pause,
        onResume = pendingViewModel::resume,
        onRetry = pendingViewModel::retry,
        onDelete = pendingViewModel::delete,
    )
}

@Composable
private fun HistoryRoute(onBack: () -> Unit, onResult: (String) -> Unit) {
    val container = LocalAppContainer.current
    val storage = remember(container) { container.draftStorage.current() }
    val repository = remember(storage, container) {
        HistoryRepository(
            local = AccountScopedHistoryLocalSource(storage),
            remote = VocaEaseHistoryRemoteSource(container.patientApi, storage.accountScopeHash),
            leaseActive = storage::isLeaseActive,
        )
    }
    val historyViewModel: HistoryViewModel = viewModel(
        key = "history:${storage.accountScopeHash}:${storage.cleanupScopeToken}",
        factory = HistoryViewModel.factory(repository, container.dispatchers.io),
    )
    val state by historyViewModel.state.collectAsState()
    LaunchedEffect(historyViewModel) { historyViewModel.start() }
    HistoryScreen(
        state = state,
        onBack = onBack,
        onRetry = historyViewModel::retry,
        onLoadMore = historyViewModel::loadMore,
        onItemClick = onResult,
    )
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ResultRoute(sessionId: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerLifecycle = remember(sessionId) { PrivateVideoRouteLifecycle() }
    val storage = remember(sessionId, container) { container.draftStorage.current() }
    val retryRemote = remember(sessionId, storage, container) {
        VocaEaseAnalysisRetryRemote(container.patientApi, storage.accountScopeHash)
    }
    val retryCoordinator = remember(retryRemote) { AnalysisRetryCoordinator(retryRemote, maxAttempts = 3) }
    val analysisCoordinator = remember(container) {
        container.repositoryFactory.create("analysis-worker") as AnalysisSyncCoordinator
    }
    val analysisSessionSynchronizer = remember(container) {
        container.repositoryFactory.create("analysis-session-sync") as ProductionAnalysisSessionSynchronizer
    }
    val resultViewModel: ResultViewModel = viewModel(
        key = "result:${storage.accountScopeHash}:${storage.cleanupScopeToken}:$sessionId",
        factory = ResultViewModel.factory(
            sessionId = sessionId,
            remote = analysisSessionSynchronizer.resultRemote(storage.accountScopeHash, storage.cleanupScopeToken),
            retryAction = {
                val detail = retryRemote.detail(sessionId)
                when (val outcome = retryCoordinator.retry(detail)) {
                    is AnalysisRetryOutcome.Accepted -> {
                        if (analysisCoordinator.restart(sessionId, outcome.generation)) outcome
                        else AnalysisRetryOutcome.Rejected
                    }
                    else -> outcome
                }
            },
            dispatcher = container.dispatchers.io,
        ),
    )
    val state by resultViewModel.state.collectAsState()
    val playerView = remember(context) {
        PlayerView(context).apply {
            useController = true
            contentDescription = "私有演唱录像"
        }
    }
    val engine by produceState<Media3PrivateVideoEngine?>(null, playerView) {
        value = Media3PrivateVideoEngine.create(context, playerView)
    }
    val privatePlayer = remember(engine, storage, container) {
        engine?.let { readyEngine ->
            PrivateSessionVideoPlayer(
                remote = VocaEasePrivateVideoRemote(container.patientApi, storage.accountScopeHash),
                engine = readyEngine,
                nowEpochMillis = container.clock::nowEpochMilliseconds,
                leaseActive = storage::isLeaseActive,
                launchPlaybackError = playerLifecycle::launch,
            )
        }
    }
    val privateVideoState = privatePlayer?.stateFlow?.collectAsState()?.value
    LaunchedEffect(resultViewModel) { resultViewModel.start() }
    LaunchedEffect(privatePlayer, state.content?.contentState, sessionId) {
        if (state.content?.contentState == ResultContentState.COMPLETED) privatePlayer?.open(sessionId)
    }
    DisposableEffect(resultViewModel) {
        onDispose { resultViewModel.stop() }
    }
    if (privatePlayer != null) {
        DisposableEffect(privatePlayer, storage) {
            val registration = storage.onLeaseInvalidated {
                playerLifecycle.launch { privatePlayer.invalidateLease() }
            }
            onDispose {
                registration.unregister()
                playerLifecycle.releaseWhenReady { privatePlayer.releaseAndAwait() }
            }
        }
    }
    ResultScreen(
        state = state,
        videoContent = {
            when (val videoState = privateVideoState) {
                is PrivateVideoState.Ready -> AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize())
                is PrivateVideoState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(videoState.message, color = AppWhite, fontSize = 12.sp)
                    TextButton(onClick = { scope.launch { privatePlayer.open(sessionId) } }) {
                        Text("重试视频", color = AppWhite)
                    }
                }
                PrivateVideoState.Unavailable -> Text("视频暂不可用", color = AppWhite, fontSize = 12.sp)
                PrivateVideoState.Loading, null -> CircularProgressIndicator(color = BrandGreen)
            }
        },
        onBack = onBack,
        onRetryLoad = {
            resultViewModel.stop()
            resultViewModel.start()
        },
        onRetryAnalysis = { scope.launch { resultViewModel.retryAnalysis() } },
    )
}

@Composable
private fun PreparationRoute(
    songId: String,
    onBack: () -> Unit,
    onRecording: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val activity = requireNotNull(context.findActivity()) { "演唱准备页需要 Activity 上下文" }
    var permissionsRequested by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val preview = remember(songId, container) {
        container.mediaFactory.createPreviewSession()
    }
    val factory = remember(songId, container, activity) {
        viewModelFactory {
            initializer {
                PreparationViewModel(
                    songId = songId,
                    songSource = VocaEasePreparationSongSource(container.patientApi),
                    readinessSource = AndroidReadinessSource(
                        activity,
                        { permissionsRequested },
                        container.dispatchers.io,
                        container.connectivity::isOnline,
                    ),
                    environmentMonitor = container.connectivity.environmentMonitor(),
                    preview = preview,
                    storageProvider = AccountScopedPreparationDraftStoreProvider(container.draftStorage),
                    sessionCreator = VocaEaseTrainingSessionCreator(container.patientApi),
                    savedState = SavedStatePreparationState(createSavedStateHandle()),
                    countdownTick = { delay(1_000) },
                    idFactory = { UUID.randomUUID().toString() },
                    clock = container.clock::nowEpochMilliseconds,
                    dispatcher = container.dispatchers.io,
                    onPlaybackHandoff = { draftId, session ->
                        container.recordingPlaybackHandoff.offer(draftId, session)
                    },
                    onPlaybackHandoffCancelled = container.recordingPlaybackHandoff::discard,
                )
            }
        }
    }
    val viewModel: PreparationViewModel = viewModel(key = "preparation:$songId", factory = factory)
    val state by viewModel.state.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionsRequested = true
        scope.launch { viewModel.refreshReadiness() }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(viewModel, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onEnvironmentStarted()
                Lifecycle.Event.ON_RESUME -> viewModel.onEnvironmentResumed()
                Lifecycle.Event.ON_STOP -> viewModel.onEnvironmentStopped()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.onEnvironmentStarted()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onEnvironmentStopped()
        }
    }

    LaunchedEffect(viewModel) { viewModel.load() }
    LaunchedEffect(state.navigateToRecordingDraftId) {
        state.navigateToRecordingDraftId?.let { draftId ->
            viewModel.consumeRecordingNavigation()
            onRecording(draftId)
        }
    }
    val abandonAndBack = {
        scope.launch {
            viewModel.abandonPreparation()
            onBack()
        }
        Unit
    }
    BackHandler(onBack = abandonAndBack)
    PreparationScreen(
        state = state,
        onBack = abandonAndBack,
        onStart = { scope.launch { viewModel.startTraining() } },
        onRequestPermissions = {
            permissionsRequested = true
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        },
        onOpenSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        },
        onRetry = { scope.launch { viewModel.load() } },
        onPreviewToggle = { scope.launch { viewModel.togglePreview() } },
        onModeChange = { mode -> scope.launch { viewModel.switchPreviewMode(mode) } },
        onRetryPreview = { scope.launch { viewModel.retryPreview() } },
    )
}

@Composable
private fun RecordingRoute(
    draftId: String,
    onBack: () -> Unit,
    onReview: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val activity = requireNotNull(context.findActivity()) { "演唱录制页需要 Activity 上下文" }
    RecordingSystemChrome(activity.window)
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val storage = remember(draftId, container) { container.draftStorage.current() }
    val playbackClaim by produceState<RecordingPlaybackClaim>(
        initialValue = RecordingPlaybackClaim.Loading,
        key1 = draftId,
        key2 = container,
    ) {
        val claimed = container.recordingPlaybackHandoff.take(draftId)
        value = claimed?.let(RecordingPlaybackClaim::Ready) ?: RecordingPlaybackClaim.Missing
        try {
            awaitCancellation()
        } finally {
            claimed?.stop()
        }
    }
    if (playbackClaim === RecordingPlaybackClaim.Loading) {
        PlaceholderScreen("正在接管录制")
        return
    }
    if (playbackClaim === RecordingPlaybackClaim.Missing) {
        LaunchedEffect(draftId) {
            runCatching { storage.markRecordingInterrupted(draftId, 0, "歌曲播放交接已失效") }
            onBack()
        }
        PlaceholderScreen("录制已中断")
        return
    }
    val playback = (playbackClaim as RecordingPlaybackClaim.Ready).playback
    val tempFiles = remember(context) { PrivateRecordingTempFiles(context).also { it.cleanupOrphans() } }
    val coordinator = remember(draftId, previewView, lifecycleOwner, playback, storage) {
        DefaultRecordingCoordinator(
            capture = container.recordingCaptureFactory.create(context, lifecycleOwner, previewView.surfaceProvider) { previewView.viewPort },
            playback = playback,
            clockNanos = container.monotonicClock::nowNanoseconds,
            tempFiles = tempFiles,
            publisher = AccountScopedRecordingArtifactPublisher(storage),
            persistMetadata = storage::savePlaybackMetadata,
        )
    }
    val factory = remember(draftId, coordinator, storage) {
        viewModelFactory {
            initializer {
                RecordingViewModel(
                    draftId,
                    coordinator,
                    AccountScopedRecordingDraftGateway(storage),
                    // Task 7 已向患者展示 3、2、1；本页只完成状态机交接，避免重复等待。
                    countdownTick = {},
                    referenceRepository = com.vocaease.patient.feature.training.ReferencePitchRepository { id, version -> container.patientApi.referencePitch(id, version).data },
                    dispatcher = container.dispatchers.io,
                )
            }
        }
    }
    val recordingViewModel: RecordingViewModel = viewModel(key = "recording:$draftId", factory = factory)
    val state by recordingViewModel.state.collectAsState()

    val interruptionCoordinator = remember(recordingViewModel, context, scope) {
        RecordingEnvironmentInterruptionCoordinator(
            scope = scope,
            dispatcher = container.dispatchers.io,
            audioFocus = AndroidRecordingAudioFocus(context),
            interrupt = { reason ->
                when (reason) {
                    com.vocaease.patient.feature.training.RecordingInterruption.AUDIO ->
                        recordingViewModel.onAudioFocusLost()
                    else -> recordingViewModel.onHostStopped()
                }
            },
        )
    }
    DisposableEffect(lifecycleOwner, interruptionCoordinator) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) interruptionCoordinator.onHostStopped()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            interruptionCoordinator.close()
        }
    }

    LaunchedEffect(recordingViewModel, interruptionCoordinator) {
        interruptionCoordinator.startAndAwaitReady()
        recordingViewModel.start()
    }
    LaunchedEffect(state.navigateReviewDraftId) {
        state.navigateReviewDraftId?.let { id ->
            recordingViewModel.consumeReviewNavigation()
            onReview(id)
        }
    }
    DisposableEffect(state.keepScreenOn, activity) {
        if (state.keepScreenOn) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(recordingViewModel) {
        onDispose { recordingViewModel.disposeRoute() }
    }
    val leave = {
        scope.launch {
            recordingViewModel.leave()
            onBack()
        }
        Unit
    }
    BackHandler(onBack = leave)
    RecordingScreen(
        state = state,
        preview = {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        },
        onStop = { scope.launch { recordingViewModel.stop() } },
        onModeChange = { mode -> scope.launch { recordingViewModel.switchMode(mode) } },
        onClose = leave,
    )
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ReviewRoute(
    draftId: String,
    onBack: () -> Unit,
    onRerecord: (String) -> Unit,
    onPendingUploads: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storage = remember(draftId, container) { container.draftStorage.current() }
    val playerView = remember(context) {
        PlayerView(context).apply {
            useController = false
            contentDescription = "本地录制视频"
        }
    }
    val player = remember(draftId, storage, playerView) {
        container.mediaFactory.createReviewPlayer(storage, playerView)
    }
    val gateway = remember(storage) {
        DraftRepository(AccountScopedLocalReviewStore(storage), LocalUploadQueueSignals)
    }
    val factory = remember(draftId, gateway, player) {
        viewModelFactory {
            initializer {
                ReviewViewModel(draftId, gateway, player, container.dispatchers.io)
            }
        }
    }
    val reviewViewModel: ReviewViewModel = viewModel(key = "review:$draftId", factory = factory)
    val state by reviewViewModel.state.collectAsState()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(reviewViewModel) { reviewViewModel.load() }
    LaunchedEffect(
        state.navigatePreparationSongId,
        state.navigatePendingUploadDraftId,
        state.navigateBack,
    ) {
        when {
            state.navigatePreparationSongId != null -> {
                val songId = requireNotNull(state.navigatePreparationSongId)
                reviewViewModel.consumeNavigation()
                onRerecord(songId)
            }
            state.navigatePendingUploadDraftId != null -> {
                val id = requireNotNull(state.navigatePendingUploadDraftId)
                val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
                if (UploadNotificationPermission.shouldRequest(Build.VERSION.SDK_INT, true, granted)) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                reviewViewModel.consumeNavigation()
                onPendingUploads(id)
            }
            state.navigateBack -> {
                reviewViewModel.consumeNavigation()
                onBack()
            }
        }
    }
    val leaveAndBack = {
        scope.launch {
            reviewViewModel.leave()
            onBack()
        }
        Unit
    }
    BackHandler(onBack = leaveAndBack)
    ReviewScreen(
        state = state,
        videoContent = {
            AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize())
        },
        onBack = leaveAndBack,
        onPlayPause = { scope.launch { reviewViewModel.playPause() } },
        onSeek = { scope.launch { reviewViewModel.seekTo(it) } },
        onSwitchMedia = { scope.launch { reviewViewModel.switchMedia(it) } },
        onRerecord = { scope.launch { reviewViewModel.rerecord() } },
        onDelete = { scope.launch { reviewViewModel.delete() } },
        onConfirm = { scope.launch { reviewViewModel.confirm() } },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun PlaceholderScreen(title: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(title)
    }
}
