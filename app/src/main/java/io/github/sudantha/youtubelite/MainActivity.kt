package io.github.sudantha.youtubelite

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sudantha.youtubelite.auth.LoginActivity
import io.github.sudantha.youtubelite.data.Feed
import io.github.sudantha.youtubelite.ui.*

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { YouTubeTheme { AppScreen(model) } }
    }
    override fun onStart() { super.onStart(); model.visibility(true) }
    override fun onStop() { model.visibility(false); super.onStop() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(model: MainViewModel) {
    val feed by model.feed.collectAsStateWithLifecycle()
    val watch by model.watch.collectAsStateWithLifecycle()
    val playback by model.playback.collectAsStateWithLifecycle()
    val background by model.backgroundAudio.collectAsStateWithLifecycle()
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var accountVisible by remember { mutableStateOf(false) }
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) model.signedIn()
    }
    val context = LocalContext.current
    BackHandler(enabled = watch.video != null || searchVisible) {
        if (watch.video != null) model.closeVideo() else { searchVisible = false; model.search("") }
    }
    Scaffold(topBar = {
        TopAppBar(title = {
            Row {
                Image(painterResource(R.drawable.ic_launcher), contentDescription = null, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Text("You-Tube")
            }
        }, navigationIcon = {
            if (watch.video != null) IconButton(onClick = model::closeVideo) { Icon(Icons.Default.ArrowBack, "Close video") }
        }, actions = {
            if (watch.video == null) IconButton(onClick = {
                if (searchVisible && query.isNotBlank()) model.search(query) else searchVisible = !searchVisible
            }) { Icon(Icons.Default.Search, "Search") }
            if (searchVisible && watch.video == null) IconButton(onClick = { searchVisible = false; query = ""; model.search("") }) {
                Icon(Icons.Default.Close, "Close search")
            }
            IconButton(onClick = { accountVisible = true }) { Icon(Icons.Default.AccountCircle, "Account and settings") }
        })
    }, bottomBar = {
        if (watch.video == null) NavigationBar {
            Feed.entries.forEach { tab ->
                NavigationBarItem(selected = feed.feed == tab && feed.query.isBlank(), onClick = {
                    searchVisible = false; query = ""; model.selectFeed(tab)
                }, icon = { Text(when (tab) { Feed.Home -> "⌂"; Feed.Subscriptions -> "▷"; Feed.Library -> "▤"; Feed.Liked -> "♡" }) },
                    label = { Text(tab.label, maxLines = 1, style = MaterialTheme.typography.labelSmall) })
            }
        }
    }) { insets ->
        if (watch.video != null) WatchScreen(watch, playback, model.engine.player, feed.signedIn, model, Modifier.padding(insets))
        else Column(Modifier.fillMaxSize().padding(insets)) {
            if (searchVisible) {
                TextField(value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search YouTube") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp)
                        .semantics { contentDescription = "Search query" },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { model.search(query) }))
            }
            HomeFeed(feed, model::open, model::refresh, model::loadMore, Modifier.weight(1f))
        }
    }
    if (accountVisible) AlertDialog(onDismissRequest = { accountVisible = false }, title = { Text("Your You-Tube") }, text = {
        Column {
            Text(if (feed.signedIn) "YouTube session connected" else "Watching as a guest")
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Background audio", modifier = Modifier.weight(1f))
                Switch(checked = background, onCheckedChange = model::setBackground)
            }
            Text("When enabled, leaving the app or locking the screen switches to audio-only playback.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Text("Independent, unofficial client. Not affiliated with YouTube or Google. Sign-in and features depend on YouTube availability.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        TextButton(onClick = {
            accountVisible = false
            if (feed.signedIn) model.signOut() else login.launch(Intent(context, LoginActivity::class.java))
        }) { Text(if (feed.signedIn) "Sign out" else "Sign in") }
    }, dismissButton = { TextButton(onClick = { accountVisible = false }) { Text("Done") } })
}
