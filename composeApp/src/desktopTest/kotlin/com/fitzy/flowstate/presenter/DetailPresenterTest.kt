package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.data.Catalog
import com.fitzy.flowstate.data.local.ActorPref
import com.fitzy.flowstate.data.tmdb.CastMember
import com.fitzy.flowstate.data.tmdb.EpisodeInfo
import com.fitzy.flowstate.data.tmdb.SeasonInfo
import com.fitzy.flowstate.data.tmdb.TmdbDetails
import com.fitzy.flowstate.data.tmdb.TmdbTitle
import com.fitzy.flowstate.domain.*
import com.fitzy.flowstate.data.tmdb.withTmdbJson
import com.fitzy.flowstate.links.LinkLauncher
import com.fitzy.flowstate.ratings.OmdbClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.*

class DetailPresenterTest {
    private val clock = Clock()
    private val repo = newRepo(clock)
    private val c = Candidate(TitleId(MediaType.SHOW, 4613), "Band of Brothers", 2001, setOf(18, 10768), voteAverage = 8.5)
    private val opened = mutableListOf<String>()
    private var canOpen = true
    private val launcher = LinkLauncher { opened += it; canOpen }

    private val cast = listOf(CastMember(1, "Damian Lewis", "Winters"), CastMember(2, "Ron Livingston", "Nixon"))
    private val seasons = listOf(SeasonInfo(1, 3, "S1"), SeasonInfo(2, 2, "S2"))

    private fun catalog(imdb: String? = "tt0185906", trailer: String? = "yt1") = object : Catalog {
        override suspend fun related(id: TitleId) = listOf(Candidate(TitleId(MediaType.SHOW, 77), "Related", 2010, setOf(18)), c)
        override suspend fun pool() = emptyList<Candidate>()
        override suspend fun seasonEpisodes(show: TitleId, season: Int) = emptyList<EpisodeInfo>()
        override suspend fun seasonTrailerKey(show: TitleId, season: Int): String? = null
        override suspend fun details(id: TitleId) = TmdbDetails(
            TmdbTitle(id, c.name, c.year, null, c.genres, 8.5), imdb, emptyList(), emptySet(), trailer, "Easy Company.",
            seasons = seasons, castMembers = cast,
        )
    }

    private fun presenter(cat: Catalog = catalog()) =
        DetailPresenter(c, repo, cat, launcher, null, CoroutineScope(Dispatchers.Unconfined), clock).also { it.load() }

    @Test fun loadsOverviewImdbAndTrailer() {
        val s = presenter().state.value
        assertEquals("Easy Company.", s.overview)
        assertEquals("tt0185906", s.imdbId)
        assertEquals("yt1", s.trailerKey)
        assertFalse(s.loading)
    }

    @Test fun ratingIsStoredAndShown() {
        val p = presenter()
        assertNull(p.state.value.userScore)
        assertTrue(p.rate(87))
        assertEquals(87, p.state.value.userScore)
        assertEquals(87, repo.rating(c.id))
        assertFalse(p.rate(101))
        assertEquals(87, p.state.value.userScore)
    }

    @Test fun addToLibraryCountsAsYesAndDisablesButton() {
        val p = presenter()
        p.addToLibrary()
        assertTrue(p.state.value.inLibrary)
        assertEquals(Vote.YES, p.state.value.vote)
    }

    @Test fun playOpensStremioAndNuvioLinks() {
        val p = presenter()
        p.play(Player.STREMIO); p.play(Player.NUVIO)
        // a show with nothing watched opens its first episode in Stremio; Nuvio only opens the title page
        assertEquals(listOf("stremio:///detail/series/tt0185906/tt0185906:1:1", "nuvio://meta?type=series&id=tt0185906"), opened)
        assertNull(p.state.value.message)
    }

    @Test fun playWithoutImdbIdExplainsInsteadOfOpening() {
        val p = presenter(catalog(imdb = null))
        p.play(Player.STREMIO)
        assertTrue(opened.isEmpty())
        assertNotNull(p.state.value.message)
    }

    @Test fun playWhenAppMissingShowsMessage() {
        canOpen = false
        val p = presenter()
        p.play(Player.NUVIO)
        assertTrue("Nuvio" in p.state.value.message!!)
        p.dismissMessage()
        assertNull(p.state.value.message)
    }

    @Test fun trailerUsesTmdbKeyElseSearchLink() {
        presenter().watchTrailer()
        assertEquals("https://www.youtube.com/watch?v=yt1", opened.last())
        presenter(catalog(trailer = null)).watchTrailer()
        assertTrue(opened.last().startsWith("https://www.youtube.com/results"))
    }

    @Test fun detailsFailureStillShowsBasics() {
        val failing = object : Catalog {
            override suspend fun pool() = emptyList<Candidate>()
            override suspend fun seasonEpisodes(show: TitleId, season: Int) = emptyList<EpisodeInfo>()
            override suspend fun seasonTrailerKey(show: TitleId, season: Int): String? = null
            override suspend fun details(id: TitleId): TmdbDetails? = error("network down")
        }
        val s = presenter(failing).state.value
        assertFalse(s.loading)
        assertEquals("", s.overview)
        assertEquals("Band of Brothers", s.candidate.name)
    }

    @Test fun playUsesTheNextUnwatchedEpisodeAndTheDefaultPlayer() {
        val p = presenter()
        assertEquals(1 to 1, p.state.value.nextEpisode)
        listOf(1, 2, 3).forEach { repo.setEpisodeWatched(c.id, 1, it, true) }
        repo.setEpisodeWatched(c.id, 2, 1, true)
        p.refresh()
        assertEquals(2 to 2, p.state.value.nextEpisode)
        p.play()
        assertEquals("stremio:///detail/series/tt0185906/tt0185906:2:2", opened.last())
        p.setDefaultPlayer(Player.NUVIO)
        assertEquals(Player.NUVIO, p.state.value.defaultPlayer)
        p.play()
        assertEquals("nuvio://meta?type=series&id=tt0185906", opened.last())
    }

    @Test fun fullyWatchedShowHasNoNextEpisodeAndOpensTheTitle() {
        listOf(1 to 3, 2 to 2).forEach { (s, n) -> (1..n).forEach { repo.setEpisodeWatched(c.id, s, it, true) } }
        val p = presenter()
        assertNull(p.state.value.nextEpisode)
        p.play()
        assertEquals("stremio:///detail/series/tt0185906", opened.last())
    }

    @Test fun castAndSimilarLoadAndSimilarHidesTheTitleItself() {
        val s = presenter().state.value
        assertEquals(listOf("Damian Lewis", "Ron Livingston"), s.cast.map { it.name })
        assertTrue(s.similar.any { it.name == "Related" })
    }

    @Test fun actorChoicesToggleAndFeedTheProfile() {
        val p = presenter()
        p.setActor(cast[0], ActorPref.LIKED)
        assertEquals(setOf(1L), p.state.value.likedActors)
        assertEquals(setOf(1L), repo.actorPrefs().liked)
        p.setActor(cast[0], ActorPref.DISLIKED) // switch
        assertEquals(setOf(1L), p.state.value.dislikedActors); assertTrue(p.state.value.likedActors.isEmpty())
        p.setActor(cast[0], ActorPref.DISLIKED) // same again clears
        assertTrue(p.state.value.dislikedActors.isEmpty())
        assertEquals(ActorPrefs(), repo.actorPrefs())
    }

    @Test fun externalRatingsAreCachedForAWeek() {
        val omdbCalls = mutableListOf<String>()
        val http = HttpClient(MockEngine {
            omdbCalls += it.url.toString()
            respond(
                """{"Response":"True","imdbRating":"9.4","Ratings":[{"Source":"Internet Movie Database","Value":"9.4/10"},{"Source":"Rotten Tomatoes","Value":"96%"}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }).withTmdbJson()
        val client = OmdbClient(http, apiKey = { "K" })
        fun make() = DetailPresenter(c, repo, catalog(), launcher, client, CoroutineScope(Dispatchers.Unconfined), clock).also { it.load() }
        val first = make()
        runBlocking { withTimeout(5_000) { while (first.state.value.ratings == null) delay(10) } }
        assertEquals(9.4, first.state.value.ratings!!.imdb)
        assertEquals(96, first.state.value.ratings!!.rottenTomatoes)
        val calls = omdbCalls.size
        assertEquals(96, make().state.value.ratings?.rottenTomatoes) // served from the cache
        assertEquals(calls, omdbCalls.size)
        clock.now += 8L * 24 * 60 * 60 * 1000 // a week later the score is fetched again
        val later = make()
        runBlocking { withTimeout(5_000) { while (omdbCalls.size == calls) delay(10) } }
        assertEquals(calls + 1, omdbCalls.size)
    }
}
