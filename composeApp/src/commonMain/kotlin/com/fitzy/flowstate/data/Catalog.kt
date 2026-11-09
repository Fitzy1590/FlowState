package com.fitzy.flowstate.data

import com.fitzy.flowstate.data.tmdb.DiscoverQuery
import com.fitzy.flowstate.data.tmdb.EpisodeInfo
import com.fitzy.flowstate.data.tmdb.TmdbDetails
import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.domain.MediaType
import com.fitzy.flowstate.domain.TitleId

/** Source of titles to rank, explore and swipe. The real implementation will sit on TmdbClient. */
interface Catalog {
    suspend fun pool(): List<Candidate>

    /** Full details for one title, or null when unavailable. */
    suspend fun details(id: TitleId): TmdbDetails?

    suspend fun seasonEpisodes(show: TitleId, season: Int): List<EpisodeInfo>

    /** YouTube key of the season trailer, or null. */
    suspend fun seasonTrailerKey(show: TitleId, season: Int): String?

    /** Title search by name. Empty when unsupported. */
    suspend fun search(query: String): List<Candidate> = emptyList()

    /** Popular-first discovery with filters (genre, years, minimum votes). */
    suspend fun discover(type: MediaType, query: DiscoverQuery): List<Candidate> = emptyList()

    /** Titles related to [id], best first (position = rank). */
    suspend fun related(id: TitleId): List<Candidate> = emptyList()
}
