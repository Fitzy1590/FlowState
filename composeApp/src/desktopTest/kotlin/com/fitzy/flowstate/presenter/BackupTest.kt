package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.app.ActivityHooks
import com.fitzy.flowstate.app.FileTransfer
import com.fitzy.flowstate.backup.BackupService
import com.fitzy.flowstate.backup.ImportResult
import com.fitzy.flowstate.data.local.ActorPref
import com.fitzy.flowstate.data.local.LibraryStatus
import com.fitzy.flowstate.data.tmdb.TmdbTitle
import com.fitzy.flowstate.domain.*
import kotlin.test.*

class BackupTest {
    private val clock = Clock()
    private val source = newRepo(clock)
    private val title = TmdbTitle(TitleId(MediaType.MOVIE, 857), "Saving Private Ryan", 1998, "/a.jpg", setOf(18, 10752), 8.2)
    private val show = TmdbTitle(TitleId(MediaType.SHOW, 4613), "Band of Brothers", 2001, null, setOf(18), 8.5)

    private fun fill() {
        source.addToLibrary(title, "tt0120815"); source.setProgress(title.id, LibraryStatus.COMPLETED, 1.0); source.setRating(title.id, 91)
        source.addToLibrary(show, "tt0185906"); source.setProgress(show.id, LibraryStatus.WATCHING, 0.4)
        source.setEpisodeWatched(show.id, 1, 1, true); source.setEpisodeWatched(show.id, 1, 2, true)
        source.castVote(title.id, Vote.YES, VoteSource.SWIPE, TitleFeatures(setOf(18), setOf(7), listOf(1L, 2L)))
        source.setActorPref(1, "Tom Hanks", ActorPref.LIKED)
        source.putSetting("player.default", "NUVIO")
        source.putSetting("update.skippedVersion", "9.9.9") // not part of a backup
    }

    @Test fun exportThenImportRebuildsEverythingInAFreshDatabase() {
        fill()
        val text = BackupService(source, clock).export()
        val fresh = newRepo(clock)
        val r = BackupService(fresh, clock).import(text) as ImportResult.Done
        assertEquals(2, r.library); assertEquals(1, r.ratings); assertEquals(1, r.votes)
        assertEquals(setOf(title.id, show.id), fresh.libraryIds())
        assertEquals(91, fresh.rating(title.id))
        assertEquals(0.4, fresh.libraryItems().first { it.candidate.id == show.id }.completion, 1e-9)
        assertEquals(setOf(1 to 1, 1 to 2), fresh.watchedEpisodes(show.id))
        assertEquals(TitleFeatures(setOf(18), setOf(7), listOf(1L, 2L)), fresh.votes().single().features)
        assertEquals(setOf(1L), fresh.actorPrefs().liked)
        assertEquals("NUVIO", fresh.getSetting("player.default"))
        assertNull(fresh.getSetting("update.skippedVersion"))
    }

    @Test fun exportContainsNoSecretsOrSyncKeys() {
        fill(); source.putSetting("stremio.authKey", "SECRET-SESSION")
        val text = BackupService(source, clock).export()
        assertFalse("SECRET-SESSION" in text); assertFalse("authKey" in text); assertFalse("apiKey" in text)
    }

    @Test fun importingTwiceDoesNotDuplicate() {
        fill()
        val text = BackupService(source, clock).export()
        val fresh = newRepo(clock); val svc = BackupService(fresh, clock)
        svc.import(text); svc.import(text)
        assertEquals(2, fresh.libraryIds().size)
        assertEquals(1, fresh.votes().size)
    }

    @Test fun badFilesAreRejectedAndChangeNothing() {
        val fresh = newRepo(clock); val svc = BackupService(fresh, clock)
        assertTrue(svc.import("not json") is ImportResult.Invalid)
        assertTrue(svc.import("""{"format":"other","version":1,"exportedAt":1}""") is ImportResult.Invalid)
        assertTrue((svc.import("""{"format":"flowstate-backup","version":2,"exportedAt":1}""") as ImportResult.Invalid).reason.contains("newer"))
        val badScore = """{"format":"flowstate-backup","version":1,"exportedAt":1,"ratings":[{"type":"MOVIE","tmdbId":1,"score":150,"ratedAt":1}]}"""
        assertTrue(svc.import(badScore) is ImportResult.Invalid)
        val badStatus = """{"format":"flowstate-backup","version":1,"exportedAt":1,"library":[{"type":"MOVIE","tmdbId":1,"name":"x","status":"WEIRD","addedAt":1,"lastActivityAt":1}]}"""
        assertTrue(svc.import(badStatus) is ImportResult.Invalid)
        assertTrue(fresh.libraryIds().isEmpty())
    }

    @Test fun presenterSavesAndRestoresThroughTheFilePicker() {
        fill()
        var saved: String? = null
        val files = object : FileTransfer {
            override val available = true
            override fun save(suggestedName: String, text: String, onDone: (Boolean) -> Unit) { saved = text; onDone(true) }
            override fun open(onText: (String?) -> Unit) = onText(saved)
        }
        val hooks = ActivityHooks().also { it.files = files }
        var reloaded = 0
        val fresh = newRepo(clock)
        val p = BackupPresenter(BackupService(fresh, clock), hooks, clock) { reloaded++ }
        assertTrue(p.state.value.available)
        // export from the filled database via its own presenter, restore into the fresh one
        BackupPresenter(BackupService(source, clock), hooks, clock) {}.export()
        assertNotNull(saved)
        p.import()
        assertEquals(1, reloaded)
        assertTrue("Restored 2 titles" in p.state.value.message!!)
        assertEquals(2, fresh.libraryIds().size)
    }

    @Test fun cancelledPickerAndUnavailablePickerAreHandled() {
        val hooks = ActivityHooks()
        val p = BackupPresenter(BackupService(newRepo(clock), clock), hooks, clock) {}
        assertFalse(p.state.value.available)
        p.import(); assertEquals("No file was opened.", p.state.value.message)
        p.export(); assertEquals("The backup was not saved.", p.state.value.message)
    }
}
