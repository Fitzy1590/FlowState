package com.fitzy.flowstate.domain

enum class MediaType { MOVIE, SHOW }

data class TitleId(val type: MediaType, val tmdbId: Long)

enum class Vote { YES, MAYBE, NEVER }

enum class VoteSource { PROMPT, SWIPE, SEARCH }

data class InterestVote(
    val titleId: TitleId,
    val vote: Vote,
    val atMillis: Long,
    val source: VoteSource,
)

/** Features of a title used to learn from votes. Cast is in billing order. */
data class TitleFeatures(
    val genres: Set<Int> = emptySet(),
    val keywords: Set<Int> = emptySet(),
    val cast: List<Long> = emptyList(),
)

data class VotedTitle(val vote: InterestVote, val features: TitleFeatures)

/**
 * One title in the user's history.
 * [completion] is 0..1 of the title watched. [abandoned] means under 25% watched after 30 days.
 */
data class WatchRecord(
    val titleId: TitleId,
    val genres: Set<Int>,
    val userScore: Int?,
    val completion: Double,
    val abandoned: Boolean = false,
) {
    init {
        require(userScore == null || userScore in 0..100) { "userScore must be 0..100" }
        require(completion in 0.0..1.0) { "completion must be 0..1" }
    }
}

data class ActorPrefs(
    val liked: Set<Long> = emptySet(),
    val disliked: Set<Long> = emptySet(),
)

/** A title that may be recommended. [similarSeeds] maps a seed title to this title's rank in that seed's TMDB list (0 = first). */
data class Candidate(
    val id: TitleId,
    val name: String,
    val year: Int?,
    val genres: Set<Int>,
    val keywords: Set<Int> = emptySet(),
    val cast: List<Long> = emptyList(),
    val voteAverage: Double = 0.0,
    val similarSeeds: Map<TitleId, Int> = emptyMap(),
)
