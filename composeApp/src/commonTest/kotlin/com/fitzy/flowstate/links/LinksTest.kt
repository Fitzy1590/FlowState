package com.fitzy.flowstate.links

import com.fitzy.flowstate.domain.MediaType
import kotlin.test.*

class LinksTest {
    @Test fun stremioMovieAndShowDetail() {
        assertEquals("stremio:///detail/movie/tt0120815", DeepLinks.stremio(MediaType.MOVIE, "tt0120815"))
        assertEquals("stremio:///detail/series/tt0185906", DeepLinks.stremio(MediaType.SHOW, "tt0185906"))
    }

    @Test fun stremioEpisodeUsesImdbSeasonEpisodeVideoId() {
        assertEquals(
            "stremio:///detail/series/tt0185906/tt0185906:1:3",
            DeepLinks.stremio(MediaType.SHOW, "tt0185906", season = 1, episode = 3),
        )
        // an episode makes no sense for a movie
        assertEquals("stremio:///detail/movie/tt1", DeepLinks.stremio(MediaType.MOVIE, "tt1", season = 1, episode = 1))
    }

    @Test fun nuvioMatchesItsMetaLinkFormat() {
        assertEquals("nuvio://meta?type=movie&id=tt0120815", DeepLinks.nuvio(MediaType.MOVIE, "tt0120815"))
        assertEquals("nuvio://meta?type=series&id=tt0185906", DeepLinks.nuvio(MediaType.SHOW, " tt0185906 "))
    }

    @Test fun trailerLinks() {
        assertEquals("https://www.youtube.com/watch?v=abc123", TrailerLinks.watch("abc123"))
        val s = TrailerLinks.search("Band of Brothers", 2001)
        assertTrue(s.startsWith("https://www.youtube.com/results?search_query="), s)
        assertTrue("Band" in s && "2001" in s && "trailer" in s && " " !in s, s)
    }

    @Test fun parseScoreAcceptsOnlyWholeNumbers0To100() {
        assertEquals(0, parseScore("0")); assertEquals(100, parseScore(" 100 ")); assertEquals(87, parseScore("87"))
        listOf("101", "-1", "", "abc", "8.5", "1e2").forEach { assertNull(parseScore(it), it) }
    }
}
