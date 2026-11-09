package com.fitzy.flowstate.data

import com.fitzy.flowstate.data.tmdb.TmdbDetails
import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.domain.TitleId

/** Source of titles to rank, explore and swipe. The real implementation will sit on TmdbClient. */
interface Catalog {
    suspend fun pool(): List<Candidate>

    /** Full details for one title, or null when unavailable. */
    suspend fun details(id: TitleId): TmdbDetails?
}
