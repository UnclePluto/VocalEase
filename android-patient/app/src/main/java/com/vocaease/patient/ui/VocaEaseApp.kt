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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.camera.view.PreviewView
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
import com.vocaease.patient.core.media.PreviewPlayer
import com.vocaease.patient.core.media.AccountScopedRecordingArtifactPublisher
import com.vocaease.patient.core.media.CameraXRecordingCapture
import com.vocaease.patient.core.media.DefaultRecordingCoordinator
import com.vocaease.patient.core.media.PrivateRecordingTempFiles
import com.vocaease.patient.core.media.RecordingPlayback
import com.vocaease.patient.feature.training.AccountScopedPreparationDraftStoreProvider
import com.vocaease.patient.feature.training.AndroidReadinessSource
import com.vocaease.patient.feature.training.AndroidPreparationEnvironmentMonitor
import com.vocaease.patient.feature.training.PreparationScreen
import com.vocaease.patient.feature.training.PreparationViewModel
import com.vocaease.patient.feature.training.SavedStatePreparationState
import com.vocaease.patient.feature.training.VocaEasePreparationSongSource
import com.vocaease.patient.feature.training.VocaEasePreviewGrantSource
import com.vocaease.patient.feature.training.VocaEaseTrainingSessionCreator
import com.vocaease.patient.feature.training.AccountScopedRecordingDraftGateway
import com.vocaease.patient.feature.training.RecordingScreen
import com.vocaease.patient.feature.training.RecordingViewModel
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

private sealed interface RecordingPlaybackClaim {
    data object Loading : RecordingPlaybackClaim
    data object Missing : RecordingPlaybackClaim
    data class Ready(val playback: RecordingPlayback) : RecordingPlaybackClaim
}

data class ProfileNavigation(
    val openHistory: () -> Unit,
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
                    )
                }
            } else {
                AuthenticatedApp(
                    initialRoute,
                    preparationContent = { songId, onBack, onRecording ->
                        PreparationRoute(songId, onBack, onRecording)
                    },
                    recordingContent = { draftId, onBack, onReview -> RecordingRoute(draftId, onBack, onReview) },
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
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBottomBar = destination?.isMainDestination()
        ?: (initialRoute == AppRoute.Catalog || initialRoute == AppRoute.Profile)

    Scaffold(
        containerColor = AppBackground,
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
        composable<AppRoute.Review> { PlaceholderScreen("确认作品") }
        composable<AppRoute.PendingUploads> { PlaceholderScreen("待上传记录") }
        composable<AppRoute.TreatmentPlan> { PlaceholderScreen("治疗计划") }
        composable<AppRoute.History> { PlaceholderScreen("演唱记录") }
        composable<AppRoute.Result> { PlaceholderScreen("分析结果") }
        composable<AppRoute.Settings> { PlaceholderScreen("设置") }
    }
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
    val scope = rememberCoroutineScope()
    LaunchedEffect(profileViewModel) { profileViewModel.start() }
    ProfileScreen(
        state = state,
        onHistoryClick = navigation.openHistory,
        onTreatmentPlanClick = navigation.openTreatmentPlan,
        onPendingUploadsClick = navigation.openPendingUploads,
        onSettingsClick = navigation.openSettings,
        onRetry = { scope.launch { profileViewModel.refresh() } },
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
        PreviewPlayer(
            container.mediaFactory.createPreviewEngine(),
            VocaEasePreviewGrantSource(container.patientApi),
        )
    }
    val factory = remember(songId, container, activity) {
        viewModelFactory {
            initializer {
                PreparationViewModel(
                    songId = songId,
                    songSource = VocaEasePreparationSongSource(container.patientApi),
                    readinessSource = AndroidReadinessSource(activity, { permissionsRequested }, container.dispatchers.io),
                    environmentMonitor = AndroidPreparationEnvironmentMonitor(activity.applicationContext),
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
            capture = CameraXRecordingCapture(context, lifecycleOwner, previewView.surfaceProvider),
            playback = playback,
            clockNanos = System::nanoTime,
            tempFiles = tempFiles,
            publisher = AccountScopedRecordingArtifactPublisher(storage),
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
                    dispatcher = container.dispatchers.io,
                )
            }
        }
    }
    val recordingViewModel: RecordingViewModel = viewModel(key = "recording:$draftId", factory = factory)
    val state by recordingViewModel.state.collectAsState()

    LaunchedEffect(recordingViewModel) { recordingViewModel.start() }
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
        onClose = leave,
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
