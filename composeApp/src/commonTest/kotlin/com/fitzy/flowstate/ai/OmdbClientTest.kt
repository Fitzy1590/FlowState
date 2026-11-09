package com.fitzy.flowstate.ai

import com.fitzy.flowstate.data.tmdb.withTmdbJson
import com.fitzy.flowstate.ratings.OmdbClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OmdbClientTest {
    private var url = ""
    private fun client(body: String, key: String? = "K", status: HttpStatusCode = HttpStatusCode.OK) = OmdbClient(
        HttpClient(MockEngine { url = it.url.toString(); respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }).withTmdbJson(), { key },
    )

    @Test fun parsesImdbAndTomatometerAndSendsIdAndKey() = runTest {
        val r = client("""{"Response":"True","imdbRating":"8.2","Ratings":[{"Source":"Rotten Tomatoes","Value":"93%"}]}""").ratings("tt0120815")!!
        assertEquals(8.2, r.imdb); assertEquals(93, r.rottenTomatoes)
        assertTrue("i=tt0120815" in url && "apikey=K" in url, url)
    }

    @Test fun missingScoresStayNull() = runTest {
        val r = client("""{"Response":"True","imdbRating":"N/A","Ratings":[]}""").ratings("tt1")!!
        assertNull(r.imdb); assertNull(r.rottenTomatoes)
    }

    @Test fun noKeyUnknownIdOrErrorReturnNull() = runTest {
        assertNull(client("{}", key = null).ratings("tt1"))
        assertNull(client("""{"Response":"False","Error":"Incorrect IMDb ID."}""").ratings("tt0"))
        assertNull(client("{}", status = HttpStatusCode.Unauthorized).ratings("tt1"))
        assertNull(client("not json").ratings("tt1"))
    }
}
