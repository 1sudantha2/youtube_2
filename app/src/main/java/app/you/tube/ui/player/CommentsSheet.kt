package app.you.tube.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.you.tube.core.model.CommentItem
import app.you.tube.player.PlayerViewModel
import app.you.tube.ui.components.AvatarImage
import app.you.tube.ui.components.shimmer
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Comments bottom sheet: cursor pagination, per-comment like actions,
 * inline replies (loaded lazily via reply continuation tokens), sort
 * switching (Top / Newest) and comment posting — all through the verified
 * InnerTube comment endpoints.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(
    vm: PlayerViewModel,
    onDismiss: () -> Unit,
    onSignIn: () -> Unit
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val comments = uiState.comments

    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 3 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd) { if (nearEnd) vm.loadMoreComments() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .imePadding()
        ) {
            Text(
                "Comments",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 18.dp, bottom = 4.dp)
            )
            if (comments.sorts.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(comments.sorts, key = { it.token }) { sort ->
                        FilterChip(
                            selected = sort.selected,
                            onClick = { vm.changeCommentSort(sort) },
                            label = { Text(sort.title) }
                        )
                    }
                }
            }

            when {
                comments.isLoading && comments.items.isEmpty() -> Column(
                    Modifier.fillMaxWidth().padding(top = 10.dp)
                ) {
                    repeat(4) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Box(Modifier.size(32.dp).shimmer(16.dp))
                            Column(Modifier.padding(start = 10.dp)) {
                                Box(Modifier.fillMaxWidth(0.6f).height(12.dp).shimmer(4.dp))
                                Spacer(Modifier.size(6.dp))
                                Box(Modifier.fillMaxWidth(0.9f).height(12.dp).shimmer(4.dp))
                            }
                        }
                    }
                }

                comments.error != null && comments.items.isEmpty() -> Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        comments.error ?: "Comments unavailable",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                comments.items.isEmpty() -> Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No comments yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp)
                ) {
                    items(
                        items = comments.items,
                        key = { it.id },
                        contentType = { "comment" }
                    ) { comment ->
                        CommentRow(
                            comment = comment,
                            replies = comments.replies[comment.id],
                            loadingReplies = comments.loadingRepliesFor == comment.id,
                            onLike = { vm.toggleCommentLike(comment) },
                            onLoadReplies = { vm.loadReplies(comment) }
                        )
                    }
                    if (comments.isLoading && comments.items.isNotEmpty()) {
                        item(key = "comments-loader", contentType = "loader") {
                            Box(
                                Modifier.fillMaxWidth().padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) { CircularProgressIndicator(Modifier.size(20.dp)) }
                        }
                    }
                }
            }

            CommentComposer(
                posting = comments.posting,
                enabled = true,
                onPost = { vm.postComment(it) },
                onSignInNeeded = onSignIn
            )
        }
    }
}

@Composable
private fun CommentRow(
    comment: CommentItem,
    replies: ImmutableList<CommentItem>?,
    loadingReplies: Boolean,
    onLike: () -> Unit,
    onLoadReplies: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row {
            AvatarImage(
                url = comment.avatarUrl,
                contentDescription = comment.authorName,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        comment.authorName.ifBlank { "Unknown" },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    comment.publishedTime?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (comment.isHearted) {
                    Text(
                        "❤ by creator",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp
                    )
                }
                Text(
                    comment.text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 3.dp)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 5.dp)
                ) {
                    IconButton(onClick = onLike, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.ThumbUp,
                            contentDescription = "Like comment",
                            tint = if (comment.isLiked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    comment.likeCount?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (comment.replyCount > 0 && comment.repliesToken != null) {
                        Spacer(Modifier.width(12.dp))
                        TextButton(onClick = onLoadReplies) {
                            Text(
                                if (loadingReplies) "Loading…" else "${comment.replyCount} replies",
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                if (loadingReplies) {
                    CircularProgressIndicator(
                        Modifier.padding(start = 8.dp, top = 4.dp).size(14.dp),
                        strokeWidth = 1.5.dp
                    )
                }
                replies?.forEach { reply ->
                    Row(Modifier.padding(top = 6.dp)) {
                        AvatarImage(
                            url = reply.avatarUrl,
                            contentDescription = reply.authorName,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                reply.authorName.ifBlank { "Unknown" },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                reply.text,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentComposer(
    posting: Boolean,
    enabled: Boolean,
    onPost: (String) -> Unit,
    onSignInNeeded: () -> Unit
) {
    val loggedIn = app.you.tube.ui.LocalAppContainer.current.auth.isLoggedIn
    var text by remember { mutableStateOf("") }

    if (!loggedIn) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Sign in to join the conversation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = onSignInNeeded) { Text("Sign in") }
        }
        return
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Add a comment…", fontSize = 13.sp) },
            singleLine = false,
            maxLines = 3,
            shape = RoundedCornerShape(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        if (posting) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            IconButton(
                onClick = {
                    onPost(text)
                    text = ""
                },
                enabled = enabled && text.isNotBlank()
            ) {
                Icon(Icons.Filled.Send, contentDescription = "Post comment")
            }
        }
    }
}
