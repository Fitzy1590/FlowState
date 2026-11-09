package com.fitzy.flowstate.episodes

import com.fitzy.flowstate.data.tmdb.SeasonInfo

/** The first episode the user has not watched, in season then episode order. Specials (season 0) are skipped. */
object NextEpisode {
    fun of(seasons: List<SeasonInfo>, watched: Set<Pair<Int, Int>>): Pair<Int, Int>? {
        for (s in seasons.filter { it.number > 0 }.sortedBy { it.number }) {
            val e = (1..s.episodeCount).firstOrNull { (s.number to it) !in watched }
            if (e != null) return s.number to e
        }
        return null
    }
}
