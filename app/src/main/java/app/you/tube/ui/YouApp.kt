package app.you.tube.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.you.tube.MainActivity
import app.you.tube.R
import app.you.tube.di.AppContainer
import app.you.tube.ui.auth.LoginScreen
import app.you.tube.ui.home.HomeScreen
import app.you.tube.ui.library.LibraryScreen
import app.you.tube.ui.player.PlayerScreen
import app.you.tube.ui.search.SearchScreen
import app.you.tube.ui.settings.SettingsScreen
import app.you.tube.ui.subscriptions.SubscriptionsScreen
import app.you.tube.YouTubeApp

/** Manual-DI container exposed to the composition tree. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

// Plain string set — evaluated once, no composition needed.
private val BottomRoutes = setOf("home", "subs", "library", "settings")

// Icons resolve inside composition (vectorResource is @Composable).
@Composable
private fun bottomTabs(): List<BottomTab> = listOf(
    BottomTab("home", "Home", Icons.Filled.Home),
    BottomTab("subs", "Subscriptions", ImageVector.vectorResource(R.drawable.ic_subscriptions)),
    BottomTab("library", "Library", ImageVector.vectorResource(R.drawable.ic_library)),
    BottomTab("settings", "Settings", Icons.Filled.Settings)
)

@Composable
fun YouApp(initialVideoId: String?) {
    val app = LocalContext.current.applicationContext as YouTubeApp
    CompositionLocalProvider(LocalAppContainer provides app.container) {

        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        val showBottomBar = currentRoute in BottomRoutes

        // Media notification permission (Android 13+) — asked once, up front.
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33 &&
                !MainActivity.hasNotificationPermission(app)
            ) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (showBottomBar) {
                    val tabs = bottomTabs()
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                alwaysShowLabel = false
                            )
                        }
                    }
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "home",
                modifier = Modifier.padding(padding)
            ) {
                composable("home") {
                    HomeScreen(
                        onVideoClick = { id -> navController.navigate("watch/$id") },
                        onSearchClick = { navController.navigate("search") }
                    )
                }
                composable("subs") {
                    SubscriptionsScreen(
                        onVideoClick = { id -> navController.navigate("watch/$id") },
                        onSignIn = { navController.navigate("login") }
                    )
                }
                composable("library") {
                    LibraryScreen(
                        onVideoClick = { id -> navController.navigate("watch/$id") },
                        onSignIn = { navController.navigate("login") }
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        onOpenLogin = { navController.navigate("login") }
                    )
                }
                composable("search") {
                    SearchScreen(
                        onVideoClick = { id -> navController.navigate("watch/$id") },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable("login") {
                    LoginScreen(onDone = { navController.popBackStack() })
                }
                composable("watch/{videoId}") { entry ->
                    val videoId = entry.arguments?.getString("videoId").orEmpty()
                    PlayerScreen(
                        videoId = videoId,
                        onBack = { navController.popBackStack() },
                        onOpenVideo = { id -> navController.navigate("watch/$id") }
                    )
                }
            }

            // Cold-start deep link: land on home first (healthy back stack), then open the video.
            LaunchedEffect(initialVideoId) {
                if (!initialVideoId.isNullOrBlank() && currentRoute == "home") {
                    navController.navigate("watch/$initialVideoId")
                }
            }
        }
    }
}
