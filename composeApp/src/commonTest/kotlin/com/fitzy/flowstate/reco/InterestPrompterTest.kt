package com.fitzy.flowstate.reco

import com.fitzy.flowstate.domain.*
import kotlin.random.Random
import kotlin.test.*

class InterestPrompterTest {
    private val day = InterestPrompter.DAY_MS
    private val p = InterestPrompter(Random(1))
    private fun id(n: Long) = TitleId(MediaType.MOVIE, n)
    private fun c(n: Long, genres: Set<Int> = setOf(1)) = Candidate(id(n), "t$n", 2020, genres)

    @Test fun showsAtMostOncePerLaunchAndPerDay() {
        val s = PromptState()
        assertTrue(p.canShow(s, now = 10 * day, busy = false))
        val shown = p.onShown(s, 10 * day)
        assertFalse(p.canShow(shown, 10 * day + 1, false)) // same launch
        val nextLaunch = shown.copy(shownThisLaunch = false)
        assertFalse(p.canShow(nextLaunch, 10 * day + day - 1, false)) // under 24h
        assertTrue(p.canShow(nextLaunch, 11 * day, false))
    }

    @Test fun neverShowsWhileBusy() = assertFalse(p.canShow(PromptState(), 10 * day, busy = true))

    @Test fun threeDismissalsBackOffSevenDays() {
        var s = PromptState()
        s = p.onDismissed(s, 10 * day)
        s = p.onDismissed(s, 10 * day)
        assertEquals(2, s.consecutiveDismissals)
        s = p.onDismissed(s, 10 * day)
        assertEquals(0, s.consecutiveDismissals)
        assertEquals(17 * day, s.backoffUntil)
        assertFalse(p.canShow(s, 16 * day, false))
        assertTrue(p.canShow(s, 17 * day, false))
    }

    @Test fun answeringResetsDismissals() =
        assertEquals(0, p.onAnswered(PromptState(consecutiveDismissals = 2)).consecutiveDismissals)

    @Test fun skipsLibraryVotedAndExcludedButRetriesOldMaybe() {
        val profile = UserProfile.Empty.copy(excluded = setOf(id(3)))
        val votes = mapOf(
            id(4) to InterestVote(id(4), Vote.YES, 0, VoteSource.PROMPT),
            id(5) to InterestVote(id(5), Vote.MAYBE, 0, VoteSource.PROMPT), // 40 days old
            id(6) to InterestVote(id(6), Vote.MAYBE, 39 * day, VoteSource.PROMPT), // 1 day old
        )
        val eligible = (1L..6).filter {
            p.pick(listOf(c(it)), listOf(c(it)), profile, votes, setOf(id(1)), now = 40 * day) != null
        }.toSet()
        assertEquals(setOf(2L, 5L), eligible) // 1 in library, 3 excluded, 4 voted yes, 6 maybe too recent
    }

    @Test fun mixesRecommendedAndExploration() {
        val recs = listOf(c(1, setOf(1)))
        val explore = listOf(c(2, setOf(99)))
        val profile = UserProfile.Empty
        val picks = (1..400).map { InterestPrompter(Random(it)).pick(recs, explore, profile, emptyMap(), emptySet(), 0)!!.id.tmdbId }
        val share = picks.count { it == 1L } / picks.size.toDouble()
        assertTrue(share in 0.62..0.78, "recommended share was $share")
    }

    @Test fun fallsBackToOtherPoolWhenOneIsEmpty() {
        val only = listOf(c(7))
        assertEquals(id(7), p.pick(only, emptyList(), UserProfile.Empty, emptyMap(), emptySet(), 0)?.id)
        assertEquals(id(7), p.pick(emptyList(), only, UserProfile.Empty, emptyMap(), emptySet(), 0)?.id)
        assertNull(p.pick(emptyList(), emptyList(), UserProfile.Empty, emptyMap(), emptySet(), 0))
    }

    @Test fun explorationPrefersUnfamiliarGenres() {
        val profile = UserProfile.Empty.copy(genreAffinity = mapOf(1 to 0.8))
        val known = c(1, setOf(1)); val fresh = c(2, setOf(77))
        assertEquals(1.0, p.novelty(fresh, profile)); assertEquals(0.0, p.novelty(known, profile))
        val pick = InterestPrompter(Random(1)).pick(emptyList(), listOf(known, fresh), profile, emptyMap(), emptySet(), 0)
        assertEquals(id(2), pick?.id)
    }
}
