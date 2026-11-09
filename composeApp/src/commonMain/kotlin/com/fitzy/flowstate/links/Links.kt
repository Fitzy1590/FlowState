package com.fitzy.flowstate.links

import com.fitzy.flowstate.domain.MediaType
import io.ktor.http.encodeURLParameter

/** Opens a URI in another app or the browser. Returns false when nothing could handle it. */
fun interface LinkLauncher {
    fun open(uri: String): Boolean
}

/** Deep links into the player apps (SPEC 3a, 3b). Both need an IMDb id. */
object DeepLinks {
    private fun MediaType.stremioType() = if (this == MediaType.MOVIE) "movie" else "series"

    /** Stremio detail page; an episode adds `{imdbId}:{season}:{episode}` as the video id. URL format not yet checked on a device. */
    fun stremio(type: MediaType, imdbId: String, season: Int? = null, episode: Int? = null): String {
        val base = "stremio:///detail/${type.stremioType()}/$imdbId"
        return if (type == MediaType.SHOW && season != null && episode != null) "$base/$imdbId:$season:$episode" else base
    }

    /** Matches Nuvio's own `buildMetaDeepLinkUrl` in its source: `nuvio://meta?type=&id=`. Opens the title page. */
    fun nuvio(type: MediaType, imdbId: String): String =
        "nuvio://meta?type=${type.stremioType().encodeURLParameter()}&id=${imdbId.trim().encodeURLParameter()}"
}

/** YouTube links for trailers (SPEC 7a). */
object TrailerLinks {
    fun watch(youtubeKey: String) = "https://www.youtube.com/watch?v=$youtubeKey"

    /** Fallback when TMDB has no trailer: a search page, which cannot autoplay. */
    fun search(name: String, year: Int?): String {
        val q = listOfNotNull(name, year?.toString(), "official trailer").joinToString(" ")
        return "https://www.youtube.com/results?search_query=${q.encodeURLParameter()}"
    }
}

/** Parses the rating box text. Returns null unless it is a whole number from 0 to 100. */
fun parseScore(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 0..100 }
