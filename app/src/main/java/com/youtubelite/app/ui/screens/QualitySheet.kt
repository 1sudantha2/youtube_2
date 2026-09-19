package com.youtubelite.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.youtubelite.app.AppGraph
import com.youtubelite.app.model.StreamSet
import com.youtubelite.app.player.PlaybackHub
import kotlinx.coroutines.launch

/**
 * Dynamic quality selector. "Auto" lets the service self-demote on network
 * degradation; picking a fixed rung pins it (with muxed fallback on hard
 * playback errors). Picking a muxed row forces the progressive fallback URL.
 */
@Composable
fun QualitySheet(
    streams: StreamSet,
    activeLabel: String?,
    isAuto: Boolean,
    audioOnly: Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    fun persist(label: String) {
        scope.launch { AppGraph.prefs.setDefaultQuality(label) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                text = "Quality",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            QualityRow(label = "Auto", selected = isAuto) {
                PlaybackHub.setAuto()
                persist("auto")
                onDismiss()
            }
            streams.qualities.asReversed().forEach { q ->
                QualityRow(
                    label = q.label + if (q.fps == 60) "  •  60fps" else "",
                    selected = !isAuto && activeLabel == q.label,
                ) {
                    PlaybackHub.setQuality(q.label)
                    persist(q.label)
                    onDismiss()
                }
            }
            if (streams.muxed.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                streams.muxed.asReversed().forEach { q ->
                    QualityRow(
                        label = "${q.label} (muxed fallback)",
                        selected = !isAuto && activeLabel == "${q.label} (fallback)",
                    ) {
                        PlaybackHub.setQuality("${q.label}|muxed")
                        onDismiss()
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            QualityRow(
                label = if (audioOnly) "Audio-only: ON (tap for video)" else "Audio-only",
                selected = audioOnly,
            ) {
                PlaybackHub.setAudioOnly(!audioOnly)
                onDismiss()
            }
        }
    }
}

@Composable
private fun QualityRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Checkbox(
            checked = selected,
            onCheckedChange = { onClick() },
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
        )
    }
}
