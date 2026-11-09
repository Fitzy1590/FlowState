package com.fitzy.flowstate.episodes

import com.fitzy.flowstate.data.tmdb.SeasonInfo
import kotlin.test.*

class SeasonPlanTest {
    private val seasons = listOf(SeasonInfo(0, 3, "Specials"), SeasonInfo(1, 3, "S1"), SeasonInfo(2, 2, "S2"), SeasonInfo(3, 4, "S3"))
    private fun watched(vararg se: Pair<Int, Int>) = se.toSet()

    @Test fun notStartedOffersFirstSeasonTrailerOnly() {
        assertEquals(SeasonPlan(null, 1, null), SeasonPlan.of(seasons, emptySet()))
    }

    @Test fun partlyWatchedFirstSeasonOffersNothing() {
        assertEquals(SeasonPlan(null, null, null), SeasonPlan.of(seasons, watched(1 to 1, 1 to 2)))
    }

    @Test fun finishedSeasonOneOffersSeasonTwoTrailerAndSeasonOneRecap() {
        val p = SeasonPlan.of(seasons, watched(1 to 1, 1 to 2, 1 to 3))
        assertEquals(SeasonPlan(1, 2, 1), p)
    }

    @Test fun lastFinishedDrivesBoth() {
        val p = SeasonPlan.of(seasons, watched(1 to 1, 1 to 2, 1 to 3, 2 to 1, 2 to 2))
        assertEquals(SeasonPlan(2, 3, 2), p)
    }

    @Test fun finishedFinalSeasonHasNoNextTrailerButKeepsRecap() {
        val all = (1..3).map { 1 to it } + (1..2).map { 2 to it } + (1..4).map { 3 to it }
        assertEquals(SeasonPlan(3, null, 3), SeasonPlan.of(seasons, all.toSet()))
    }

    @Test fun specialsAreIgnored() {
        val p = SeasonPlan.of(seasons, watched(0 to 1, 0 to 2, 0 to 3))
        assertEquals(1, p.trailerSeason) // specials watched does not count as starting season 1
        assertNull(p.lastFinished)
    }

    @Test fun seasonsWithNoEpisodesCannotBeFinished() {
        assertEquals(SeasonPlan(null, null, null), SeasonPlan.of(listOf(SeasonInfo(1, 0, "empty")), emptySet()))
    }
}
