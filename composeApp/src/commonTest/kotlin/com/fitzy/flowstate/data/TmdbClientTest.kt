package com.fitzy.flowstate.data

import com.fitzy.flowstate.data.tmdb.*
import com.fitzy.flowstate.domain.MediaType
import com.fitzy.flowstate.domain.TitleId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbClientTest {
    private fun client(json: String, onRequest: (io.ktor.client.request.HttpRequestData) -> Unit = {}): TmdbClient {
        val engine = MockEngine { req ->
            onRequest(req)
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return TmdbClient(HttpClient(engine).withTmdbJson(), apiKey = { "KEY" })
    }

    @Test fun searchKeepsMoviesAndShowsAndDropsPeople() = runTest {
        val c = client(
            """{"results":[
              {"id":857,"media_type":"movie","title":"Saving Private Ryan","release_date":"1998-07-24","poster_path":"/a.jpg","genre_ids":[18,10752],"vote_average":8.2},
              {"id":4613,"media_type":"tv","name":"Band of Brothers","first_air_date":"2001-09-09","genre_ids":[10768],"vote_average":8.5},
              {"id":1,"media_type":"person","name":"Tom Hanks"}]}""",
        )
        val r = c.search("war")
        assertEquals(2, r.size)
        assertEquals(TitleId(MediaType.MOVIE, 857), r[0].id)
        assertEquals(1998, r[0].year)
        assertEquals(setOf(18, 10752), r[0].genreIds)
        assertEquals(TitleId(MediaType.SHOW, 4613), r[1].id)
        assertEquals("Band of Brothers", r[1].name)
        assertNull(r[1].posterPath)
    }

    @Test fun similarUsesFallbackTypeAndSendsKey() = runTest {
        var url = ""
        val c = client("""{"results":[{"id":9,"title":"X","release_date":"2000-01-01"}]}""") { url = it.url.toString() }
        val r = c.similar(TitleId(MediaType.MOVIE, 857))
        assertEquals(TitleId(MediaType.MOVIE, 9), r.single().id)
        assertTrue("/movie/857/similar" in url && "api_key=KEY" in url, url)
    }

    @Test fun discoverBuildsWarFilterForOldPopularMovies() = runTest {
        var url = ""
        val c = client("""{"results":[]}""") { url = it.url.toString() }
        c.discover(MediaType.MOVIE, DiscoverQuery(genres = setOf(10752), yearTo = 1999, minVotes = 1000))
        assertTrue("with_genres=10752" in url, url)
        assertTrue("primary_release_date.lte=1999-12-31" in url, url)
        assertTrue("vote_count.gte=1000" in url, url)
        assertTrue("sort_by=vote_count.desc" in url, url)
    }

    @Test fun discoverUsesShowDateField() = runTest {
        var url = ""
        val c = client("""{"results":[]}""") { url = it.url.toString() }
        c.discover(MediaType.SHOW, DiscoverQuery(yearFrom = 2000))
        assertTrue("first_air_date.gte=2000-01-01" in url, url)
    }

    @Test fun detailsParsesCastKeywordsImdbAndBestTrailer() = runTest {
        val c = client(
            """{"id":4613,"name":"Band of Brothers","first_air_date":"2001-09-09","genres":[{"id":10768,"name":"War"}],
              "vote_average":8.5,"external_ids":{"imdb_id":"tt0185906"},
              "credits":{"cast":[{"id":2,"order":1},{"id":1,"order":0}]},
              "keywords":{"results":[{"id":5,"name":"wwii"}]},
              "videos":{"results":[
                {"key":"old","site":"YouTube","type":"Trailer","official":false,"published_at":"2010-01-01T00:00:00.000Z"},
                {"key":"best","site":"YouTube","type":"Trailer","official":true,"published_at":"2020-01-01T00:00:00.000Z"},
                {"key":"vimeo","site":"Vimeo","type":"Trailer","official":true}]}}""",
        )
        val d = c.details(TitleId(MediaType.SHOW, 4613))
        assertEquals("tt0185906", d.imdbId)
        assertEquals(listOf(1L, 2L), d.cast)
        assertEquals(setOf(5), d.keywords)
        assertEquals("best", d.trailerKey)
        assertEquals(2001, d.title.year)
    }

    @Test fun detailsMovieKeywordsAndTopLevelImdb() = runTest {
        val c = client("""{"id":857,"title":"Saving Private Ryan","imdb_id":"tt0120815","keywords":{"keywords":[{"id":7,"name":"d-day"}]}}""")
        val d = c.details(TitleId(MediaType.MOVIE, 857))
        assertEquals("tt0120815", d.imdbId)
        assertEquals(setOf(7), d.keywords)
        assertNull(d.trailerKey)
    }

    @Test fun posterUrlsPreferTmdbThenMetahub() {
        assertEquals("https://image.tmdb.org/t/p/w342/a.jpg", PosterUrls.tmdb("/a.jpg"))
        assertEquals(2, PosterUrls.candidates("/a.jpg", "tt1").size)
        assertEquals(1, PosterUrls.candidates(null, "tt1").size)
        assertTrue(PosterUrls.candidates(null, null).isEmpty())
    }
}
