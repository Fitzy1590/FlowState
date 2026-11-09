package com.fitzy.flowstate.data

import com.fitzy.flowstate.domain.Candidate

/** Source of titles to rank, explore and swipe. The real implementation will sit on TmdbClient. */
interface Catalog {
    suspend fun pool(): List<Candidate>
}
