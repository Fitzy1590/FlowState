package com.fitzy.flowstate.search

import com.fitzy.flowstate.data.Catalog
import com.fitzy.flowstate.data.tmdb.DiscoverQuery
import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.domain.MediaType
import com.fitzy.flowstate.domain.TitleId
import com.fitzy.flowstate.reco.UserProfile

/** Resolves a [SearchQuery] against the catalog (SPEC 8c). Titles only ever come from the catalog. */
class SearchService(private val catalog: Catalog) {

    suspend fun run(q: SearchQuery, profile: UserProfile, limit: Int = 30): List<Candidate> {
        val raw = when {
            q.seedTitles.isNotEmpty() -> fromSeeds(q)
            q.genres.isNotEmpty() || q.yearFrom != null || q.yearTo != null || q.popular -> fromFilters(q)
            q.titleSearch != null -> catalog.search(q.titleSearch).filter { it.id.type in q.mediaTypes }
            else -> emptyList()
        }
        return raw.distinctBy { it.id }.filter { it.id !in profile.excluded }.take(limit)
    }

    /**
     * Related titles of every seed, merged. A title related to several seeds ranks above one related to a single seed;
     * ties keep the better position in the seeds' lists. The seeds themselves are left out.
     */
    private suspend fun fromSeeds(q: SearchQuery): List<Candidate> {
        val seeds = q.seedTitles.mapNotNull { name -> catalog.search(name).firstOrNull() }
        val seedIds = seeds.map { it.id }.toSet()
        val hits = HashMap<TitleId, Int>()
        val bestRank = HashMap<TitleId, Int>()
        val byId = LinkedHashMap<TitleId, Candidate>()
        for (seed in seeds) {
            catalog.related(seed.id).forEachIndexed { rank, c ->
                if (c.id in seedIds) return@forEachIndexed
                hits[c.id] = (hits[c.id] ?: 0) + 1
                bestRank[c.id] = minOf(bestRank[c.id] ?: Int.MAX_VALUE, rank)
                byId.getOrPut(c.id) { c }
            }
        }
        return byId.values
            .filter { it.id.type in q.mediaTypes }
            .sortedWith(compareByDescending<Candidate> { hits.getValue(it.id) }.thenBy { bestRank.getValue(it.id) })
    }

    /** Filters go to the database sorted by popularity; "popular" also requires a minimum number of votes. */
    private suspend fun fromFilters(q: SearchQuery): List<Candidate> {
        val perType = q.mediaTypes.map { type ->
            catalog.discover(
                type,
                DiscoverQuery(
                    genres = Genres.ids(q.genres, type),
                    yearFrom = q.yearFrom, yearTo = q.yearTo,
                    minVotes = if (q.popular) POPULAR_MIN_VOTES else null,
                ),
            )
        }
        // Interleave so a "movies and shows" request shows both kinds near the top.
        val out = ArrayList<Candidate>()
        var i = 0
        while (perType.any { i < it.size }) { perType.forEach { l -> l.getOrNull(i)?.let(out::add) }; i++ }
        return out
    }

    companion object {
        const val POPULAR_MIN_VOTES = 1000
    }
}
