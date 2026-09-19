package com.ultra.youtube.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ultra.youtube.app.data.YoutubeRepository
import com.ultra.youtube.app.data.auth.CookieStore
import com.ultra.youtube.app.ui.screens.FeedScreen
import com.ultra.youtube.app.ui.screens.HomeFeedScreen
import com.ultra.youtube.app.ui.screens.LoginScreen
import com.ultra.youtube.app.ui.screens.PlayerScreen
import com.ultra.youtube.app.ui.screens.SearchScreen

/**
 * Single-pane navigation with a bottom bar.
 *
 * One `NavHost`, no nested navigation graphs: fewer back-stack entries means fewer
 * retained ViewModel stores, which matters when the whole point is a small heap.
 */
object Destinations {
    const val HOME = "home"
    const val SUBSCRIPTIONS = "subscriptions"
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val SIGN_IN = "sign_in"

    const val WATCH_ARG = "videoId"
    const val SEARCH_QUERY_ARG = "query"
    fun watch(videoId: String) = "watch/$videoId"
    fun searchResults(query: String) = "search_results/$query"
}

private data class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val TABS = listOf(
    Tab(Destinations.HOME, "Home", Icons.Default.Home),
    Tab(Destinations.SUBSCRIPTIONS, "Subs", Icons.Default.Subscriptions),
    Tab(Destinations.LIBRARY, "Library", Icons.Default.LibraryBooks),
    Tab(Destinations.SEARCH, "Search", Icons.Default.Search),
)

/** Watch Later is playlist-scoped, so it hangs off the Library tab instead of being a tab. */
private val WATCH_LATER_TAB = Tab("watch_later", "Watch Later", Icons.Default.WatchLater)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppNavHost(
    repository: YoutubeRepository,
    cookieStore: CookieStore,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        topBar = {
            if (TABS.any { it.route == currentRoute }) {
                androidx.compose.material3.TopAppBar(
                    title = { Text("You-Tube") },
                    actions = {
                        if (currentRoute == Destinations.LIBRARY) {
                            IconButton(onClick = { navController.navigate(WATCH_LATER_TAB.route) }) {
                                Icon(Icons.Default.WatchLater, contentDescription = "Watch Later")
                            }
                        }
                        IconButton(onClick = { navController.navigate(Destinations.SIGN_IN) }) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = if (cookieStore.isAuthenticated) "Account" else "Sign in",
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (TABS.any { it.route == currentRoute }) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                if (currentRoute != tab.route) {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destinations.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destinations.HOME) {
                val vm = feedViewModel(repository, FeedViewModel.FeedSource.Home)
                HomeFeedScreen(
                    viewModel = vm,
                    onVideoClick = { video -> navController.navigate(Destinations.watch(video.id)) },
                )
            }

            composable(Destinations.SUBSCRIPTIONS) {
                val vm = feedViewModel(repository, FeedViewModel.FeedSource.Subscriptions)
                FeedScreen(
                    viewModel = vm,
                    title = "Subscriptions",
                    onVideoClick = { video -> navController.navigate(Destinations.watch(video.id)) },
                )
            }

            composable(Destinations.LIBRARY) {
                val vm = feedViewModel(repository, FeedViewModel.FeedSource.Library)
                FeedScreen(
                    viewModel = vm,
                    title = "Library",
                    onVideoClick = { video -> navController.navigate(Destinations.watch(video.id)) },
                )
            }

            composable(Destinations.SEARCH) {
                SearchScreen(
                    onSearch = { query -> navController.navigate(Destinations.searchResults(query)) },
                )
            }

            composable("search_results/{${Destinations.SEARCH_QUERY_ARG}}") { entry ->
                val query = entry.arguments?.getString(Destinations.SEARCH_QUERY_ARG).orEmpty()
                val vm = feedViewModel(repository, FeedViewModel.FeedSource.Search(query))
                FeedScreen(
                    viewModel = vm,
                    title = query,
                    onVideoClick = { video -> navController.navigate(Destinations.watch(video.id)) },
                )
            }

            composable("watch/{${Destinations.WATCH_ARG}}") { entry ->
                val videoId = entry.arguments?.getString(Destinations.WATCH_ARG).orEmpty()
                val vm: WatchViewModel = viewModel(
                    factory = WatchViewModel.factory(repository, videoId),
                )
                PlayerScreen(viewModel = vm, onBack = { navController.popBackStack() })
            }

            composable(WATCH_LATER_TAB.route) {
                // The Watch Later playlist id is account-specific; resolve it on entry.
                val vm = feedViewModel(repository, FeedViewModel.FeedSource.WatchLater(playlistId = ""))
                FeedScreen(
                    viewModel = vm,
                    title = "Watch Later",
                    onVideoClick = { video -> navController.navigate(Destinations.watch(video.id)) },
                )
            }

            composable(Destinations.SIGN_IN) {
                LoginScreen(
                    onSignedIn = { session ->
                        // This is what makes every authenticated InnerTube call work:
                        // the CookieStore feeds the SAPISIDHASH header on later requests.
                        cookieStore.update(session)
                        navController.popBackStack()
                    },
                )
            }
        }
    }
}

@Composable
private fun feedViewModel(
    repository: YoutubeRepository,
    source: FeedViewModel.FeedSource,
): FeedViewModel = viewModel(factory = FeedViewModel.factory(repository, source))
