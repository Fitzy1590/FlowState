package com.fitzy.flowstate.sync

import com.fitzy.flowstate.data.tmdb.withTmdbJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class StremioApiTest {
    private val paths = mutableListOf<String>()
    private var body: JsonObject = JsonObject(emptyMap())
    private fun api(reply: String, status: HttpStatusCode = HttpStatusCode.OK) = StremioApi(
        HttpClient(MockEngine { req ->
            paths += req.url.encodedPath
            body = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
            respond(reply, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }).withTmdbJson(),
    )

    @Test fun loginSendsTypeLoginAndReturnsAuthKey() = runTest {
        val key = api("""{"result":{"authKey":"AK","user":{"_id":"u"}}}""").login("a@b.c", "pw")
        assertEquals("AK", key)
        assertEquals("/api/login", paths.single())
        assertEquals("Login", body["type"]!!.jsonPrimitive.content)
        assertEquals("a@b.c", body["email"]!!.jsonPrimitive.content)
        assertEquals(false, body["facebook"]!!.jsonPrimitive.boolean)
    }

    @Test fun errorOrHttpFailureReturnsNull() = runTest {
        assertNull(api("""{"error":{"message":"User not found","code":1}}""").login("a", "b"))
        assertNull(api("{}", HttpStatusCode.InternalServerError).login("a", "b"))
        assertNull(api("not json").library("AK"))
    }

    @Test fun libraryRequestsAllItemsOfLibraryItemCollection() = runTest {
        val items = api("""{"result":[{"_id":"tt1"},{"_id":"tt2"}]}""").library("AK")
        assertEquals(2, items!!.size)
        assertEquals("/api/datastoreGet", paths.single())
        assertEquals("libraryItem", body["collection"]!!.jsonPrimitive.content)
        assertEquals(true, body["all"]!!.jsonPrimitive.boolean)
        assertEquals("AK", body["authKey"]!!.jsonPrimitive.content)
    }

    @Test fun emptyLibraryIsDifferentFromFailure() = runTest {
        assertEquals(emptyList(), api("""{"result":[]}""").library("AK"))
    }

    @Test fun putSendsChanges() = runTest {
        assertTrue(api("""{"result":{"success":true}}""").putLibrary("AK", listOf(buildJsonObject { put("_id", "tt1") })))
        assertEquals("/api/datastorePut", paths.single())
        assertEquals(1, body["changes"]!!.jsonArray.size)
    }
}
