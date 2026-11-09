package com.fitzy.flowstate.presenter

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.fitzy.flowstate.data.Catalog
import com.fitzy.flowstate.data.local.FlowRepository
import com.fitzy.flowstate.data.tmdb.TmdbDetails
import com.fitzy.flowstate.db.FlowDatabase
import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.domain.MediaType
import com.fitzy.flowstate.domain.TitleId

class FakeCatalog(private val items: List<Candidate>) : Catalog {
    override suspend fun pool() = items
    override suspend fun details(id: TitleId): TmdbDetails? = null
}

class Clock(var now: Long = 100L * 24 * 60 * 60 * 1000) : () -> Long {
    override fun invoke() = now
}

fun newRepo(clock: Clock): FlowRepository {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    FlowDatabase.Schema.create(driver)
    return FlowRepository(FlowDatabase(driver), clock)
}

fun cand(n: Long, vararg genres: Int, year: Int = 2020) =
    Candidate(TitleId(MediaType.MOVIE, n), "Title $n", year, genres.toSet(), voteAverage = 7.0)
