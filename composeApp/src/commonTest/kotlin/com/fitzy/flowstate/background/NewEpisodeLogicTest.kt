package com.fitzy.flowstate.background

import com.fitzy.flowstate.data.tmdb.UpcomingEpisode
import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.domain.MediaType
import com.fitzy.flowstate.domain.TitleId
import kotlin.test.*

class NewEpisodeLogicTest {
    private fun item(id: Long, season: Int, ep: Int, date: String) =
        UpcomingItem(Candidate(TitleId(MediaType.SHOW, id), "Show $id", 2020, emptySet()), UpcomingEpisode(season, ep, "Name", date))

    @Test fun onlyEpisodesAiringTodayAreDue() {
        val items = listOf(item(1, 1, 2, "2026-11-09"), item(2, 3, 1, "2026-11-10"), item(3, 1, 1, "2026-11-08"))
        assertEquals(listOf(1L), NewEpisodeLogic.due(items, "2026-11-09") { false }.map { it.show.id.tmdbId })
    }

    @Test fun anEpisodeAlreadyNotifiedIsNotDueAgain() {
        val i = item(1, 1, 2, "2026-11-09")
        assertTrue(NewEpisodeLogic.due(listOf(i), "2026-11-09") { it == NewEpisodeLogic.key(i) }.isEmpty())
    }

    @Test fun keysDifferByShowSeasonAndEpisode() {
        assertEquals(3, setOf(item(1, 1, 2, "d"), item(1, 1, 3, "d"), item(2, 1, 2, "d")).map(NewEpisodeLogic::key).toSet().size)
    }

    @Test fun todayIsAnIsoDate() {
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}").matches(NewEpisodeLogic.todayIso(1_800_000_000_000)))
    }
}
