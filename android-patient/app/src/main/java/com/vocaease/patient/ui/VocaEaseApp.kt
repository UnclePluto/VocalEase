package com.vocaease.patient.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vocaease.patient.AppContainer
import com.vocaease.patient.LocalAppContainer
import com.vocaease.patient.ui.theme.AppWhite
import com.vocaease.patient.ui.theme.MinimumTouchTargetSize
import com.vocaease.patient.ui.theme.VocaEaseTheme

@Composable
fun VocaEaseApp(
    container: AppContainer,
    initialRoute: AppRoute = AppRoute.Catalog,
) {
    VocaEaseTheme {
        CompositionLocalProvider(LocalAppContainer provides container) {
            val navController = rememberNavController()
            val backStackEntry by navController.currentBackStackEntryAsState()
            val destination = backStackEntry?.destination
            val showBottomBar = destination?.isMainDestination()
                ?: (initialRoute == AppRoute.Catalog || initialRoute == AppRoute.Profile)

            Scaffold(
                bottomBar = {
                    if (showBottomBar) {
                        MainNavigationBar(navController, destination)
                    }
                },
            ) { padding ->
                AppNavHost(
                    navController = navController,
                    initialRoute = initialRoute,
                    padding = padding,
                )
            }
        }
    }
}

private fun NavDestination.isMainDestination(): Boolean =
    hasRoute<AppRoute.Catalog>() || hasRoute<AppRoute.Profile>()

@Composable
private fun MainNavigationBar(
    navController: NavHostController,
    destination: NavDestination?,
) {
    NavigationBar(containerColor = AppWhite) {
        NavigationBarItem(
            selected = destination?.hasRoute<AppRoute.Catalog>() == true,
            onClick = { navController.navigateToMainDestination(AppRoute.Catalog) },
            icon = { Text("唱") },
            label = { Text("去唱歌") },
            modifier = Modifier.heightIn(min = MinimumTouchTargetSize),
        )
        NavigationBarItem(
            selected = destination?.hasRoute<AppRoute.Profile>() == true,
            onClick = { navController.navigateToMainDestination(AppRoute.Profile) },
            icon = { Text("我") },
            label = { Text("我的") },
            modifier = Modifier.heightIn(min = MinimumTouchTargetSize),
        )
    }
}

private fun NavHostController.navigateToMainDestination(route: AppRoute) {
    navigate(route) {
        popUpTo(AppRoute.Catalog) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    initialRoute: AppRoute,
    padding: PaddingValues,
) {
    NavHost(
        navController = navController,
        startDestination = initialRoute,
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        composable<AppRoute.Login> { PlaceholderScreen("登录") }
        composable<AppRoute.ChangePassword> { PlaceholderScreen("修改密码") }
        composable<AppRoute.Catalog> {
            CatalogPlaceholder(
                onSongClick = { navController.navigate(AppRoute.Preparation("song-1")) },
            )
        }
        composable<AppRoute.Profile> { PlaceholderScreen("我的") }
        composable<AppRoute.Preparation> { PlaceholderScreen("准备演唱") }
        composable<AppRoute.Recording> { PlaceholderScreen("正在录制") }
        composable<AppRoute.Review> { PlaceholderScreen("确认作品") }
        composable<AppRoute.PendingUploads> { PlaceholderScreen("待上传") }
        composable<AppRoute.History> { PlaceholderScreen("演唱记录") }
        composable<AppRoute.Result> { PlaceholderScreen("分析结果") }
        composable<AppRoute.Settings> { PlaceholderScreen("设置") }
    }
}

@Composable
private fun CatalogPlaceholder(onSongClick: () -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Card(
            onClick = onSongClick,
            modifier = Modifier
                .testTag("song-card-1")
                .heightIn(min = MinimumTouchTargetSize),
        ) {
            Text(
                text = "歌曲卡片",
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(title)
    }
}
