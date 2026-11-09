package com.fitzy.flowstate.reco

import com.fitzy.flowstate.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecommenderTest {
    private val reco = Recommender(currentYear = 2026)
    private val war = 10752
    private val comedy = 35

    private fun movie(id: Long) = TitleId(MediaType.MOVIE, id)
    private fun cand(
        id: Long = 1, genres: Set<Int> = setOf(war), cast: List<Long> = emptyList(),
        seeds: Map<TitleId, Int> = emptyMap(), year: Int? = 2020, vote: Double = 7.5,
    ) = Candidate(movie(id), "t$id", year, genres, emptySet(), cast, vote, seeds)

    private fun close(a: Double, b: Double) = assertTrue(kotlin.math.abs(a - b) < 1e-9, "expected $b got $a")

    @Test fun weightsSumToOne() = close(Weights.SUM, 1.0)

    @Test fun scoreStaysInRange() {
        val s = reco.score(cand(), UserProfile.Empty)
        assertTrue(s.total in 0.0..1.0)
    }

    @Test fun likedActorsAddAtMostCap() {
        val cast = listOf(1L, 2, 3, 4, 5)
        val none = reco.score(cand(cast = cast), UserProfile.Empty).total
        val liked = reco.score(cand(cast = cast), profile(ActorPrefs(liked = cast.toSet()))).total
        close(liked - none, ActorAdjust.LIKED_CAP) // 0.08 * 3.1 = 0.248, capped
    }

    @Test fun dislikedActorsSubtractAtMostCapButTitleStays() {
        val cast = listOf(1L, 2, 3, 4, 5)
        val none = reco.score(cand(cast = cast), UserProfile.Empty).total
        val prefs = ActorPrefs(disliked = cast.toSet())
        val ranked = reco.rank(listOf(cand(cast = cast)), profile(prefs))
        assertEquals(1, ranked.size) // soft penalty never removes a title
        close(none - ranked[0].breakdown.total, ActorAdjust.DISLIKED_CAP)
    }

    @Test fun billingOrderScalesAdjustment() {
        val lead = reco.score(cand(cast = listOf(1L)), profile(ActorPrefs(liked = setOf(1L)))).breakdown()
        val fifth = reco.score(cand(cast = listOf(9L, 9, 9, 9, 1L)), profile(ActorPrefs(liked = setOf(1L)))).breakdown()
        close(lead, 0.08 * 1.0)
        close(fifth, 0.08 * 0.3)
    }

    @Test fun actorsBeyondTopFiveIgnored() {
        val c = cand(cast = listOf(9L, 9, 9, 9, 9, 1L))
        close(reco.score(c, profile(ActorPrefs(liked = setOf(1L)))).actorAdjustment, 0.0)
    }

    @Test fun watchedAndNeverTitlesAreExcluded() {
        val history = listOf(WatchRecord(movie(1), setOf(war), 80, 1.0))
        val votes = listOf(vote(2, Vote.NEVER, setOf(war)))
        val p = UserProfile.build(history, votes, ActorPrefs())
        val ranked = reco.rank(listOf(cand(1), cand(2), cand(3)), p)
        assertEquals(listOf(movie(3)), ranked.map { it.candidate.id })
    }

    @Test fun similarityDecaysByRankAndCapsAtOne() {
        val a = movie(100); val b = movie(101); val low = movie(102)
        val history = listOf(
            WatchRecord(a, setOf(war), 90, 1.0), WatchRecord(b, setOf(war), 80, 1.0), WatchRecord(low, setOf(war), 60, 1.0),
        )
        val p = UserProfile.build(history, emptyList(), ActorPrefs())
        close(reco.score(cand(seeds = mapOf(a to 0)), p).similarity, 1.0)
        close(reco.score(cand(seeds = mapOf(a to 1)), p).similarity, 0.5)
        close(reco.score(cand(seeds = mapOf(a to 0, b to 0)), p).similarity, 1.0) // capped
        close(reco.score(cand(seeds = mapOf(low to 0)), p).similarity, 0.0) // rated under 70
    }

    @Test fun lovedGenreBeatsUnknownGenre() {
        val history = (1L..6).map { WatchRecord(movie(it), setOf(war), 95, 1.0) }
        val p = UserProfile.build(history, emptyList(), ActorPrefs())
        val warScore = reco.score(cand(id = 50, genres = setOf(war)), p).genre
        val comedyScore = reco.score(cand(id = 51, genres = setOf(comedy)), p).genre
        assertTrue(warScore > 0.5)
        close(comedyScore, 0.5)
    }

    @Test fun abandonedTitlesLowerGenreCompletion() {
        val finished = UserProfile.build(listOf(WatchRecord(movie(1), setOf(war), 80, 1.0)), emptyList(), ActorPrefs())
        val dropped = UserProfile.build(listOf(WatchRecord(movie(1), setOf(war), 80, 0.2, abandoned = true)), emptyList(), ActorPrefs())
        assertTrue(finished.genreCompletion.getValue(war) > dropped.genreCompletion.getValue(war))
    }

    @Test fun yesVotesRaiseAndNeverVotesLowerVoteSignal() {
        val yes = UserProfile.build(emptyList(), (1L..4).map { vote(it, Vote.YES, setOf(war)) }, ActorPrefs())
        val never = UserProfile.build(emptyList(), (1L..4).map { vote(it, Vote.NEVER, setOf(war)) }, ActorPrefs())
        val c = cand(id = 99)
        assertTrue(reco.score(c, yes).votes > 0.5)
        assertTrue(reco.score(c, never).votes < 0.5)
    }

    @Test fun latestVotePerTitleWins() {
        val old = vote(1, Vote.NEVER, setOf(war), at = 1)
        val new = vote(1, Vote.YES, setOf(war), at = 2)
        val p = UserProfile.build(emptyList(), listOf(old, new), ActorPrefs())
        assertTrue(movie(1) !in p.excluded)
        assertTrue(p.voteGenre.getValue(war) > 0.0)
    }

    @Test fun newerTitlesGetRecencyBoost() {
        assertTrue(reco.score(cand(year = 2025), UserProfile.Empty).recency > reco.score(cand(year = 2005), UserProfile.Empty).recency)
        close(reco.score(cand(year = 2005), UserProfile.Empty).recency, 0.0)
    }

    private fun ScoreBreakdown.breakdown() = actorAdjustment
    private fun profile(prefs: ActorPrefs) = UserProfile.build(emptyList(), emptyList(), prefs)
    private fun vote(id: Long, v: Vote, genres: Set<Int>, at: Long = 1) = VotedTitle(
        InterestVote(movie(id), v, at, VoteSource.SWIPE), TitleFeatures(genres = genres),
    )
}
