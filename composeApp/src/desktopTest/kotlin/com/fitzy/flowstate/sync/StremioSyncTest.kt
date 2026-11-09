package com.fitzy.flowstate.sync

import com.fitzy.flowstate.data.local.LibraryStatus
import com.fitzy.flowstate.data.tmdb.TmdbTitle
import com.fitzy.flowstate.data.tmdb.withTmdbJson
import com.fitzy.flowstate.domain.MediaType
import com.fitzy.flowstate.domain.TitleId
import com.fitzy.flowstate.presenter.Clock
import com.fitzy.flowstate.presenter.newRepo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class StremioSyncTest {
    private val clock = Clock(1_800_000_000_000)
    private val repo = newRepo(clock)
    private var remote: MutableList<JsonObject> = mutableListOf()
    private var fetchFails = false
    private var putFails = false
    private val puts = mutableListOf<JsonObject>()
    private var key: String? = "AK"

    private val api = StremioApi(HttpClient(MockEngine { req ->
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when {
            req.url.encodedPath.endsWith("datastoreGet") ->
                if (fetchFails) respond("{}", HttpStatusCode.InternalServerError, json)
                else respond(buildJsonObject { put("result", JsonArray(remote)) }.toString(), HttpStatusCode.OK, json)
            else -> {
                if (putFails) respond("{}", HttpStatusCode.InternalServerError, json)
                else {
                    puts += Json.parseToJsonElement((req.body as TextContent).text).jsonObject["changes"]!!.jsonArray.map { it.jsonObject }
                    respond("""{"result":{"success":true}}""", HttpStatusCode.OK, json)
                }
            }
        }
    }).withTmdbJson())

    private val ryan = TmdbTitle(TitleId(MediaType.MOVIE, 857), "Saving Private Ryan", 1998, "/a.jpg", setOf(18), 8.2)
    private val resolver = TitleResolver { if (it == "tt0120815") ryan else null }
    private fun sync() = StremioSync(api, repo, resolver, { key }, clock)

    private fun remoteMovie(id: String, offset: Long, duration: Long, lastWatched: Long) = buildJsonObject {
        put("_id", id); put("name", "R-$id"); put("type", "movie"); put("removed", false); put("temp", false)
        put("_mtime", StremioItems.iso(lastWatched))
        putJsonObject("state") { put("lastWatched", StremioItems.iso(lastWatched)); put("timeOffset", offset); put("duration", duration); put("flaggedWatched", 0); put("watched", "keep-me") }
    }

    @Test fun notConnectedDoesNothing() = runTest {
        key = null
        assertEquals(SyncResult.NotConnected, sync().sync())
        assertTrue(puts.isEmpty())
    }

    @Test fun failedFetchChangesNothingOnEitherSide() = runTest {
        repo.addToLibrary(ryan, "tt0120815"); repo.setProgress(ryan.id, LibraryStatus.WATCHING, 0.4)
        fetchFails = true
        assertEquals(SyncResult.Failed, sync().sync())
        assertTrue(puts.isEmpty())
        assertEquals(setOf(ryan.id), repo.libraryIds())
    }

    @Test fun remoteOnlyMovieIsPulledIntoLibraryWithItsProgressAndTimestamp() = runTest {
        remote += remoteMovie("tt0120815", 5_400_000, 10_800_000, lastWatched = 1_700_000_000_000)
        val r = sync().sync() as SyncResult.Done
        assertEquals(1, r.pulled); assertEquals(0, r.pushed)
        val row = repo.syncCandidates().single()
        assertEquals(ryan.id, row.id)
        assertEquals(LibraryStatus.WATCHING, row.status)
        assertEquals(0.5, row.completion, 1e-9)
        assertEquals(1_700_000_000_000, row.lastActivityAt) // pulled time, so the next sync sees them as equal
        assertEquals(SyncResult.Done(0, 0), sync().sync()) // no ping-pong
    }

    @Test fun unresolvableRemoteItemIsSkippedNotFailed() = runTest {
        remote += remoteMovie("tt9999999", 1, 10, 1_700_000_000_000)
        assertEquals(SyncResult.Done(0, 0, skipped = 1), sync().sync()) // reported, not hidden
        assertTrue(repo.libraryIds().isEmpty())
        assertEquals("skipped (not matched to TMDB)", repo.recentSyncEvents().first().action)
    }

    @Test fun newerLocalMovieIsPushedKeepingRemoteFields() = runTest {
        remote += remoteMovie("tt0120815", 1_000, 10_800_000, lastWatched = 1_700_000_000_000)
        repo.addToLibrary(ryan, "tt0120815")
        repo.setProgressAt(ryan.id, LibraryStatus.COMPLETED, 1.0, 1_750_000_000_000)
        assertEquals(SyncResult.Done(1, 0), sync().sync())
        val sent = puts.single()
        assertEquals("keep-me", sent["state"]!!.jsonObject["watched"]!!.jsonPrimitive.content)
        assertEquals(1, sent["state"]!!.jsonObject["flaggedWatched"]!!.jsonPrimitive.int)
    }

    @Test fun olderLocalMovieIsOverwrittenByRemote() = runTest {
        repo.addToLibrary(ryan, "tt0120815")
        repo.setProgressAt(ryan.id, LibraryStatus.WATCHING, 0.9, 1_600_000_000_000)
        remote += remoteMovie("tt0120815", 3_000_000, 10_000_000, lastWatched = 1_700_000_000_000)
        assertEquals(SyncResult.Done(0, 1), sync().sync())
        assertEquals(0.3, repo.syncCandidates().single().completion, 1e-9)
        assertTrue(puts.isEmpty())
    }

    @Test fun localOnlyMovieIsCreatedRemotely() = runTest {
        repo.addToLibrary(ryan, "tt0120815"); repo.setProgress(ryan.id, LibraryStatus.COMPLETED, 1.0)
        assertEquals(SyncResult.Done(1, 0), sync().sync())
        assertEquals("tt0120815", puts.single()["_id"]!!.jsonPrimitive.content)
    }

    @Test fun failedPushReportsFailure() = runTest {
        repo.addToLibrary(ryan, "tt0120815"); repo.setProgress(ryan.id, LibraryStatus.COMPLETED, 1.0)
        putFails = true
        assertEquals(SyncResult.Failed, sync().sync())
    }

    @Test fun syncIsLogged() = runTest {
        remote += remoteMovie("tt0120815", 1, 10, 1_700_000_000_000)
        sync().sync()
        assertEquals("pulled", repo.recentSyncEvents().single().action)
    }
}

class StremioBigLibraryTest {
    private val clock = Clock(1_800_000_000_000)
    private val repo = newRepo(clock)

    @Test fun aLargeLibraryIsLookedUpInParallelAndSkippedTitlesAreCountedButOnlyFewLogged() = runTest {
        val items = (1..200).map { i ->
            buildJsonObject {
                put("_id", "tt%07d".format(i)); put("name", "Title $i"); put("type", "movie"); put("removed", false); put("temp", false)
                put("_mtime", StremioItems.iso(1_700_000_000_000)); putJsonObject("state") { put("timeOffset", 0); put("duration", 0); put("flaggedWatched", 0) }
            }
        }
        var inFlight = 0; var maxInFlight = 0
        val resolver = TitleResolver { imdb ->
            inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
            kotlinx.coroutines.delay(5)
            inFlight--
            val n = imdb.removePrefix("tt").toLong()
            if (n % 2 == 0L) null else TmdbTitle(TitleId(MediaType.MOVIE, n), "Title $n", 2020, null, setOf(18), 7.0) // half are unknown to TMDB
        }
        val api = StremioApi(HttpClient(MockEngine {
            respond(buildJsonObject { put("result", JsonArray(items)) }.toString(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }).withTmdbJson())
        val r = StremioSync(api, repo, resolver, { "AK" }, clock).sync() as SyncResult.Done
        assertEquals(100, r.pulled); assertEquals(100, r.skipped)
        assertEquals(100, repo.libraryIds().size)
        assertTrue(maxInFlight in 2..8, "lookups ran $maxInFlight at a time")
        assertEquals(10, repo.recentSyncEvents(500).count { it.action.startsWith("skipped") })
    }
}
