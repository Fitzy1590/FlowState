package com.fitzy.flowstate.reco

import com.fitzy.flowstate.domain.ActorPrefs
import com.fitzy.flowstate.domain.TitleId
import com.fitzy.flowstate.domain.Vote
import com.fitzy.flowstate.domain.VotedTitle
import com.fitzy.flowstate.domain.WatchRecord

internal const val PRIOR_STRENGTH = 5

/** Mean of [values] pulled toward [prior] as if [PRIOR_STRENGTH] extra observations equal to [prior] existed. */
internal fun smoothedMean(values: List<Double>, prior: Double): Double =
    (values.sum() + prior * PRIOR_STRENGTH) / (values.size + PRIOR_STRENGTH)

/** Weight units from SPEC section 8. */
internal fun Vote.weight(): Double = when (this) {
    Vote.YES -> 1.0
    Vote.MAYBE -> 0.3
    Vote.NEVER -> -0.2
}

/** Aggregated taste, built once from history and votes. All maps are keyed by TMDB ids. */
data class UserProfile(
    /** Smoothed mean of (score/100 * completion) per genre, prior 0.5. */
    val genreAffinity: Map<Int, Double>,
    /** Smoothed mean of score/100 per genre for rated titles, prior 0.5. */
    val genreRating: Map<Int, Double>,
    /** Smoothed mean completion per genre (abandoned counts as 0), prior 0.5. */
    val genreCompletion: Map<Int, Double>,
    /** Vote affinity in weight units, per feature. */
    val voteGenre: Map<Int, Double>,
    val voteKeyword: Map<Int, Double>,
    val voteCast: Map<Long, Double>,
    /** Titles the user rated 70 or more; used as similarity seeds. */
    val likedSeeds: Set<TitleId>,
    /** Never recommended: already in history, or voted NEVER. */
    val excluded: Set<TitleId>,
    val actorPrefs: ActorPrefs,
) {
    companion object {
        /** Default score assumed for an unrated title when learning genre affinity. */
        private const val UNRATED_DEFAULT = 0.6

        val Empty = build(emptyList(), emptyList(), ActorPrefs())

        fun build(
            history: List<WatchRecord>,
            votes: List<VotedTitle>,
            actorPrefs: ActorPrefs,
        ): UserProfile {
            val affinity = HashMap<Int, MutableList<Double>>()
            val rating = HashMap<Int, MutableList<Double>>()
            val completion = HashMap<Int, MutableList<Double>>()
            for (r in history) {
                val comp = if (r.abandoned) 0.0 else r.completion
                val score = r.userScore?.let { it / 100.0 } ?: UNRATED_DEFAULT
                for (g in r.genres) {
                    affinity.getOrPut(g) { mutableListOf() }.add(score * comp)
                    completion.getOrPut(g) { mutableListOf() }.add(comp)
                    r.userScore?.let { rating.getOrPut(g) { mutableListOf() }.add(it / 100.0) }
                }
            }

            // One current vote per title: the latest wins.
            val latest = votes.groupBy { it.vote.titleId }.mapValues { (_, v) -> v.maxBy { it.vote.atMillis } }.values
            val vGenre = HashMap<Int, MutableList<Double>>()
            val vKeyword = HashMap<Int, MutableList<Double>>()
            val vCast = HashMap<Long, MutableList<Double>>()
            for (v in latest) {
                val w = v.vote.vote.weight()
                v.features.genres.forEach { vGenre.getOrPut(it) { mutableListOf() }.add(w) }
                v.features.keywords.forEach { vKeyword.getOrPut(it) { mutableListOf() }.add(w) }
                v.features.cast.take(TOP_BILLED).forEach { vCast.getOrPut(it) { mutableListOf() }.add(w) }
            }

            return UserProfile(
                genreAffinity = affinity.mapValues { smoothedMean(it.value, 0.5) },
                genreRating = rating.mapValues { smoothedMean(it.value, 0.5) },
                genreCompletion = completion.mapValues { smoothedMean(it.value, 0.5) },
                voteGenre = vGenre.mapValues { smoothedMean(it.value, 0.0) },
                voteKeyword = vKeyword.mapValues { smoothedMean(it.value, 0.0) },
                voteCast = vCast.mapValues { smoothedMean(it.value, 0.0) },
                likedSeeds = history.filter { (it.userScore ?: 0) >= 70 }.map { it.titleId }.toSet(),
                excluded = history.map { it.titleId }.toSet() +
                    latest.filter { it.vote.vote == Vote.NEVER }.map { it.vote.titleId },
                actorPrefs = actorPrefs,
            )
        }
    }
}

internal const val TOP_BILLED = 5
