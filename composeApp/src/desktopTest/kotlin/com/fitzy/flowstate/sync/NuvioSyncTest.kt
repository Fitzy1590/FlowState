package com.fitzy.flowstate.sync

import com.fitzy.flowstate.data.Catalog
import com.fitzy.flowstate.data.local.LibraryStatus
import com.fitzy.flowstate.data.tmdb.*
import com.fitzy.flowstate.domain.*
import com.fitzy.flowstate.presenter.Clock
import com.fitzy.flowstate.presenter.newRepo
import com.fitzy.flowstate.secrets.InMemorySecretStore
import com.fitzy.flowstate.sync.nuvio.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** A tiny in-memory stand-in for a Nuvio server, so the whole sync can be exercised. */
class FakeNuvioServer {
    val watched = mutableListOf<JsonObject>()
    val progress = mutableListOf<JsonObject>()
    val watchedPushes = mutableListOf<JsonObject>()
    val progressPushes = mutableListOf<JsonObject>()
    var failPull = false
    var failPush = false
    var authFailuresLeft = 0
    var tokenRequests = 0
    var lastAuth: String? = null

    val engine = MockEngine { req ->
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val path = req.url.encodedPath
        val body = (req.body as? TextContent)?.text?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
        lastAuth = req.headers["Authorization"]
        when {
            path.endsWith("/auth/v1/token") -> {
                tokenRequests++
                respond("""{"access_token":"A${tokenRequests}","refresh_token":"R${tokenRequests}","expires_in":3600}""", HttpStatusCode.OK, json)
            }
            authFailuresLeft > 0 && path.contains("/rpc/") -> { authFailuresLeft--; respond("{}", HttpStatusCode.Unauthorized, json) }
            path.endsWith("/rpc/sync_pull_profiles") -> respond("""[{"profile_index":1,"name":"Main"},{"profile_index":2,"name":"Kids"}]""", HttpStatusCode.OK, json)
            path.endsWith("/rpc/sync_pull_watched_items") ->
                if (failPull) respond("{}", HttpStatusCode.InternalServerError, json)
                else respond(JsonArray(if (body["p_page"]!!.jsonPrimitive.int == 1) watched else emptyList()).toString(), HttpStatusCode.OK, json)
            path.endsWith("/rpc/sync_pull_watch_progress") ->
                if (failPull) respond("{}", HttpStatusCode.InternalServerError, json) else respond(JsonArray(progress).toString(), HttpStatusCode.OK, json)
            path.endsWith("/rpc/sync_push_watched_items") ->
                if (failPush) respond("{}", HttpStatusCode.InternalServerError, json)
                else { watchedPushes += body; body["p_items"]!!.jsonArray.forEach { watched += it.jsonObject }; respond("null", HttpStatusCode.OK, json) }
            path.endsWith("/rpc/sync_push_watch_progress") -> { progressPushes += body; respond("null", HttpStatusCode.OK, json) }
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    }

    fun watchedRow(id: String, type: String, season: Int?, episode: Int?, at: Long, title: String = "T") = buildJsonObject {
        put("content_id", id); put("content_type", type); put("title", title); put("season", season); put("episode", episode); put("watched_at", at)
    }

    fun progressRow(id: String, position: Long, duration: Long, at: Long) = buildJsonObject {
        put("content_id", id); put("content_type", "movie"); put("video_id", id); put("season", null as Int?); put("episode", null as Int?)
        put("position", position); put("duration", duration); put("last_watched", at); put("progress_key", id)
    }
}

class NuvioSyncTest {
    private val clock = Clock(1_800_000_000_000)
    private val repo = newRepo(clock)
    private val server = FakeNuvioServer()
    private val secrets = InMemorySecretStore()
    private val store = NuvioAccountStore(secrets)
    private val http = HttpClient(server.engine).withTmdbJson()
    private val api = NuvioApi(http, store, { "flowstate-test-client-id-1234567890" }, clock)

    private val ryan = TmdbTitle(TitleId(MediaType.MOVIE, 857), "Saving Private Ryan", 1998, null, setOf(18), 8.2)
    private val bob = TmdbTitle(TitleId(MediaType.SHOW, 4613), "Band of Brothers", 2001, null, setOf(18), 8.5)
    private val titles = mapOf("tt0120815" to ryan, "tt0185906" to bob)

    private val catalog = object : Catalog {
        override suspend fun pool() = emptyList<Candidate>()
        override suspend fun seasonEpisodes(show: TitleId, season: Int) = emptyList<EpisodeInfo>()
        override suspend fun seasonTrailerKey(show: TitleId, season: Int): String? = null
        override suspend fun details(id: TitleId): TmdbDetails? = titles.entries.firstOrNull { it.value.id == id }?.let { (imdb, t) ->
            TmdbDetails(t, imdb, emptyList(), emptySet(), null, seasons = if (id.type == MediaType.SHOW) listOf(SeasonInfo(1, 4, "S1")) else emptyList())
        }
    }

    private fun sync(resolve: Boolean = true) = NuvioSync(api, store, repo, catalog, { if (resolve) titles[it] else null }, clock)

    private fun connect(profile: Int = 1) = store.save(NuvioAccount("https://n.example.com", "pk", "A0", "R0", clock.now + 3_600_000, profile, "Main"))

    @Test fun notConnectedDoesNothing() = runTest { assertEquals(SyncResult.NotConnected, sync().sync()) }

    @Test fun remoteMovieAndEpisodesAreAddedToTheLibrary() = runTest {
        connect()
        server.watched += server.watchedRow("tt0120815", "movie", null, null, 1_700_000_000_000)
        server.watched += listOf(1, 2).map { server.watchedRow("tt0185906", "series", 1, it, 1_700_000_000_000L + it) }
        val r = sync().sync() as SyncResult.Done
        assertEquals(3, r.pulled); assertEquals(0, r.pushed); assertEquals(0, r.skipped)
        assertEquals(LibraryStatus.COMPLETED.name, repo.libraryStatus(ryan.id))
        assertEquals(setOf(1 to 1, 1 to 2), repo.watchedEpisodes(bob.id))
        val show = repo.libraryItems().first { it.candidate.id == bob.id }
        assertEquals(LibraryStatus.WATCHING, show.status)
        assertEquals(0.5, show.completion, 1e-9) // 2 of 4 episodes
    }

    @Test fun finishingEverySeasonMarksTheShowCompleted() = runTest {
        connect()
        (1..4).forEach { server.watched += server.watchedRow("tt0185906", "series", 1, it, 1_700_000_000_000L) }
        sync().sync()
        assertEquals(LibraryStatus.COMPLETED.name, repo.libraryStatus(bob.id))
    }

    @Test fun localWatchedMoviesAndEpisodesArePushedWithTheOriginClient() = runTest {
        connect()
        repo.addToLibrary(ryan, "tt0120815"); repo.setProgressAt(ryan.id, LibraryStatus.COMPLETED, 1.0, 1_750_000_000_000)
        repo.addToLibrary(bob, "tt0185906"); repo.markEpisodeWatchedAt(bob.id, 1, 3, 1_760_000_000_000)
        val r = sync().sync() as SyncResult.Done
        assertEquals(2, r.pushed)
        val items = server.watchedPushes.single()["p_items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("tt0120815", "tt0185906"), items.map { it["content_id"]!!.jsonPrimitive.content }.toSet())
        val ep = items.first { it["content_id"]!!.jsonPrimitive.content == "tt0185906" }
        assertEquals("series", ep["content_type"]!!.jsonPrimitive.content)
        assertEquals(3, ep["episode"]!!.jsonPrimitive.int); assertEquals(1, ep["season"]!!.jsonPrimitive.int)
        assertEquals("flowstate-test-client-id-1234567890", server.watchedPushes.single()["p_origin_client_id"]!!.jsonPrimitive.content)
        assertEquals(1, server.watchedPushes.single()["p_profile_id"]!!.jsonPrimitive.int)
    }

    @Test fun bothSidesEndUpWithTheUnionAndASecondSyncChangesNothing() = runTest {
        connect()
        repo.addToLibrary(bob, "tt0185906"); repo.markEpisodeWatchedAt(bob.id, 1, 1, 1_700_000_000_001)
        server.watched += server.watchedRow("tt0185906", "series", 1, 2, 1_700_000_000_002)
        val first = sync().sync() as SyncResult.Done
        assertEquals(1, first.pushed); assertEquals(1, first.pulled)
        assertEquals(setOf(1 to 1, 1 to 2), repo.watchedEpisodes(bob.id))
        assertEquals(2, server.watched.count { it["content_id"]!!.jsonPrimitive.content == "tt0185906" })
        assertEquals(SyncResult.Done(0, 0), sync().sync())
    }

    @Test fun nothingIsEverDeletedOnEitherSide() = runTest {
        connect()
        repo.addToLibrary(ryan, "tt0120815"); repo.setProgressAt(ryan.id, LibraryStatus.COMPLETED, 1.0, 1)
        sync().sync() // an empty server gets the movie, the movie stays
        assertEquals(1, server.watched.size)
        repo.removeFromLibrary(ryan.id)
        sync().sync() // locally gone: it is pulled back, never deleted remotely
        assertEquals(1, server.watched.size)
    }

    @Test fun nonImdbIdsAreIgnoredAndUnidentifiableTitlesAreReported() = runTest {
        connect()
        server.watched += server.watchedRow("kitsu:123", "series", 1, 1, 1)
        server.watched += server.watchedRow("tt9999999", "movie", null, null, 1)
        val r = sync().sync() as SyncResult.Done
        assertEquals(0, r.pulled); assertEquals(1, r.skipped) // kitsu ignored, tt9999999 reported
        assertTrue(repo.libraryIds().isEmpty())
    }

    @Test fun withoutTmdbEverythingPulledIsReportedAsSkipped() = runTest {
        connect()
        server.watched += server.watchedRow("tt0120815", "movie", null, null, 1)
        assertEquals(SyncResult.Done(0, 0, skipped = 1), sync(resolve = false).sync())
    }

    @Test fun aFailedFetchChangesNothingAnywhere() = runTest {
        connect()
        repo.addToLibrary(ryan, "tt0120815"); repo.setProgressAt(ryan.id, LibraryStatus.COMPLETED, 1.0, 1)
        server.failPull = true
        assertEquals(SyncResult.Failed, sync().sync())
        assertTrue(server.watchedPushes.isEmpty())
        assertEquals(LibraryStatus.COMPLETED.name, repo.libraryStatus(ryan.id))
    }

    @Test fun aFailedPushIsReported() = runTest {
        connect(); repo.addToLibrary(ryan, "tt0120815"); repo.setProgressAt(ryan.id, LibraryStatus.COMPLETED, 1.0, 1)
        server.failPush = true
        assertEquals(SyncResult.Failed, sync().sync())
    }

    @Test fun aShowAddedWithoutAnImdbIdIsMatchedThroughTheCatalog() = runTest {
        connect()
        repo.addToLibrary(bob) // no imdb id, as when added from the episode list
        repo.markEpisodeWatchedAt(bob.id, 1, 1, 1_700_000_000_000)
        assertEquals(SyncResult.Done(1, 0), sync().sync())
        assertEquals("tt0185906", server.watched.single()["content_id"]!!.jsonPrimitive.content)
    }

    @Test fun movieProgressNewerHereIsPushedAndNewerThereIsPulled() = runTest {
        connect()
        repo.addToLibrary(ryan, "tt0120815"); repo.setProgressAt(ryan.id, LibraryStatus.WATCHING, 0.5, 1_750_000_000_000)
        server.progress += server.progressRow("tt0120815", 1_000_000, 7_200_000, 1_700_000_000_000) // older
        val r = sync().sync() as SyncResult.Done
        assertEquals(1, r.pushed)
        val e = server.progressPushes.single()["p_entries"]!!.jsonArray.single().jsonObject
        assertEquals(3_600_000L, e["position"]!!.jsonPrimitive.long)
        assertEquals("tt0120815", e["progress_key"]!!.jsonPrimitive.content)

        // now the server has the newer one
        server.progress.clear(); server.progressPushes.clear()
        server.progress += server.progressRow("tt0120815", 5_760_000, 7_200_000, 1_790_000_000_000)
        assertEquals(SyncResult.Done(0, 1), sync().sync())
        assertEquals(0.8, repo.syncCandidates().single().completion, 1e-9) // 5.76M / 7.2M
    }

    @Test fun aMovieAtNinetyPercentThereCountsAsWatchedHere() = runTest {
        connect()
        server.progress += server.progressRow("tt0120815", 6_480_000, 7_200_000, 1_700_000_000_000) // exactly 90%
        assertEquals(SyncResult.Done(0, 1), sync().sync())
        assertEquals(LibraryStatus.COMPLETED.name, repo.libraryStatus(ryan.id))
        assertEquals(SyncResult.Done(0, 0), sync().sync()) // and it is not pushed back as a second mark
    }

    @Test fun anExpiredLoginIsRefreshedBeforeCalling() = runTest {
        store.save(NuvioAccount("https://n.example.com", "pk", "OLD", "R0", clock.now - 1000, 1, "Main")) // already expired
        sync().sync()
        assertEquals(1, server.tokenRequests)
        assertEquals("Bearer A1", server.lastAuth)
        assertEquals("A1", store.load()!!.accessToken)
        assertEquals(1, store.load()!!.profileIndex) // refresh keeps the chosen profile
    }

    @Test fun aRejectedTokenIsRefreshedOnceAndRetried() = runTest {
        connect(); server.authFailuresLeft = 1
        server.watched += server.watchedRow("tt0120815", "movie", null, null, 1)
        assertEquals(SyncResult.Done(0, 1), sync().sync())
        assertEquals(1, server.tokenRequests)
    }

    @Test fun signInReturnsAnAccountAndProfilesAreListed() = runTest {
        val a = api.signIn(NuvioServer("https://n.example.com", "pk"), "me@x.com", "pw")!!
        assertEquals("A1", a.accessToken); assertEquals("https://n.example.com", a.backendUrl)
        store.save(a)
        assertEquals(listOf(NuvioProfile(1, "Main"), NuvioProfile(2, "Kids")), api.profiles())
    }

    @Test fun theSelectedProfileIdIsSent() = runTest {
        connect(profile = 2)
        repo.addToLibrary(ryan, "tt0120815"); repo.setProgressAt(ryan.id, LibraryStatus.COMPLETED, 1.0, 1)
        sync().sync()
        assertEquals(2, server.watchedPushes.single()["p_profile_id"]!!.jsonPrimitive.int)
    }
}
