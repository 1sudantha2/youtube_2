package com.youtubelite.app.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.youtubelite.app.R
import com.youtubelite.app.model.FeedSource
import com.youtubelite.app.ui.screens.CommentsScreen
import com.youtubelite.app.ui.screens.FeedScreen
import com.youtubelite.app.ui.screens.LoginScreen
import com.youtubelite.app.ui.screens.SearchScreen
import com.youtubelite.app.ui.screens.WatchScreen

/** Hand-rolled stack navigation: zero library overhead, full back control. */
sealed class Screen {
    data object Login : Screen()
    data object Search : Screen()
    data class Watch(val videoId: String) : Screen()
    data class Comments(val videoId: String) : Screen()
}

class NavState {
    val tab = mutableIntStateOf(0)
    val stack = mutableStateListOf<Screen>()

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }
}

@Composable
fun AppRoot() {
    val nav = remember { NavState() }

    BackHandler(enabled = nav.stack.isNotEmpty()) { nav.pop() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (nav.stack.isEmpty()) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(
                        selected = nav.tab.intValue == 0,
                        onClick = { nav.tab.intValue = 0 },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("Home") },
                    )
                    NavigationBarItem(
                        selected = nav.tab.intValue == 1,
                        onClick = { nav.tab.intValue = 1 },
                        icon = {
                            Icon(
                                painterResource(R.drawable.ic_nav_subs),
                                contentDescription = null,
                            )
                        },
                        label = { Text("Subscriptions") },
                    )
                    NavigationBarItem(
                        selected = nav.tab.intValue == 2,
                        onClick = { nav.tab.intValue = 2 },
                        icon = {
                            Icon(
                                painterResource(R.drawable.ic_nav_library),
                                contentDescription = null,
                            )
                        },
                        label = { Text("Library") },
                    )
                }
            }
        },
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (val top = nav.stack.lastOrNull()) {
                null -> when (nav.tab.intValue) {
                    0 -> FeedScreen(FeedSource.HOME, nav)
                    1 -> FeedScreen(FeedSource.SUBSCRIPTIONS, nav)
                    else -> LibraryTabs(nav)
                }
                Screen.Login -> LoginScreen(onDone = { nav.pop() })
                Screen.Search -> SearchScreen(nav)
                is Screen.Watch -> WatchScreen(top.videoId, nav)
                is Screen.Comments -> CommentsScreen(top.videoId, nav)
            }
        }
    }
}

/** Library tab exposes the signed-in shelves with an in-tab switcher. */
@Composable
private fun LibraryTabs(nav: NavState) {
    var mode by rememberSaveable { mutableStateOf("library") }
    val source = when (mode) {
        "liked" -> FeedSource.LIKED
        "history" -> FeedSource.HISTORY
        else -> FeedSource.LIBRARY
    }
    FeedScreen(source, nav, headerExtra = { LibrarySwitcher(mode, onPick = { mode = it }) })
}

@Composable
private fun LibrarySwitcher(current: String, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = current == "library",
            onClick = { onPick("library") },
            label = { Text("Library") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = androidx.compose.ui.graphics.Color.White,
            ),
        )
        FilterChip(
            selected = current == "liked",
            onClick = { onPick("liked") },
            label = { Text("Liked") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = androidx.compose.ui.graphics.Color.White,
            ),
        )
        FilterChip(
            selected = current == "history",
            onClick = { onPick("history") },
            label = { Text("History") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = androidx.compose.ui.graphics.Color.White,
            ),
        )
    }
}
