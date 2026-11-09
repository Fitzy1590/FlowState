package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.data.Catalog
import com.fitzy.flowstate.data.ai.Summarizer
import com.fitzy.flowstate.data.local.LibraryStatus
import com.fitzy.flowstate.data.tmdb.*
import com.fitzy.flowstate.data.youtube.YoutubeClient
import com.fitzy.flowstate.domain.*
import com.fitzy.flowstate.links.LinkLauncher
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class EpisodesPresenterTest {
    private val clock = Clock()
    private val repo = newRepo(clock)
    private val show = Candidate(TitleId(MediaType.SHOW, 4613), "Band of Brothers", 2001, setOf(18))
    private val opened = mutableListOf<String>()
    private val launcher = LinkLauncher { opened += it; true }

    private class FakeSummarizer(override var available: Boolean = true) : Summarizer {
        val episodeCalls = mutableListOf<Int>()
        var catchUpInput: List<String>? = null
        override suspend fun episode(show: String, ep: EpisodeInfo): String? {
            episodeCalls += ep.number
            return if (ep.overview.isBlank()) null else "Summary of ${ep.name}"
        }
        override suspend fun catchUp(show: String, season: Int, episodeSummaries: List<String>): String? {
            catchUpInput = episodeSummaries
            return "Catch-up: ${episodeSummaries.joinToString(" | ")}"
        }
    }

    private val seasons = listOf(SeasonInfo(1, 3, "S1"), SeasonInfo(2, 2, "S2"))
    private val catalog = object : Catalog {
        override suspend fun pool() = emptyList<Candidate>()
        override suspend fun details(id: TitleId) = TmdbDetails(TmdbTitle(id, show.name, 2001, null, emptySet(), 8.0), null, emptyList(), emptySet(), null, seasons = seasons)
        override suspend fun seasonEpisodes(show: TitleId, season: Int) = when (season) {
            1 -> listOf(EpisodeInfo(1, 1, "Currahee", "Recruits train.", null), EpisodeInfo(1, 2, "Day of Days", "D-Day.", null), EpisodeInfo(1, 3, "Carentan", "", null))
            else -> listOf(EpisodeInfo(2, 1, "Next", "Later events.", null), EpisodeInfo(2, 2, "Last", "End.", null))
        }
        override suspend fun seasonTrailerKey(show: TitleId, season: Int) = if (season == 2) "s2trailer" else null
    }

    private fun youtube(key: String? = null, id: String? = "recapId") = YoutubeClient(
        HttpClient(MockEngine { respond("""{"items":[{"id":{"videoId":"$id"}}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }).withTmdbJson(),
        { key },
    )

    private fun presenter(sum: Summarizer = FakeSummarizer(), yt: YoutubeClient = youtube()) =
        EpisodesPresenter(show, repo, catalog, sum, yt, launcher, CoroutineScope(Dispatchers.Unconfined)).also { it.load() }

    @Test fun opensFirstUnfinishedSeasonWithEpisodesUnwatched() {
        val s = presenter().state.value
        assertEquals(1, s.selected)
        assertEquals(3, s.rows.size)
        assertTrue(s.rows.none { it.watched })
        assertEquals(1, s.plan.trailerSeason)
    }

    @Test fun markingEpisodesWatchedUpdatesLibraryProgress() {
        val p = presenter()
        p.toggleWatched(1, 1)
        assertTrue(show.id in repo.libraryIds())
        assertEquals(1, repo.continueWatching()?.let { 1 })
        assertEquals(0.2, repo.continueWatching()!!.completion, 1e-9) // 1 of 5 episodes
        listOf(2, 3).forEach { p.toggleWatched(1, it) }
        listOf(1, 2).forEach { p.toggleWatched(2, it) }
        assertNull(repo.continueWatching()) // completed, no longer "in progress"
        p.toggleWatched(2, 2) // unwatch
        assertEquals(LibraryStatus.WATCHING.name, repo.libraryStatus(show.id))
    }

    @Test fun finishingSeasonOneOffersSeasonTwoTrailerAndSeasonOneRecap() {
        val p = presenter()
        (1..3).forEach { p.toggleWatched(1, it) }
        val plan = p.state.value.plan
        assertEquals(2, plan.trailerSeason)
        assertEquals(1, plan.recapSeason)
        p.openSeasonTrailer(2)
        assertEquals("https://www.youtube.com/watch?v=s2trailer", opened.last())
    }

    @Test fun seasonTrailerMissingShowsMessage() {
        val p = presenter()
        p.openSeasonTrailer(1)
        assertTrue(opened.isEmpty())
        assertNotNull(p.state.value.message)
    }

    @Test fun summaryIsRefusedForUnwatchedEpisodes() {
        val sum = FakeSummarizer()
        val p = presenter(sum)
        p.requestSummary(1, 1)
        assertTrue(sum.episodeCalls.isEmpty())
        assertNull(p.state.value.rows[0].summary)
    }

    @Test fun summaryForWatchedEpisodeIsMadeOnceAndCached() {
        val sum = FakeSummarizer()
        val p = presenter(sum)
        p.toggleWatched(1, 1)
        p.requestSummary(1, 1)
        assertEquals("Summary of Currahee", p.state.value.rows[0].summary)
        assertEquals(listOf(1), sum.episodeCalls)
        p.requestSummary(1, 1)
        assertEquals(listOf(1), sum.episodeCalls) // not asked twice
        // a new presenter (new launch) reads it from the database
        val again = presenter(FakeSummarizer())
        assertEquals("Summary of Currahee", again.state.value.rows[0].summary)
    }

    @Test fun unwatchingHidesTheSummaryAgain() {
        val p = presenter()
        p.toggleWatched(1, 1); p.requestSummary(1, 1)
        p.toggleWatched(1, 1)
        assertNull(p.state.value.rows[0].summary)
    }

    @Test fun withoutClaudeKeyExplainsAndMakesNoCall() {
        val sum = FakeSummarizer(available = false)
        val p = presenter(sum)
        p.toggleWatched(1, 1); p.requestSummary(1, 1)
        assertTrue(sum.episodeCalls.isEmpty())
        assertTrue("Claude" in p.state.value.message!!)
        assertFalse(p.state.value.aiAvailable)
    }

    @Test fun catchUpUsesOnlyWatchedEpisodes() {
        val sum = FakeSummarizer()
        val p = presenter(sum)
        p.toggleWatched(1, 1); p.toggleWatched(1, 2) // episode 3 stays unwatched
        p.catchUp()
        assertEquals(listOf("Summary of Currahee", "Summary of Day of Days"), sum.catchUpInput)
        assertTrue(p.state.value.catchUp!!.startsWith("Catch-up:"))
        assertFalse(3 in sum.episodeCalls)
        assertFalse(p.state.value.catchUpBusy)
    }

    @Test fun togglingClearsStaleCatchUp() {
        val p = presenter()
        p.toggleWatched(1, 1); p.catchUp()
        assertNotNull(p.state.value.catchUp)
        p.toggleWatched(1, 2)
        assertNull(p.state.value.catchUp)
    }

    /** The mock HTTP engine answers on another thread, so wait for the link to be opened. */
    private fun awaitOpened(count: Int) = runBlocking {
        withTimeout(5_000) { while (opened.size < count) delay(10) }
    }

    @Test fun recapOpensTopVideoWithKeyElseSearchPage() {
        presenter(yt = youtube(key = "K")).openRecap(1)
        awaitOpened(1)
        assertEquals("https://www.youtube.com/watch?v=recapId", opened.last())
        presenter(yt = youtube(key = null)).openRecap(1)
        awaitOpened(2)
        assertTrue(opened.last().startsWith("https://www.youtube.com/results?search_query="))
        assertTrue("recap" in opened.last())
    }
}
