package com.ultra.youtube.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ultra.youtube.app.domain.Comment
import com.ultra.youtube.app.ui.theme.AppColors

/**
 * Comments list + composer.
 *
 * Rendered inside the watch screen's `verticalScroll`, so this is a plain `Column` rather
 * than a `LazyColumn`: nesting two scrolling lazy lists forces the inner one to measure
 * every child, which is the worst case for both memory and frame time. Comment pages are
 * ~20 items, so a `Column` is both cheaper and correct here.
 */
@Composable
fun CommentList(
    comments: List<Comment>,
    isLoading: Boolean,
    onPost: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Comments",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Post comment…") },
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = {
                    if (draft.isNotBlank()) {
                        onPost(draft)
                        draft = ""
                    }
                },
                enabled = draft.isNotBlank() && !isLoading,
            ) { Text("Post") }
        }

        Spacer(Modifier.height(8.dp))

        comments.forEach { comment ->
            CommentRow(comment)
        }

        if (isLoading) {
            Text(
                text = "Loading…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun CommentRow(comment: Comment, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(AppColors.SurfaceDarkElevated),
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = comment.author,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                if (comment.timeText.isNotBlank()) {
                    Text(
                        text = "  ${comment.timeText}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = comment.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (comment.likeCountText.isNotBlank() || comment.replyCountText.isNotBlank()) {
                Text(
                    text = listOf(comment.likeCountText, comment.replyCountText)
                        .filter { it.isNotBlank() }
                        .joinToString(" • "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { /* expand replies */ },
                )
            }
        }
    }
}
