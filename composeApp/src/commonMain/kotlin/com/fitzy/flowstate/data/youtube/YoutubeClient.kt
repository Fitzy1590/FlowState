package com.fitzy.flowstate.data.youtube

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

@Serializable internal data class SearchResponse(val items: List<SearchItem> = emptyList())
@Serializable internal data class SearchItem(val id: VideoId = VideoId())
@Serializable internal data class VideoId(val videoId: String? = null)

/**
 * YouTube Data API v3 search, used to find a season recap video (SPEC 7c). One search costs 100 quota units.
 * Returns null without a key or on any failure. Shapes follow Google's public docs, not yet run against the live API.
 */
class YoutubeClient(
    private val http: HttpClient,
    private val apiKey: () -> String?,
    private val baseUrl: String = "https://www.googleapis.com/youtube/v3",
) {
    val hasKey: Boolean get() = !apiKey().isNullOrBlank()

    suspend fun firstVideoId(query: String): String? {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val resp = http.get("$baseUrl/search") {
                parameter("part", "snippet")
                parameter("type", "video")
                parameter("maxResults", 1)
                parameter("q", query)
                parameter("key", key)
            }
            if (!resp.status.isSuccess()) null else resp.body<SearchResponse>().items.firstOrNull()?.id?.videoId
        }.getOrNull()
    }
}
