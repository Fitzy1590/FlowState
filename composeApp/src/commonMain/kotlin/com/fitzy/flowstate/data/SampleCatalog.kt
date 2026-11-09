package com.fitzy.flowstate.data

import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.domain.MediaType
import com.fitzy.flowstate.domain.TitleId

/**
 * DEV ONLY: a small fixed list so the app runs without a TMDB key or network.
 * Ids, years and genre ids are from memory and not checked against TMDB. Replace with a TMDB-backed Catalog.
 */
class SampleCatalog : Catalog {
    private fun m(id: Long, name: String, year: Int, vote: Double, vararg genres: Int) =
        Candidate(TitleId(MediaType.MOVIE, id), name, year, genres.toSet(), voteAverage = vote)

    private fun s(id: Long, name: String, year: Int, vote: Double, vararg genres: Int) =
        Candidate(TitleId(MediaType.SHOW, id), name, year, genres.toSet(), voteAverage = vote)

    override suspend fun pool(): List<Candidate> = listOf(
        m(857, "Saving Private Ryan", 1998, 8.2, 18, 10752),
        s(4613, "Band of Brothers", 2001, 8.5, 18, 10768),
        m(228150, "Fury", 2014, 7.5, 28, 18, 10752),
        m(374720, "Dunkirk", 2017, 7.4, 28, 18, 10752),
        m(28, "Apocalypse Now", 1979, 8.3, 18, 10752),
        s(1396, "Breaking Bad", 2008, 8.9, 18, 80),
        m(238, "The Godfather", 1972, 8.7, 18, 80),
        m(680, "Pulp Fiction", 1994, 8.5, 80, 53),
        m(27205, "Inception", 2010, 8.4, 28, 878, 12),
        m(438631, "Dune", 2021, 7.8, 878, 12),
        s(1399, "Game of Thrones", 2011, 8.4, 10765, 18),
        s(60622, "Fargo", 2014, 8.3, 80, 18),
        m(496243, "Parasite", 2019, 8.5, 35, 53, 18),
    )
}
