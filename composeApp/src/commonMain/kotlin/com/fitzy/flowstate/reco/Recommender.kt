package com.fitzy.flowstate.reco

import com.fitzy.flowstate.domain.Candidate
import kotlin.math.abs

/** Signal weights from SPEC section 1. Must sum to 1.0. */
object Weights {
    const val GENRE = 0.25
    const val SIMILARITY = 0.20
    const val RATING = 0.15
    const val COMPLETION = 0.15
    const val VOTES = 0.15
    const val RECENCY = 0.10
    const val SUM = GENRE + SIMILARITY + RATING + COMPLETION + VOTES + RECENCY
}

/** Actor adjustment constants (soft penalty). Billing factor applies by cast position. */
object ActorAdjust {
    val BILLING_FACTOR = doubleArrayOf(1.0, 0.8, 0.6, 0.4, 0.3)
    const val LIKED_STEP = 0.08
    const val LIKED_CAP = 0.20
    const val DISLIKED_STEP = 0.10
    const val DISLIKED_CAP = 0.30
}

data class ScoreBreakdown(
    val genre: Double,
    val similarity: Double,
    val rating: Double,
    val completion: Double,
    val votes: Double,
    val recency: Double,
    val base: Double,
    val actorAdjustment: Double,
    val total: Double,
)

data class Scored(val candidate: Candidate, val breakdown: ScoreBreakdown)

class Recommender(private val currentYear: Int) {

    fun score(c: Candidate, p: UserProfile): ScoreBreakdown {
        val genre = meanOrDefault(c.genres.map { p.genreAffinity[it] ?: 0.5 }, 0.5)
        val similarity = c.similarSeeds.entries
            .filter { it.key in p.likedSeeds }
            .sumOf { 1.0 / (1 + it.value) }
            .coerceAtMost(1.0)
        val userRating = c.genres.mapNotNull { p.genreRating[it] }
        val rating = if (userRating.isEmpty()) 0.5
        else (1.0 - abs(c.voteAverage / 10.0 - userRating.average())).coerceIn(0.0, 1.0)
        val completion = meanOrDefault(c.genres.map { p.genreCompletion[it] ?: 0.5 }, 0.5)
        val votes = voteSignal(c, p)
        val recency = recency(c.year)

        val base = Weights.GENRE * genre + Weights.SIMILARITY * similarity + Weights.RATING * rating +
            Weights.COMPLETION * completion + Weights.VOTES * votes + Weights.RECENCY * recency
        val adj = actorAdjustment(c, p)
        return ScoreBreakdown(genre, similarity, rating, completion, votes, recency, base, adj, (base + adj).coerceIn(0.0, 1.0))
    }

    /** Candidates the user has seen or voted NEVER are dropped; the rest are ranked by total, best first. */
    fun rank(candidates: List<Candidate>, p: UserProfile): List<Scored> =
        candidates.filter { it.id !in p.excluded }
            .map { Scored(it, score(it, p)) }
            .sortedByDescending { it.breakdown.total }

    private fun voteSignal(c: Candidate, p: UserProfile): Double {
        val features = c.genres.map { p.voteGenre[it] ?: 0.0 } +
            c.keywords.map { p.voteKeyword[it] ?: 0.0 } +
            c.cast.take(TOP_BILLED).map { p.voteCast[it] ?: 0.0 }
        if (features.isEmpty()) return 0.5
        // Affinity is in weight units (-0.2..1.0); 0 maps to the neutral 0.5.
        return (0.5 + 0.5 * features.average()).coerceIn(0.0, 1.0)
    }

    private fun actorAdjustment(c: Candidate, p: UserProfile): Double {
        var liked = 0.0
        var disliked = 0.0
        c.cast.take(TOP_BILLED).forEachIndexed { i, actor ->
            val f = ActorAdjust.BILLING_FACTOR[i]
            if (actor in p.actorPrefs.liked) liked += ActorAdjust.LIKED_STEP * f
            if (actor in p.actorPrefs.disliked) disliked += ActorAdjust.DISLIKED_STEP * f
        }
        return liked.coerceAtMost(ActorAdjust.LIKED_CAP) - disliked.coerceAtMost(ActorAdjust.DISLIKED_CAP)
    }

    private fun recency(year: Int?): Double =
        if (year == null) 0.0 else (1.0 - (currentYear - year) / 10.0).coerceIn(0.0, 1.0)

    private fun meanOrDefault(v: List<Double>, default: Double) = if (v.isEmpty()) default else v.average()
}
