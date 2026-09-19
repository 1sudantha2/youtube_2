package com.youtubelite.app.data

import com.youtubelite.app.AppGraph
import com.youtubelite.app.innertube.Innertube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NotSignedInException : IllegalStateException("Sign in to use this feature")

/** User actions — every call requires the authenticated session. */
object ActionsRepository {

    private fun requireSession() {
        if (!AppGraph.auth.isSignedIn) throw NotSignedInException()
    }

    suspend fun like(videoId: String) = withContext(Dispatchers.IO) {
        requireSession(); Innertube.like(videoId)
    }

    suspend fun dislike(videoId: String) = withContext(Dispatchers.IO) {
        requireSession(); Innertube.dislike(videoId)
    }

    suspend fun removeLike(videoId: String) = withContext(Dispatchers.IO) {
        requireSession(); Innertube.removeLike(videoId)
    }

    suspend fun subscribe(channelId: String) = withContext(Dispatchers.IO) {
        requireSession(); Innertube.subscribe(channelId)
    }

    suspend fun unsubscribe(channelId: String) = withContext(Dispatchers.IO) {
        requireSession(); Innertube.unsubscribe(channelId)
    }

    suspend fun createComment(videoId: String, channelId: String, text: String) =
        withContext(Dispatchers.IO) {
            requireSession(); Innertube.createComment(videoId, channelId, text)
        }
}
