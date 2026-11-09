package com.fitzy.flowstate.data

import com.fitzy.flowstate.data.tmdb.DiscoverQuery
import com.fitzy.flowstate.data.tmdb.EpisodeInfo
import com.fitzy.flowstate.data.tmdb.TmdbDetails
import com.fitzy.flowstate.data.tmdb.TmdbTitle
import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.domain.MediaType
import com.fitzy.flowstate.domain.TitleId

/**
 * DEV ONLY: a small fixed list so the app runs without a TMDB key or network.
 * Ids, years and genre ids are from memory and not checked against TMDB. Replace with a TMDB-backed Catalog.
 */
class SampleCatalog : Catalog {
    private fun m(id: Long, imdb: String, name: String, year: Int, vote: Double, vararg genres: Int) =
        Candidate(TitleId(MediaType.MOVIE, id), name, year, genres.toSet(), voteAverage = vote, imdbId = imdb)

    private fun s(id: Long, imdb: String, name: String, year: Int, vote: Double, vararg genres: Int) =
        Candidate(TitleId(MediaType.SHOW, id), name, year, genres.toSet(), voteAverage = vote, imdbId = imdb)

    override suspend fun pool(): List<Candidate> = all

    override suspend fun details(id: TitleId): TmdbDetails? = all.firstOrNull { it.id == id }?.let {
        TmdbDetails(
            TmdbTitle(it.id, it.name, it.year, it.posterPath, it.genres, it.voteAverage),
            imdbId = it.imdbId, cast = emptyList(), keywords = emptySet(), trailerKey = null,
        )
    }

    override suspend fun search(query: String): List<Candidate> =
        all.filter { it.name.contains(query.trim(), ignoreCase = true) }

    override suspend fun discover(type: MediaType, query: DiscoverQuery): List<Candidate> = all
        .filter { it.id.type == type }
        .filter { query.genres.isEmpty() || it.genres.any { g -> g in query.genres } }
        .filter { query.yearFrom == null || (it.year ?: 0) >= query.yearFrom }
        .filter { query.yearTo == null || (it.year ?: 9999) <= query.yearTo }
        .sortedByDescending { it.voteAverage }

    override suspend fun related(id: TitleId): List<Candidate> {
        val seed = all.firstOrNull { it.id == id } ?: return emptyList()
        return all.filter { it.id != id && it.genres.any { g -> g in seed.genres } }
            .sortedByDescending { c -> c.genres.count { it in seed.genres } * 10 + c.voteAverage }
    }

    override suspend fun seasonEpisodes(show: TitleId, season: Int): List<EpisodeInfo> = emptyList()

    override suspend fun seasonTrailerKey(show: TitleId, season: Int): String? = null

    private val all = listOf(
        m(857, "tt0120815", "Saving Private Ryan", 1998, 8.2, 18, 10752),
        s(4613, "tt0185906", "Band of Brothers", 2001, 8.5, 18, 10768),
        m(228150, "tt2713180", "Fury", 2014, 7.5, 28, 18, 10752),
        m(374720, "tt5013056", "Dunkirk", 2017, 7.4, 28, 18, 10752),
        m(28, "tt0078788", "Apocalypse Now", 1979, 8.3, 18, 10752),
        s(1396, "tt0903747", "Breaking Bad", 2008, 8.9, 18, 80),
        m(238, "tt0068646", "The Godfather", 1972, 8.7, 18, 80),
        m(680, "tt0110912", "Pulp Fiction", 1994, 8.5, 80, 53),
        m(27205, "tt1375666", "Inception", 2010, 8.4, 28, 878, 12),
        m(438631, "tt1160419", "Dune", 2021, 7.8, 878, 12),
        s(1399, "tt0944947", "Game of Thrones", 2011, 8.4, 10765, 18),
        s(60622, "tt2802850", "Fargo", 2014, 8.3, 80, 18),
        m(496243, "tt6751668", "Parasite", 2019, 8.5, 35, 53, 18),
    )
}
