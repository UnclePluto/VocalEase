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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.vocaease.patient.ui.theme.AppBackground
import com.vocaease.patient.ui.theme.AppWhite
import com.vocaease.patient.ui.theme.BrandGreen
import com.vocaease.patient.ui.theme.TextSecondary
import com.vocaease.patient.ui.theme.VocaEaseTheme
import kotlinx.coroutines.launch

typealias CatalogContent = @Composable ((String) -> Unit) -> Unit
typealias ProfileContent = @Composable (ProfileNavigation) -> Unit

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
                    AuthenticatedApp(initialRoute = AppRoute.Catalog)
                }
            } else {
                AuthenticatedApp(initialRoute)
            }
        }
    }
}

@Composable
internal fun AuthenticatedApp(
    initialRoute: AppRoute,
    catalogContent: CatalogContent = { onSongClick -> CatalogRoute(onSongClick) },
    profileContent: ProfileContent = { navigation -> ProfileRoute(navigation) },
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
        composable<AppRoute.Preparation> { PlaceholderScreen("准备演唱") }
        composable<AppRoute.Recording> { PlaceholderScreen("正在录制") }
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
private fun PlaceholderScreen(title: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(title)
    }
}
