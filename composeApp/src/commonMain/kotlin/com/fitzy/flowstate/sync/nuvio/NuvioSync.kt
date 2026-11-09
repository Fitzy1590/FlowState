package com.fitzy.flowstate.sync.nuvio

import com.fitzy.flowstate.data.Catalog
import com.fitzy.flowstate.data.local.FlowRepository
import com.fitzy.flowstate.data.local.LibraryStatus
import com.fitzy.flowstate.domain.MediaType
import com.fitzy.flowstate.domain.TitleId
import com.fitzy.flowstate.sync.SyncAction
import com.fitzy.flowstate.sync.SyncEngine
import com.fitzy.flowstate.sync.SyncItem
import com.fitzy.flowstate.sync.SyncResult
import com.fitzy.flowstate.sync.TitleResolver

/** Identity of one watched mark: a movie, or one episode of a show. */
data class WatchedKey(val imdbId: String, val season: Int?, val episode: Int?)

/** A watched mark with its time. */
data class WatchedMark(val key: WatchedKey, val type: String, val title: String, val at: Long)

/**
 * Two-way watched sync is a union (SPEC 4): anything watched on either side ends up watched on both.
 * Nothing is ever removed, so a failed or empty fetch cannot erase anything. Unwatching must be done on both sides.
 */
object NuvioPlan {
    fun union(local: List<WatchedMark>, remote: List<WatchedMark>): Pair<List<WatchedMark>, List<WatchedMark>> {
        val l = local.map { it.key }.toSet()
        val r = remote.map { it.key }.toSet()
        return local.filter { it.key !in r } to remote.filter { it.key !in l } // (to push, to pull)
    }
}

/**
 * Nuvio sync (SPEC 4b). Watched marks (movies and episodes) are merged as a union. Partial progress of movies is
 * merged with "latest activity wins". Partial progress inside a show is not kept by this app, so it is not synced.
 */
class NuvioSync(
    private val api: NuvioApi,
    private val store: NuvioAccountStore,
    private val repo: FlowRepository,
    private val catalog: Catalog,
    private val resolver: TitleResolver,
    private val clock: () -> Long,
) {
    suspend fun sync(): SyncResult {
        val account = store.load() ?: return SyncResult.NotConnected
        val profile = account.profileIndex
        val remoteWatched = api.pullWatched(profile) ?: return SyncResult.Failed
        val remoteProgress = api.pullProgress(profile) ?: return SyncResult.Failed

        fillMissingImdbIds()
        val libraryByImdb = repo.syncCandidates().associateBy { it.imdbId }
        val shows = libraryByImdb.values.filter { it.id.type == MediaType.SHOW }

        // ---- watched marks ----
        val local = ArrayList<WatchedMark>()
        libraryByImdb.values.filter { it.id.type == MediaType.MOVIE && it.status == LibraryStatus.COMPLETED }
            .forEach { local += WatchedMark(WatchedKey(it.imdbId, null, null), "movie", it.name, it.lastActivityAt) }
        shows.forEach { s ->
            repo.watchedEpisodeTimes(s.id).forEach { (se, ep, at) -> local += WatchedMark(WatchedKey(s.imdbId, se, ep), "series", s.name, at) }
        }
        val explicit = remoteWatched.filter { it.contentId.startsWith("tt") }
            .map { WatchedMark(WatchedKey(it.contentId, it.season, it.episode), it.type, it.title, it.watchedAt) }
        // Nuvio counts a movie as finished at 90% progress even without a "watched" row, so treat it the same.
        val finishedByProgress = remoteProgress.filter { it.contentId.startsWith("tt") && it.season == null && it.duration > 0 && it.position.toDouble() / it.duration >= COMPLETE_AT }
            .map { WatchedMark(WatchedKey(it.contentId, null, null), "movie", "", it.lastWatched) }
        val remote = (explicit + finishedByProgress).distinctBy { it.key }
        val (toPush, toPull) = NuvioPlan.union(local, remote)

        var pulled = 0
        var skipped = 0
        applyWatchedPull(toPull).let { (applied, notMatched) -> pulled += applied; skipped += notMatched }

        // ---- movie progress (latest activity wins) ----
        val watchedRemoteMovies = remote.filter { it.key.season == null }.map { it.key.imdbId }.toSet()
        val localProgress = libraryByImdb.values.filter { it.id.type == MediaType.MOVIE && it.status == LibraryStatus.WATCHING }
            .map { SyncItem(it.imdbId, MediaType.MOVIE, it.name, it.completion, false, it.lastActivityAt) }
        val remoteProgressItems = remoteProgress.filter { it.contentId.startsWith("tt") && it.season == null && it.contentId !in watchedRemoteMovies && it.duration > 0 }
            .map { SyncItem(it.contentId, MediaType.MOVIE, "", (it.position.toDouble() / it.duration).coerceIn(0.0, 1.0), false, it.lastWatched) }
            .filter { it.completion < COMPLETE_AT }
        val progressPush = ArrayList<ProgressRow>()
        for (d in SyncEngine.plan(localProgress, remoteProgressItems)) {
            when (d.action) {
                SyncAction.PUSH -> progressPush += ProgressRow(
                    d.item.imdbId, "movie", d.item.imdbId, null, null,
                    (d.item.completion * ASSUMED_DURATION_MS).toLong(), ASSUMED_DURATION_MS, d.item.lastActivityAt, d.item.imdbId,
                ).also { repo.logSync("nuvio", d.item.name, "pushed progress", d.localTs, d.remoteTs) }
                SyncAction.PULL -> if (applyProgressPull(d.item)) {
                    pulled++
                    repo.logSync("nuvio", d.item.name, "pulled progress", d.localTs, d.remoteTs)
                } else skipped++
            }
        }

        // ---- send our side ----
        if (toPush.isNotEmpty() && !api.pushWatched(profile, toPush.map { WatchedRow(it.key.imdbId, it.type, it.title, it.key.season, it.key.episode, it.at) })) return SyncResult.Failed
        if (progressPush.isNotEmpty() && !api.pushProgress(profile, progressPush)) return SyncResult.Failed
        if (toPush.isNotEmpty()) repo.logSync("nuvio", "${toPush.size} watched mark(s)", "pushed", null, null)
        return SyncResult.Done(toPush.size + progressPush.size, pulled, skipped)
    }

    /** Titles added without an IMDb id (for example from the episode list) get it from the catalog, so they can be matched. */
    private suspend fun fillMissingImdbIds() {
        val withWatched = repo.allWatchedRows().map { it.tmdbId }.toSet()
        repo.allLibraryRows().filter { it.imdbId.isNullOrBlank() }.forEach { row ->
            val id = TitleId(MediaType.valueOf(row.type), row.tmdbId)
            val relevant = (id.type == MediaType.SHOW && id.tmdbId in withWatched) || row.status in setOf("COMPLETED", "WATCHING")
            if (!relevant) return@forEach
            runCatching { catalog.details(id)?.imdbId }.getOrNull()?.takeIf { it.startsWith("tt") }?.let { repo.setImdbId(id, it) }
        }
    }

    /** Returns (marks applied, titles that could not be identified). */
    private suspend fun applyWatchedPull(marks: List<WatchedMark>): Pair<Int, Int> {
        var count = 0
        var notMatched = 0
        for ((imdb, group) in marks.groupBy { it.key.imdbId }) {
            val isShow = group.any { it.key.season != null }
            val existing = repo.syncCandidates().firstOrNull { it.imdbId == imdb }?.id
            val id = existing ?: run {
                val title = resolver.resolve(imdb) ?: return@run null
                if ((title.id.type == MediaType.SHOW) != isShow) return@run null
                repo.addToLibrary(title, imdb)
                title.id
            } ?: run { notMatched++; null } ?: continue
            if (!isShow) {
                val at = group.maxOf { it.at }
                val current = repo.syncCandidates().first { it.id == id }
                repo.setProgressAt(id, LibraryStatus.COMPLETED, 1.0, maxOf(at, current.lastActivityAt))
            } else {
                group.forEach { repo.markEpisodeWatchedAt(id, it.key.season!!, it.key.episode!!, it.at) }
                refreshShowProgress(id, group.maxOf { it.at })
            }
            repo.logSync("nuvio", group.first().title.ifBlank { imdb }, "pulled watched", null, group.maxOf { it.at })
            count += group.size
        }
        return count to notMatched
    }

    private suspend fun refreshShowProgress(id: TitleId, at: Long) {
        val total = runCatching { catalog.details(id) }.getOrNull()?.seasons.orEmpty().filter { it.number > 0 }.sumOf { it.episodeCount }
        val watched = repo.watchedEpisodes(id).size
        val status = if (total > 0 && watched >= total) LibraryStatus.COMPLETED else LibraryStatus.WATCHING
        repo.setProgressAt(id, status, if (total > 0) (watched.toDouble() / total).coerceAtMost(1.0) else 0.0, at)
    }

    private suspend fun applyProgressPull(item: SyncItem): Boolean {
        val id = repo.syncCandidates().firstOrNull { it.imdbId == item.imdbId }?.id ?: run {
            val title = resolver.resolve(item.imdbId) ?: return false
            if (title.id.type != MediaType.MOVIE) return false
            repo.addToLibrary(title, item.imdbId)
            title.id
        }
        repo.setProgressAt(id, if (item.completion > 0.0) LibraryStatus.WATCHING else LibraryStatus.PLANNED, item.completion, item.lastActivityAt)
        return true
    }

    private companion object {
        /** Nuvio treats 90% as finished. */
        const val COMPLETE_AT = 0.9
        /** This app stores a fraction, not minutes, so pushed progress uses a nominal two hour length. */
        const val ASSUMED_DURATION_MS = 7_200_000L
    }
}
