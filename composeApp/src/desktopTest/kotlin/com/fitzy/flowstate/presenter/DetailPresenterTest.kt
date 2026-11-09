package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.data.Catalog
import com.fitzy.flowstate.data.tmdb.TmdbDetails
import com.fitzy.flowstate.data.tmdb.TmdbTitle
import com.fitzy.flowstate.domain.*
import com.fitzy.flowstate.links.LinkLauncher
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

    private fun catalog(imdb: String? = "tt0185906", trailer: String? = "yt1") = object : Catalog {
        override suspend fun pool() = emptyList<Candidate>()
        override suspend fun details(id: TitleId) = TmdbDetails(
            TmdbTitle(id, c.name, c.year, null, c.genres, 8.5), imdb, emptyList(), emptySet(), trailer, "Easy Company.",
        )
    }

    private fun presenter(cat: Catalog = catalog()) =
        DetailPresenter(c, repo, cat, launcher, CoroutineScope(Dispatchers.Unconfined)).also { it.load() }

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
        assertEquals(listOf("stremio:///detail/series/tt0185906", "nuvio://meta?type=series&id=tt0185906"), opened)
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
            override suspend fun details(id: TitleId): TmdbDetails? = error("network down")
        }
        val s = presenter(failing).state.value
        assertFalse(s.loading)
        assertEquals("", s.overview)
        assertEquals("Band of Brothers", s.candidate.name)
    }
}
