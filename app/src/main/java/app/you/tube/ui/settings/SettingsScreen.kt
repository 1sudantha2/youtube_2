package app.you.tube.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.you.tube.data.SettingsStore
import app.you.tube.ui.LocalAppContainer
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onOpenLogin: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    val defaultQuality by container.settings.defaultQuality.collectAsState(initialValue = SettingsStore.QUALITY_AUTO)
    val autoplay by container.settings.autoplay.collectAsState(initialValue = true)
    val loggedIn = container.auth.isLoggedIn
    var showQualityDialog by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 12.dp)
        )

        SettingsHeader("Account")
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (loggedIn) "Signed in" else "Not signed in", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (loggedIn) "Subscriptions, likes and comments are enabled."
                    else "Sign in to use subscriptions, likes and comments.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (loggedIn) {
                OutlinedButton(onClick = {
                    container.auth.clearAll()
                }) { Text("Sign out") }
            } else {
                Button(onClick = onOpenLogin) { Text("Sign in") }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SettingsHeader("Playback")
        SettingRow(
            title = "Default quality",
            subtitle = if (defaultQuality == SettingsStore.QUALITY_AUTO) "Auto (up to 1080p)"
            else defaultQuality,
            onClick = { showQualityDialog = true }
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Autoplay next video", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Play the next related video automatically",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = autoplay,
                onCheckedChange = { scope.launch { container.settings.setAutoplay(it) } }
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SettingsHeader("About")
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "You-Tube v1.0.0 — an unofficial, privacy-friendly YouTube client. " +
                    "Video streams are extracted with NewPipeExtractor; feeds and engagement " +
                    "use YouTube's InnerTube API. Not affiliated with YouTube or Google.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.padding(top = 8.dp))
            Text(
                "Performance profile: SurfaceView rendering, 20 MB playback buffer, " +
                    "hardware-bitmap image pipeline, R8 full mode + baseline profiles.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showQualityDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text("Default quality") },
            text = {
                Column {
                    val options = listOf(SettingsStore.QUALITY_AUTO, "1080p", "720p", "480p", "360p")
                    options.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            RadioButton(
                                selected = option == defaultQuality,
                                onClick = {
                                    scope.launch { container.settings.setDefaultQuality(option) }
                                    showQualityDialog = false
                                }
                            )
                            Text(
                                if (option == SettingsStore.QUALITY_AUTO) "Auto (up to 1080p)" else option
                            )
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showQualityDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun SettingsHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
