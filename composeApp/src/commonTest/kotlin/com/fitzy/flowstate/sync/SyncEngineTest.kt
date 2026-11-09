package com.fitzy.flowstate.sync

import com.fitzy.flowstate.domain.MediaType
import kotlinx.serialization.json.*
import kotlin.test.*

class SyncEngineTest {
    private fun item(id: String, ts: Long, type: MediaType = MediaType.MOVIE, c: Double = 0.5, done: Boolean = false) =
        SyncItem(id, type, "n-$id", c, done, ts)

    @Test fun latestActivityWinsInBothDirections() {
        val plan = SyncEngine.plan(
            local = listOf(item("tt1", 200), item("tt2", 100)),
            remote = listOf(item("tt1", 100), item("tt2", 200)),
        ).associateBy { it.item.imdbId }
        assertEquals(SyncAction.PUSH, plan.getValue("tt1").action)
        assertEquals(SyncAction.PULL, plan.getValue("tt2").action)
    }

    @Test fun equalTimestampsChangeNothing() {
        assertTrue(SyncEngine.plan(listOf(item("tt1", 100)), listOf(item("tt1", 100))).isEmpty())
    }

    @Test fun oneSidedItemsAreCopiedAcross() {
        val plan = SyncEngine.plan(listOf(item("tt1", 1)), listOf(item("tt2", 1)))
        assertEquals(setOf("tt1" to SyncAction.PUSH, "tt2" to SyncAction.PULL), plan.map { it.item.imdbId to it.action }.toSet())
    }

    @Test fun showsAreNeverPushed() {
        val show = item("tt9", 500, MediaType.SHOW)
        assertTrue(SyncEngine.plan(listOf(show), emptyList()).isEmpty())
        assertTrue(SyncEngine.plan(listOf(show), listOf(item("tt9", 100, MediaType.SHOW))).isEmpty()) // local newer, still no push
        assertEquals(SyncAction.PULL, SyncEngine.plan(listOf(item("tt9", 100, MediaType.SHOW)), listOf(show)).single().action)
    }

    @Test fun emptyRemoteNeverDeletesLocalAndEmptyLocalNeverDeletesRemote() {
        val local = listOf(item("tt1", 1), item("tt2", 2))
        assertTrue(SyncEngine.plan(local, emptyList()).all { it.action == SyncAction.PUSH })
        assertTrue(SyncEngine.plan(emptyList(), local).all { it.action == SyncAction.PULL })
    }
}

class ForceSyncTest {
    private fun item(id: String, ts: Long, c: Double, type: MediaType = MediaType.MOVIE) = SyncItem(id, type, "n", c, c >= 1.0, ts)

    @Test fun forcePullTakesRemoteEvenWhenLocalIsNewer() {
        val plan = SyncEngine.plan(listOf(item("tt1", 500, 0.9)), listOf(item("tt1", 100, 0.2)), prefer = Prefer.REMOTE)
        assertEquals(SyncAction.PULL, plan.single().action)
    }

    @Test fun forcePushSendsLocalEvenWhenRemoteIsNewer() {
        val plan = SyncEngine.plan(listOf(item("tt1", 100, 0.9)), listOf(item("tt1", 500, 0.2)), prefer = Prefer.LOCAL)
        assertEquals(SyncAction.PUSH, plan.single().action)
    }

    @Test fun forcedModesLeaveIdenticalItemsAloneAndStillNeverPushShows() {
        assertTrue(SyncEngine.plan(listOf(item("tt1", 1, 0.5)), listOf(item("tt1", 9, 0.5)), prefer = Prefer.REMOTE).isEmpty())
        assertTrue(SyncEngine.plan(listOf(item("tt2", 1, 0.9, MediaType.SHOW)), listOf(item("tt2", 9, 0.1, MediaType.SHOW)), prefer = Prefer.LOCAL).isEmpty())
    }

    @Test fun forcedModesStillCopyOneSidedItemsAndNeverDelete() {
        val plan = SyncEngine.plan(listOf(item("tt1", 1, 0.5)), listOf(item("tt2", 1, 0.5)), prefer = Prefer.REMOTE)
        assertEquals(setOf("tt1" to SyncAction.PUSH, "tt2" to SyncAction.PULL), plan.map { it.item.imdbId to it.action }.toSet())
    }
}

class StremioItemsTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private val movie = obj("""{"_id":"tt0120815","name":"Saving Private Ryan","type":"movie","removed":false,"temp":false,
        "_mtime":"2024-01-01T00:00:00.000Z","behaviorHints":{"defaultVideoId":"tt0120815"},
        "state":{"lastWatched":"2024-03-01T10:00:00.000Z","timeOffset":3600000,"duration":10800000,"flaggedWatched":0,"watched":"abc:3:def","noNotif":true}}""")

    @Test fun mapsProgressAndTimes() {
        val s = StremioItems.toSync(movie)!!
        assertEquals("tt0120815", s.imdbId); assertEquals(MediaType.MOVIE, s.type)
        assertEquals(1.0 / 3, s.completion, 1e-9); assertFalse(s.completed)
        assertEquals(StremioItems.parseTime("2024-03-01T10:00:00.000Z"), s.lastActivityAt)
    }

    @Test fun flaggedOrNearEndCountsAsCompleted() {
        assertTrue(StremioItems.toSync(obj("""{"_id":"tt1","type":"movie","removed":false,"_mtime":"2024-01-01T00:00:00Z","state":{"flaggedWatched":1}}"""))!!.completed)
        assertTrue(StremioItems.toSync(obj("""{"_id":"tt1","type":"movie","removed":false,"_mtime":"2024-01-01T00:00:00Z","state":{"timeOffset":95,"duration":100}}"""))!!.completed)
    }

    @Test fun fallsBackToModifiedTimeWhenNeverWatched() {
        val s = StremioItems.toSync(obj("""{"_id":"tt1","type":"series","removed":false,"_mtime":"2024-01-01T00:00:00Z","state":{"lastWatched":null}}"""))!!
        assertEquals(StremioItems.parseTime("2024-01-01T00:00:00Z"), s.lastActivityAt)
        assertEquals(MediaType.SHOW, s.type)
    }

    @Test fun skipsRemovedNonImdbAndOtherTypes() {
        assertNull(StremioItems.toSync(obj("""{"_id":"tt1","type":"movie","removed":true,"_mtime":"2024-01-01T00:00:00Z","state":{}}""")))
        assertNull(StremioItems.toSync(obj("""{"_id":"kitsu:1","type":"series","removed":false,"_mtime":"2024-01-01T00:00:00Z","state":{}}""")))
        assertNull(StremioItems.toSync(obj("""{"_id":"tt1","type":"other","removed":false,"_mtime":"2024-01-01T00:00:00Z","state":{}}""")))
    }

    @Test fun pushKeepsFieldsItDoesNotUnderstand() {
        val local = SyncItem("tt0120815", MediaType.MOVIE, "x", 1.0, true, 1_700_000_000_000)
        val out = StremioItems.withLocalProgress(movie, local, 1_800_000_000_000)
        val st = out["state"]!!.jsonObject
        assertEquals("abc:3:def", st["watched"]!!.jsonPrimitive.content) // episode bitfield untouched
        assertEquals(true, st["noNotif"]!!.jsonPrimitive.boolean)
        assertEquals(1, st["flaggedWatched"]!!.jsonPrimitive.int)
        assertEquals(10_800_000L, st["timeOffset"]!!.jsonPrimitive.long) // completed = full duration
        assertEquals(movie["behaviorHints"], out["behaviorHints"])
        assertEquals(StremioItems.iso(1_800_000_000_000), out["_mtime"]!!.jsonPrimitive.content)
        assertEquals(local.lastActivityAt, StremioItems.parseTime(st["lastWatched"]!!.jsonPrimitive.content))
    }

    @Test fun newMovieHasTheFieldsStremioRequires() {
        val o = StremioItems.newMovie(SyncItem("tt5", MediaType.MOVIE, "Film", 1.0, true, 1_700_000_000_000), 1_800_000_000_000)
        listOf("_id", "name", "type", "removed", "temp", "_mtime", "state").forEach { assertTrue(it in o, it) }
        listOf("timeWatched", "timeOffset", "overallTimeWatched", "timesWatched", "flaggedWatched", "duration").forEach { assertTrue(it in o["state"]!!.jsonObject, it) }
        assertEquals(false, o["removed"]!!.jsonPrimitive.boolean)
        assertEquals(StremioItems.toSync(o)!!.completed, true)
    }
}
