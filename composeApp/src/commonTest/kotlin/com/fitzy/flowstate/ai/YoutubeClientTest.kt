package com.fitzy.flowstate.ai

import com.fitzy.flowstate.data.tmdb.withTmdbJson
import com.fitzy.flowstate.data.youtube.YoutubeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class YoutubeClientTest {
    private var url = ""
    private fun client(key: String? = "K", status: HttpStatusCode = HttpStatusCode.OK, body: String = """{"items":[{"id":{"videoId":"vid1"}}]}""") =
        YoutubeClient(HttpClient(MockEngine { url = it.url.toString(); respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }).withTmdbJson(), { key })

    @Test fun returnsTopResultAndSendsQuery() = runTest {
        assertEquals("vid1", client().firstVideoId("Band of Brothers season 1 recap"))
        assertTrue("maxResults=1" in url && "type=video" in url && "key=K" in url, url)
        assertTrue("Band%20of%20Brothers" in url || "Band+of+Brothers" in url, url)
    }

    @Test fun noKeyEmptyResultOrErrorReturnNull() = runTest {
        assertNull(client(key = null).firstVideoId("q"))
        assertNull(client(body = """{"items":[]}""").firstVideoId("q"))
        assertNull(client(status = HttpStatusCode.Forbidden).firstVideoId("q"))
    }
}
