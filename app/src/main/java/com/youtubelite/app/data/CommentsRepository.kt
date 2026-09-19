package com.youtubelite.app.data

import com.youtubelite.app.innertube.Innertube
import com.youtubelite.app.innertube.YtParsers
import com.youtubelite.app.model.Comment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CommentsPage(val comments: List<Comment>, val nextToken: String?)

object CommentsRepository {

    suspend fun firstPage(videoId: String): CommentsPage = withContext(Dispatchers.IO) {
        val root = Innertube.next(videoId = videoId)
        val token = YtParsers.findCommentToken(root)
            ?: return@withContext CommentsPage(emptyList(), null)
        loadToken(token)
    }

    suspend fun nextPage(token: String): CommentsPage = withContext(Dispatchers.IO) {
        loadToken(token)
    }

    private suspend fun loadToken(token: String): CommentsPage {
        val root = Innertube.next(continuation = token)
        val (comments, next) = YtParsers.parseCommentsPage(root)
        return CommentsPage(comments, next)
    }
}
