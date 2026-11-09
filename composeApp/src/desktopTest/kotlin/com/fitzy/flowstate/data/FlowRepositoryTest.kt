package com.fitzy.flowstate.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.fitzy.flowstate.data.local.ActorPref
import com.fitzy.flowstate.data.local.FlowRepository
import com.fitzy.flowstate.data.local.LibraryStatus
import com.fitzy.flowstate.data.tmdb.TmdbTitle
import com.fitzy.flowstate.db.FlowDatabase
import com.fitzy.flowstate.domain.*
import com.fitzy.flowstate.reco.PromptState
import kotlin.test.*

class FlowRepositoryTest {
    private var now = 1_000L
    private fun repo(): FlowRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        FlowDatabase.Schema.create(driver)
        return FlowRepository(FlowDatabase(driver)) { now }
    }

    private val id = TitleId(MediaType.MOVIE, 857)
    private val title = TmdbTitle(id, "Saving Private Ryan", 1998, "/a.jpg", setOf(18, 10752), 8.2)
    private val features = TitleFeatures(setOf(18, 10752), setOf(7), listOf(1L, 2L))

    @Test fun ratingRoundTripsAndRejectsOutOfRange() {
        val r = repo()
        assertNull(r.rating(id))
        r.setRating(id, 87)
        assertEquals(87, r.rating(id))
        r.setRating(id, 0); assertEquals(0, r.rating(id))
        assertFailsWith<IllegalArgumentException> { r.setRating(id, 101) }
        assertFailsWith<IllegalArgumentException> { r.setRating(id, -1) }
    }

    @Test fun libraryAddIsIdempotentAndPlannedIsNotHistory() {
        val r = repo()
        r.addToLibrary(title); r.addToLibrary(title)
        assertEquals(setOf(id), r.libraryIds())
        assertTrue(r.profile().excluded.isEmpty()) // planned only
        r.setProgress(id, LibraryStatus.COMPLETED, 1.0)
        assertEquals(setOf(id), r.profile().excluded)
    }

    @Test fun profileLearnsFromRatedCompletedTitles() {
        val r = repo()
        r.addToLibrary(title); r.setProgress(id, LibraryStatus.COMPLETED, 1.0); r.setRating(id, 90)
        val p = r.profile()
        assertTrue(p.genreAffinity.getValue(10752) > 0.5)
        assertEquals(setOf(id), p.likedSeeds)
    }

    @Test fun droppedEarlyCountsAsAbandoned() {
        val r = repo()
        r.addToLibrary(title); r.setProgress(id, LibraryStatus.DROPPED, 0.1)
        assertTrue(r.profile().genreCompletion.getValue(10752) < 0.5)
    }

    @Test fun voteReplacesPreviousAndRestoresFeatures() {
        val r = repo()
        r.castVote(id, Vote.NEVER, VoteSource.PROMPT, features)
        now = 2_000
        r.castVote(id, Vote.YES, VoteSource.SWIPE, features)
        val v = r.votes().single()
        assertEquals(Vote.YES, v.vote.vote)
        assertEquals(2_000L, v.vote.atMillis)
        assertEquals(features, v.features)
        assertTrue(id !in r.profile().excluded)
    }

    @Test fun neverVoteExcludesTitle() {
        val r = repo()
        r.castVote(id, Vote.NEVER, VoteSource.SWIPE, features)
        assertTrue(id in r.profile().excluded)
    }

    @Test fun quickAddAddsLibraryAndYesButKeepsExistingVote() {
        val r = repo()
        r.quickAdd(title, features)
        assertEquals(setOf(id), r.libraryIds())
        assertEquals(Vote.YES, r.voteFor(id)?.vote)

        val other = title.copy(id = TitleId(MediaType.MOVIE, 1))
        r.castVote(other.id, Vote.MAYBE, VoteSource.PROMPT, features)
        r.quickAdd(other, features)
        assertEquals(Vote.MAYBE, r.voteFor(other.id)?.vote)
    }

    @Test fun actorPrefsSetSwitchAndClear() {
        val r = repo()
        r.setActorPref(1, "A", ActorPref.LIKED)
        r.setActorPref(2, "B", ActorPref.DISLIKED)
        assertEquals(ActorPrefs(setOf(1L), setOf(2L)), r.actorPrefs())
        r.setActorPref(1, "A", ActorPref.DISLIKED)
        assertEquals(ActorPrefs(emptySet(), setOf(1L, 2L)), r.actorPrefs())
        r.setActorPref(2, "B", null)
        assertEquals(ActorPrefs(emptySet(), setOf(1L)), r.actorPrefs())
    }

    @Test fun promptStatePersists() {
        val r = repo()
        assertEquals(PromptState(), r.promptState())
        r.savePromptState(PromptState(lastShownAt = 5, consecutiveDismissals = 2, backoffUntil = 9))
        assertEquals(PromptState(5, 2, 9), r.promptState())
    }
}
