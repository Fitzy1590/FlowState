package com.fitzy.flowstate.data

import com.fitzy.flowstate.data.tmdb.withTmdbJson
import com.fitzy.flowstate.secrets.InMemorySecretStore
import com.fitzy.flowstate.secrets.SecretNames
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SecretAwareCatalogTest {
    private val auth = mutableListOf<String?>()
    private val http = HttpClient(MockEngine { req ->
        auth += req.headers["Authorization"]
        respond(
            """{"results":[{"id":1,"title":"From TMDB","genre_ids":[28]}]}""",
            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }).withTmdbJson()

    private fun catalog(secrets: InMemorySecretStore) = SecretAwareCatalog(secrets, http, seeds = { emptySet() }, clock = { 0 })

    @Test fun usesSampleListWithoutToken() = runTest {
        val pool = catalog(InMemorySecretStore()).pool()
        assertTrue(pool.any { it.name == "Saving Private Ryan" })
        assertTrue(auth.isEmpty())
    }

    @Test fun usesTmdbOnceTokenIsSavedAndReturnsToSampleWhenRemoved() = runTest {
        val secrets = InMemorySecretStore()
        val c = catalog(secrets)
        c.pool()
        secrets.put(SecretNames.TMDB, "t".repeat(100))
        assertTrue(c.pool().any { it.name == "From TMDB" })
        assertEquals("Bearer ${"t".repeat(100)}", auth.last())
        secrets.remove(SecretNames.TMDB)
        assertTrue(c.pool().any { it.name == "Saving Private Ryan" })
    }

    @Test fun newTokenBuildsFreshCatalogWithoutStaleCache() = runTest {
        val secrets = InMemorySecretStore()
        val c = catalog(secrets)
        secrets.put(SecretNames.TMDB, "a".repeat(100)); c.pool()
        secrets.put(SecretNames.TMDB, "b".repeat(100)); c.pool()
        assertEquals("Bearer ${"b".repeat(100)}", auth.last())
    }
}
