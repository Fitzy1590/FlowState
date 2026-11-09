package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.domain.*
import com.fitzy.flowstate.reco.Recommender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.*

class DiscoverPresenterTest {
    private val clock = Clock()
    private val repo = newRepo(clock)
    private val pool = (1L..4).map { cand(it, 18) }

    private fun presenter() = DiscoverPresenter(repo, FakeCatalog(pool), Recommender(2026), CoroutineScope(Dispatchers.Unconfined))
        .also { it.load() }

    @Test fun loadsDeckAndShowsFirstCard() {
        val p = presenter()
        assertEquals(4, p.state.value.remaining)
        assertNotNull(p.state.value.current)
        assertNotNull(p.state.value.next)
        assertFalse(p.state.value.canUndo)
    }

    @Test fun yesStoresSwipeVoteAndAdvances() {
        val p = presenter()
        val first = p.state.value.current!!
        p.vote(Vote.YES)
        assertEquals(3, p.state.value.remaining)
        assertNotEquals(first.id, p.state.value.current?.id)
        val v = repo.voteFor(first.id)!!
        assertEquals(Vote.YES, v.vote)
        assertEquals(VoteSource.SWIPE, v.source)
    }

    @Test fun neverExcludesTitleFromFutureDecks() {
        val p = presenter()
        val first = p.state.value.current!!
        p.vote(Vote.NEVER)
        val reloaded = presenter()
        assertEquals(3, reloaded.state.value.remaining)
        assertTrue(reloaded.state.value.current!!.id != first.id)
    }

    @Test fun addToLibraryAddsAndCountsAsYes() {
        val p = presenter()
        val first = p.state.value.current!!
        p.addToLibrary()
        assertTrue(first.id in repo.libraryIds())
        assertEquals(Vote.YES, repo.voteFor(first.id)?.vote)
        assertEquals(3, p.state.value.remaining)
    }

    @Test fun undoRestoresVoteAndRemovesAddedTitle() {
        val p = presenter()
        val first = p.state.value.current!!
        p.addToLibrary()
        assertTrue(p.state.value.canUndo)
        p.undo()
        assertEquals(first.id, p.state.value.current?.id)
        assertEquals(4, p.state.value.remaining)
        assertNull(repo.voteFor(first.id))
        assertFalse(first.id in repo.libraryIds())
        assertFalse(p.state.value.canUndo)
    }

    @Test fun undoRestoresEarlierVoteNotJustDeletes() {
        val p = presenter()
        val first = p.state.value.current!!
        repo.castVote(first.id, Vote.MAYBE, VoteSource.PROMPT, first.features())
        p.vote(Vote.NEVER)
        p.undo()
        val v = repo.voteFor(first.id)!!
        assertEquals(Vote.MAYBE, v.vote)
        assertEquals(VoteSource.PROMPT, v.source)
    }

    @Test fun emptyDeckIsSafe() {
        val p = DiscoverPresenter(repo, FakeCatalog(emptyList()), Recommender(2026), CoroutineScope(Dispatchers.Unconfined)).also { it.load() }
        assertNull(p.state.value.current)
        p.vote(Vote.YES); p.addToLibrary(); p.undo()
        assertEquals(0, p.state.value.remaining)
        assertFalse(p.state.value.loading)
    }
}
