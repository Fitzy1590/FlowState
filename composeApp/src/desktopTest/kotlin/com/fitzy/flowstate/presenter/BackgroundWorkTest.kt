package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.background.*
import com.fitzy.flowstate.data.Catalog
import com.fitzy.flowstate.data.local.LibraryStatus
import com.fitzy.flowstate.data.tmdb.*
import com.fitzy.flowstate.data.tmdb.withTmdbJson
import com.fitzy.flowstate.domain.*
import com.fitzy.flowstate.secrets.InMemorySecretStore
import com.fitzy.flowstate.sync.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BackgroundWorkTest {
    private val clock = Clock()
    private val repo = newRepo(clock)
    private val today = NewEpisodeLogic.todayIso(clock.now)
    private val show1 = TmdbTitle(TitleId(MediaType.SHOW, 1), "Airing Today", 2020, null, setOf(18), 8.0)
    private val show2 = TmdbTitle(TitleId(MediaType.SHOW, 2), "Airing Later", 2020, null, setOf(18), 8.0)
    private val show3 = TmdbTitle(TitleId(MediaType.SHOW, 3), "Dropped", 2020, null, setOf(18), 8.0)

    private val catalog = object : Catalog {
        override suspend fun pool() = emptyList<Candidate>()
        override suspend fun seasonEpisodes(show: TitleId, season: Int) = emptyList<EpisodeInfo>()
        override suspend fun seasonTrailerKey(show: TitleId, season: Int): String? = null
        override suspend fun details(id: TitleId): TmdbDetails? {
            val date = when (id.tmdbId) { 1L, 3L -> today; else -> "2999-01-01" }
            return TmdbDetails(TmdbTitle(id, "x", 2020, null, emptySet(), 0.0), null, emptyList(), emptySet(), null, nextEpisode = UpcomingEpisode(2, 5, "Ep", date))
        }
    }

    private val stremio = StremioSync(
        StremioApi(HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }).withTmdbJson()), repo, { null }, { null }, clock,
    ) // not connected: sync returns NotConnected
    private val upcoming = UpcomingService(repo, catalog)
    private val work = BackgroundWork(repo, stremio, upcoming, clock)

    private fun setup() {
        repo.addToLibrary(show1, status = LibraryStatus.WATCHING)
        repo.addToLibrary(show2, status = LibraryStatus.WATCHING)
        repo.addToLibrary(show3, status = LibraryStatus.DROPPED)
    }

    @Test fun upcomingListsNextEpisodesSoonestFirstAndSkipsDropped() = runTest {
        setup()
        val items = upcoming.load()
        assertEquals(listOf(1L, 2L), items.map { it.show.id.tmdbId })
        assertEquals(today, items.first().episode.airDate)
    }

    @Test fun nothingIsNotifiedWhileTheSettingIsOff() = runTest {
        setup()
        assertTrue(work.run().newEpisodes.isEmpty())
    }

    @Test fun notifiesOncePerEpisodeEvenIfTheJobRunsAgain() = runTest {
        setup(); repo.putSetting(NOTIFICATIONS_KEY, "true")
        val first = work.run()
        assertEquals(SyncResult.NotConnected, first.sync)
        assertEquals(listOf(1L), first.newEpisodes.map { it.show.id.tmdbId })
        assertTrue(work.run().newEpisodes.isEmpty())
    }

    @Test fun libraryPresenterExposesUpcoming() = runTest {
        setup()
        val p = com.fitzy.flowstate.presenter.LibraryPresenter(repo, upcoming, CoroutineScope(Dispatchers.Unconfined))
        p.refresh()
        assertEquals(3, p.items.value.size)
        assertEquals(2, p.upcoming.value.size)
    }

    @Test fun notificationSwitchAsksPermissionAndStaysOffWhenRefused() {
        var enabled = false
        var granted = false
        val s = SettingsPresenter(
            InMemorySecretStore(), getNotifications = { enabled }, setNotifications = { enabled = it },
            requestNotificationPermission = { reply -> reply(granted) }, onChanged = {},
        )
        s.setNotificationsEnabled(true)
        assertFalse(s.state.value.notifications)
        assertTrue("blocked" in s.state.value.message!!)
        granted = true
        s.setNotificationsEnabled(true)
        assertTrue(s.state.value.notifications)
        s.setNotificationsEnabled(false)
        assertFalse(s.state.value.notifications)
    }
}
