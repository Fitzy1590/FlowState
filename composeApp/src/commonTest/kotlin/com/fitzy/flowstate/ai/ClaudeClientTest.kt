package com.fitzy.flowstate.ai

import com.fitzy.flowstate.data.ai.ClaudeClient
import com.fitzy.flowstate.data.ai.ClaudeSummarizer
import com.fitzy.flowstate.data.tmdb.EpisodeInfo
import com.fitzy.flowstate.data.tmdb.withTmdbJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ClaudeClientTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private var lastKey: String? = null
    private var lastVersion: String? = null
    private var lastBody = ""
    private var calls = 0

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, key: String? = "sk-test", body: String = """{"content":[{"type":"text","text":"  Easy Company trains.  "}]}"""): ClaudeClient {
        val engine = MockEngine { req ->
            calls++
            lastKey = req.headers["x-api-key"]; lastVersion = req.headers["anthropic-version"]
            lastBody = (req.body as? TextContent)?.text.orEmpty()
            respond(body, status, json)
        }
        return ClaudeClient(HttpClient(engine).withTmdbJson(), apiKey = { key })
    }

    @Test fun sendsKeyVersionModelAndPrompt() = runTest {
        val c = client()
        assertEquals("Easy Company trains.", c.complete("SYS", "USER", 123))
        assertEquals("sk-test", lastKey)
        assertEquals("2023-06-01", lastVersion)
        assertTrue("\"model\":\"claude-haiku-5-5\"" in lastBody, lastBody)
        assertTrue("\"max_tokens\":123" in lastBody && "SYS" in lastBody && "USER" in lastBody, lastBody)
    }

    @Test fun noKeyMakesNoRequest() = runTest {
        assertNull(client(key = null).complete("s", "u"))
        assertNull(client(key = "  ").complete("s", "u"))
        assertEquals(0, calls)
    }

    @Test fun errorStatusAndBadBodyReturnNull() = runTest {
        assertNull(client(status = HttpStatusCode.Unauthorized).complete("s", "u"))
        assertNull(client(body = "not json").complete("s", "u"))
        assertNull(client(body = """{"content":[]}""").complete("s", "u"))
    }

    @Test fun summarizerRefusesToInventTextForEmptyDescription() = runTest {
        val s = ClaudeSummarizer(client())
        assertNull(s.episode("Show", EpisodeInfo(1, 1, "Pilot", "", null)))
        assertEquals(0, calls)
        assertNotNull(s.episode("Show", EpisodeInfo(1, 1, "Pilot", "Things happen.", null)))
        assertTrue("Things happen." in lastBody && "Do not add facts" in lastBody, lastBody)
    }

    @Test fun catchUpNeedsAtLeastOneSummaryAndListsThem() = runTest {
        val s = ClaudeSummarizer(client())
        assertNull(s.catchUp("Show", 1, emptyList()))
        assertEquals(0, calls)
        s.catchUp("Show", 1, listOf("A happens.", "B happens."))
        assertTrue("1. A happens." in lastBody && "2. B happens." in lastBody, lastBody)
    }
}
