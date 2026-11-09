package com.fitzy.flowstate.data.tmdb

import com.fitzy.flowstate.data.Catalog
import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.domain.MediaType
import com.fitzy.flowstate.domain.TitleId
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Candidate pool from TMDB: recommendations for the user's liked titles first, then popular movies and shows.
 * The top [maxDetails] get a details call for cast, keywords and the IMDb id; the rest score on genres only.
 * The pool is cached for [ttlMillis]. On any failure the last good pool (or an empty list) is returned.
 */
class TmdbCatalog(
    private val client: TmdbClient,
    private val seeds: () -> Set<TitleId>,
    private val clock: () -> Long,
    private val maxDetails: Int = 30,
    private val maxSeeds: Int = 5,
    private val ttlMillis: Long = 6L * 60 * 60 * 1000,
) : Catalog {
    private var cached: Pair<Long, List<Candidate>>? = null
    private val detailsCache = HashMap<TitleId, TmdbDetails>()

    override suspend fun pool(): List<Candidate> {
        cached?.let { (at, list) -> if (clock() - at < ttlMillis) return list }
        val fresh = runCatching { build() }.getOrNull() ?: return cached?.second ?: emptyList()
        cached = clock() to fresh
        return fresh
    }

    override suspend fun details(id: TitleId): TmdbDetails? = detailsFor(id)

    override suspend fun seasonEpisodes(show: TitleId, season: Int): List<EpisodeInfo> =
        runCatching { client.seasonEpisodes(show.tmdbId, season) }.getOrDefault(emptyList())

    override suspend fun seasonTrailerKey(show: TitleId, season: Int): String? =
        runCatching { client.seasonTrailerKey(show.tmdbId, season) }.getOrNull()

    private suspend fun build(): List<Candidate> {
        val popular = MediaType.entries.flatMap { client.popular(it) }

        // For each recommended title, remember its rank in each seed's list (0 = first).
        val similarSeeds = HashMap<TitleId, MutableMap<TitleId, Int>>()
        val recommended = seeds().take(maxSeeds).flatMap { seed ->
            client.recommendations(seed).also { list ->
                list.forEachIndexed { rank, t -> similarSeeds.getOrPut(t.id) { HashMap() }[seed] = rank }
            }
        }

        val all = (recommended + popular).distinctBy { it.id }
        val gate = Semaphore(DETAILS_PARALLELISM)
        return coroutineScope {
            all.mapIndexed { i, t ->
                async {
                    val d = if (i < maxDetails) gate.withPermit { detailsFor(t.id) } else null
                    Candidate(
                        id = t.id,
                        name = t.name,
                        year = t.year,
                        genres = t.genreIds,
                        keywords = d?.keywords ?: emptySet(),
                        cast = d?.cast ?: emptyList(),
                        voteAverage = t.voteAverage,
                        similarSeeds = similarSeeds[t.id] ?: emptyMap(),
                        posterPath = t.posterPath,
                        imdbId = d?.imdbId,
                    )
                }
            }.map { it.await() }
        }
    }

    private suspend fun detailsFor(id: TitleId): TmdbDetails? {
        detailsCache[id]?.let { return it }
        return runCatching { client.details(id) }.getOrNull()?.also { detailsCache[id] = it }
    }

    private companion object {
        const val DETAILS_PARALLELISM = 5
    }
}
