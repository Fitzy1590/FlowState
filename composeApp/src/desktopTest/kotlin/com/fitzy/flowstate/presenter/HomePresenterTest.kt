package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.data.local.LibraryStatus
import com.fitzy.flowstate.data.tmdb.TmdbTitle
import com.fitzy.flowstate.domain.*
import com.fitzy.flowstate.reco.InterestPrompter
import com.fitzy.flowstate.reco.Recommender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.*

class HomePresenterTest {
    private val clock = Clock()
    private val repo = newRepo(clock)
    private val pool = (1L..5).map { cand(it, 18) }
    private val day = InterestPrompter.DAY_MS

    private fun presenter() = HomePresenter(
        repo, FakeCatalog(pool), Recommender(2026), InterestPrompter(), CoroutineScope(Dispatchers.Unconfined), clock,
    ).also { it.load() }

    @Test fun firstLaunchShowsPromptAndRecommendations() {
        val p = presenter()
        assertNotNull(p.state.value.prompt)
        assertEquals(5, p.state.value.recommended.size)
        assertFalse(p.state.value.loading)
    }

    @Test fun promptIsNotShownAgainWithin24HoursEvenAfterRestart() {
        presenter()
        clock.now += day / 2
        assertNull(presenter().state.value.prompt) // new presenter = new launch, but under 24h
        clock.now += day
        assertNotNull(presenter().state.value.prompt)
    }

    @Test fun answeringStoresPromptVoteAndClearsCard() {
        val p = presenter()
        val shown = p.state.value.prompt!!
        p.answerPrompt(Vote.NEVER)
        assertNull(p.state.value.prompt)
        val v = repo.voteFor(shown.id)!!
        assertEquals(Vote.NEVER, v.vote)
        assertEquals(VoteSource.PROMPT, v.source)
    }

    @Test fun threeDismissalsBackOffAndPersist() {
        repeat(3) {
            val p = presenter()
            assertNotNull(p.state.value.prompt, "round $it")
            p.dismissPrompt()
            clock.now += day + 1
        }
        assertNull(presenter().state.value.prompt) // inside the 7-day back-off
        clock.now += 7 * day
        assertNotNull(presenter().state.value.prompt)
    }

    @Test fun heroIsMostRecentTitleInProgress() {
        val a = TmdbTitle(TitleId(MediaType.SHOW, 1), "Older", 2020, null, setOf(18), 7.0)
        val b = TmdbTitle(TitleId(MediaType.SHOW, 2), "Newer", 2021, null, setOf(18), 7.0)
        repo.addToLibrary(a); repo.setProgress(a.id, LibraryStatus.WATCHING, 0.3)
        clock.now += 1000
        repo.addToLibrary(b); repo.setProgress(b.id, LibraryStatus.WATCHING, 0.6)
        assertEquals("Newer", presenter().state.value.hero?.name)
    }

    @Test fun noHeroWhenNothingInProgress() = assertNull(presenter().state.value.hero)

    @Test fun libraryTitlesAreNotRecommended() {
        repo.quickAdd(pool[0])
        assertTrue(presenter().state.value.recommended.none { it.id == pool[0].id })
    }
}
