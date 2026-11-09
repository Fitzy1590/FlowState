package com.fitzy.flowstate.background

import com.fitzy.flowstate.data.Catalog
import com.fitzy.flowstate.data.local.FlowRepository
import com.fitzy.flowstate.data.local.LibraryStatus
import com.fitzy.flowstate.data.tmdb.UpcomingEpisode
import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.domain.MediaType
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class UpcomingItem(val show: Candidate, val episode: UpcomingEpisode)

/** Next episode to air for each show in the library (SPEC 6e, 6f). Dropped and unaired-info shows are left out. */
class UpcomingService(private val repo: FlowRepository, private val catalog: Catalog) {
    suspend fun load(): List<UpcomingItem> = repo.libraryItems()
        .filter { it.candidate.id.type == MediaType.SHOW && it.status != LibraryStatus.DROPPED }
        .mapNotNull { v ->
            val next = runCatching { catalog.details(v.candidate.id) }.getOrNull()?.nextEpisode ?: return@mapNotNull null
            UpcomingItem(v.candidate, next)
        }
        .sortedBy { it.episode.airDate }
}

object NewEpisodeLogic {
    fun key(i: UpcomingItem) = "notified.${i.show.id.tmdbId}.${i.episode.season}.${i.episode.episode}"

    /** Episodes airing on [today] ("YYYY-MM-DD") that have not been notified yet. */
    fun due(items: List<UpcomingItem>, today: String, alreadyNotified: (String) -> Boolean): List<UpcomingItem> =
        items.filter { it.episode.airDate == today && !alreadyNotified(key(it)) }

    fun todayIso(nowMillis: Long): String =
        Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
}
