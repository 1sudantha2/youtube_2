package com.youtubelite.app.data

import com.youtubelite.app.innertube.Innertube
import com.youtubelite.app.innertube.YtParsers
import com.youtubelite.app.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Page(val videos: List<Video>, val nextToken: String?)

object FeedRepository {

    suspend fun browse(browseId: String, continuation: String? = null): Page =
        withContext(Dispatchers.IO) {
            val root = Innertube.browse(browseId, continuation)
            val (videos, token) = YtParsers.parseVideos(root)
            Page(videos, token)
        }

    suspend fun search(query: String, continuation: String? = null): Page =
        withContext(Dispatchers.IO) {
            val root = Innertube.search(query, continuation)
            val (videos, token) = YtParsers.parseVideos(root)
            Page(videos, token)
        }
}
