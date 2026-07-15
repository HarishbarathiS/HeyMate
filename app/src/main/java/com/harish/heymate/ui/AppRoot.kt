package com.harish.heymate.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.harish.heymate.ui.screens.DevicesScreen
import com.harish.heymate.ui.screens.GalleryScreen
import com.harish.heymate.ui.screens.GlassesScreen
import com.harish.heymate.ui.screens.HomeScreen
import com.harish.heymate.ui.screens.PreviewScreen
import com.harish.heymate.ui.screens.SettingsScreen
import com.harish.heymate.ui.theme.Black
import com.harish.heymate.ui.theme.SurfaceHigh
import com.harish.heymate.ui.theme.TextSecondary
import com.harish.heymate.ui.theme.White

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Outlined.Home),
    Tab("gallery", "Gallery", Icons.Outlined.PhotoLibrary),
    Tab("devices", "Devices", Icons.Outlined.Visibility),
    Tab("settings", "Settings", Icons.Outlined.Settings),
)

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination

    // The file currently being previewed fullscreen. Held here rather than passed as a nav arg
    // because MediaFile isn't trivially serializable and the preview is a transient detail view.
    val previewFile = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.harish.heymate.wifitransfer.MediaFile?>(null)
    }

    // Single, consistent way to switch top-level tabs. Used by both the bottom bar and any
    // in-screen jump (e.g. Home's "Connect" → Devices) so the back stack never gets wedged.
    fun switchTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = Black,
        bottomBar = {
            NavigationBar(containerColor = Black, tonalElevation = 0.dp) {
                tabs.forEach { tab ->
                    val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = { switchTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = White,
                            selectedTextColor = White,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = SurfaceHigh,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding),
        ) {
            composable("home") { HomeScreen(onGoToDevices = { switchTab("devices") }) }
            composable("gallery") {
                GalleryScreen(onOpenFile = { file ->
                    previewFile.value = file
                    navController.navigate("preview")
                })
            }
            composable("preview") {
                previewFile.value?.let { file ->
                    PreviewScreen(file = file, onBack = { navController.popBackStack() })
                } ?: run { navController.popBackStack() }
            }
            composable("devices") { DevicesScreen() }
            composable("settings") {
                SettingsScreen(onGoToHardware = { navController.navigate("hardware") })
            }
            // The glasses hardware detail (firmware, controls, volume, storage) now lives under
            // Settings rather than a top-level tab.
            composable("hardware") { GlassesScreen() }
        }
    }
}
