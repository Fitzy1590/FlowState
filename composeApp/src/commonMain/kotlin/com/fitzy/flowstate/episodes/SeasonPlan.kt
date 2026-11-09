package com.fitzy.flowstate.episodes

import com.fitzy.flowstate.data.tmdb.SeasonInfo

/** Which season trailer and recap buttons to offer (SPEC 7b, 7c). Specials (season 0) are ignored. */
data class SeasonPlan(
    /** Highest season the user has fully watched. */
    val lastFinished: Int?,
    /** Season whose trailer to offer. */
    val trailerSeason: Int?,
    /** Season whose recap to offer: the last finished one. */
    val recapSeason: Int?,
) {
    companion object {
        fun of(seasons: List<SeasonInfo>, watched: Set<Pair<Int, Int>>): SeasonPlan {
            val regular = seasons.filter { it.number > 0 && it.episodeCount > 0 }.sortedBy { it.number }
            val finished = regular.filter { s -> watched.count { it.first == s.number } >= s.episodeCount }.map { it.number }
            val last = finished.maxOrNull()
            // Not started at all: offer the first season's trailer. Otherwise the season after the last finished one.
            val trailer = when {
                last == null -> regular.firstOrNull()?.takeIf { s -> watched.none { it.first == s.number } }?.number
                else -> regular.firstOrNull { it.number > last }?.number
            }
            return SeasonPlan(last, trailer, last)
        }
    }
}
