package com.fitzy.flowstate.data

import com.fitzy.flowstate.data.tmdb.TmdbCatalog
import com.fitzy.flowstate.data.tmdb.TmdbClient
import com.fitzy.flowstate.data.tmdb.withTmdbJson
import com.fitzy.flowstate.domain.MediaType
import com.fitzy.flowstate.domain.TitleId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TmdbCatalogTest {
    private val ryan = TitleId(MediaType.MOVIE, 857)
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Fixture(val failAll: Boolean = false) {
        val paths = mutableListOf<String>()
        var authHeader: String? = null
    }

    private fun client(fx: Fixture, token: String = "KEY"): TmdbClient {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            fx.paths += path
            fx.authHeader = req.headers["Authorization"]
            if (fx.failAll) return@MockEngine respond("{}", HttpStatusCode.InternalServerError, json)
            val body = when {
                path.endsWith("/movie/popular") -> """{"results":[{"id":1,"title":"Pop Movie","release_date":"2020-01-01","genre_ids":[28],"vote_average":7.0},{"id":2,"title":"Rec Movie","release_date":"1999-01-01","genre_ids":[10752]}]}"""
                path.endsWith("/tv/popular") -> """{"results":[{"id":9,"name":"Pop Show","first_air_date":"2015-01-01","genre_ids":[18]}]}"""
                path.endsWith("/movie/857/recommendations") -> """{"results":[{"id":3,"title":"Fury","release_date":"2014-01-01","genre_ids":[10752]},{"id":2,"title":"Rec Movie","release_date":"1999-01-01","genre_ids":[10752]}]}"""
                path.endsWith("/movie/3") -> """{"id":3,"title":"Fury","imdb_id":"tt2713180","genres":[{"id":10752}],"credits":{"cast":[{"id":50,"order":0}]},"keywords":{"keywords":[{"id":8}]}}"""
                else -> """{"id":0}"""
            }
            respond(body, HttpStatusCode.OK, json)
        }
        return TmdbClient(HttpClient(engine).withTmdbJson(), apiKey = { token })
    }

    @Test fun mergesRecommendationsAndPopularWithoutDuplicates() = runTest {
        val cat = TmdbCatalog(client(Fixture()), seeds = { setOf(ryan) }, clock = { 0 })
        val pool = cat.pool()
        assertEquals(listOf(3L, 2L, 1L, 9L), pool.map { it.id.tmdbId }) // recommended first, then popular, id 2 once
    }

    @Test fun recordsSeedRankAndEnrichesWithDetails() = runTest {
        val pool = TmdbCatalog(client(Fixture()), seeds = { setOf(ryan) }, clock = { 0 }).pool()
        val fury = pool.first { it.id.tmdbId == 3L }
        assertEquals(mapOf(ryan to 0), fury.similarSeeds)
        assertEquals(listOf(50L), fury.cast)
        assertEquals(setOf(8), fury.keywords)
        assertEquals("tt2713180", fury.imdbId)
        assertEquals(mapOf(ryan to 1), pool.first { it.id.tmdbId == 2L }.similarSeeds)
    }

    @Test fun detailsFailureKeepsGenreOnlyCandidate() = runTest {
        val pool = TmdbCatalog(client(Fixture()), seeds = { emptySet() }, clock = { 0 }).pool()
        val pop = pool.first { it.id.tmdbId == 1L }
        assertEquals(setOf(28), pop.genres)
        assertTrue(pop.cast.isEmpty())
    }

    @Test fun cachesPoolUntilTtlExpires() = runTest {
        val fx = Fixture(); var now = 0L
        val cat = TmdbCatalog(client(fx), seeds = { emptySet() }, clock = { now }, ttlMillis = 1000)
        cat.pool(); val calls = fx.paths.size
        now = 500; cat.pool()
        assertEquals(calls, fx.paths.size)
        now = 1500; cat.pool()
        assertTrue(fx.paths.size > calls)
    }

    @Test fun failureReturnsEmptyThenLastGoodPool() = runTest {
        assertTrue(TmdbCatalog(client(Fixture(failAll = true)), seeds = { emptySet() }, clock = { 0 }).pool().isEmpty())
    }

    @Test fun longTokenIsSentAsBearerShortKeyAsParameter() = runTest {
        val fx = Fixture()
        TmdbCatalog(client(fx, token = "e".repeat(120)), seeds = { emptySet() }, clock = { 0 }).pool()
        assertEquals("Bearer ${"e".repeat(120)}", fx.authHeader)
        val fx2 = Fixture()
        TmdbCatalog(client(fx2), seeds = { emptySet() }, clock = { 0 }).pool()
        assertNull(fx2.authHeader)
    }
}
