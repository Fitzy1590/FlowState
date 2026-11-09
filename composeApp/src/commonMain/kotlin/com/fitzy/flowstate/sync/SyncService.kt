package com.fitzy.flowstate.sync

import com.fitzy.flowstate.data.local.FlowRepository
import com.fitzy.flowstate.data.local.LibraryStatus
import com.fitzy.flowstate.data.tmdb.TmdbClient
import com.fitzy.flowstate.data.tmdb.TmdbTitle
import com.fitzy.flowstate.domain.MediaType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

sealed interface SyncResult {
    /** [skipped]: titles that could not be identified (no TMDB token, or TMDB did not know them), so they were left out. */
    data class Done(val pushed: Int, val pulled: Int, val skipped: Int = 0) : SyncResult
    data object NotConnected : SyncResult
    data object Failed : SyncResult
}

/** Looks up a title by IMDb id so a pulled item can be added to the local library. */
fun interface TitleResolver {
    suspend fun resolve(imdbId: String): TmdbTitle?
}

class StremioSync(
    private val api: StremioApi,
    private val repo: FlowRepository,
    private val resolver: TitleResolver,
    private val authKey: () -> String?,
    private val clock: () -> Long,
) {
    /**
     * Pulls the Stremio library, decides per title with [SyncEngine] (latest activity wins) and applies it both ways.
     * A failed library fetch ends the run without changing anything. Nothing is ever deleted on either side.
     */
    suspend fun sync(prefer: Prefer? = null): SyncResult {
        val key = authKey()?.takeIf { it.isNotBlank() } ?: return SyncResult.NotConnected
        val remoteRaw = api.library(key) ?: return SyncResult.Failed
        val remoteById: Map<String, JsonObject> = remoteRaw.mapNotNull { o -> o["_id"]?.jsonPrimitive?.contentOrNull?.let { it to o } }.toMap()
        val remote = remoteRaw.mapNotNull(StremioItems::toSync)

        val localRows = repo.syncCandidates()
        val local = localRows.map {
            SyncItem(it.imdbId, it.id.type, it.name, it.completion, it.status == LibraryStatus.COMPLETED, it.lastActivityAt)
        }
        val localByImdb = localRows.associateBy { it.imdbId }

        val now = clock()
        val pushes = ArrayList<JsonObject>()
        var pulled = 0
        var skipped = 0
        val decisions = SyncEngine.plan(local, remote, prefer = prefer)
        // Look titles up in parallel: a real library can hold hundreds of titles.
        val resolved = resolveAll(decisions.filter { it.action == SyncAction.PULL && localByImdb[it.item.imdbId] == null }.map { it.item }.distinctBy { it.imdbId })
        for (d in decisions) {
            when (d.action) {
                SyncAction.PUSH -> {
                    val existing = remoteById[d.item.imdbId]
                    pushes += if (existing != null) StremioItems.withLocalProgress(existing, d.item, now) else StremioItems.newMovie(d.item, now)
                    repo.logSync("stremio", d.item.name, if (prefer != null) "forced push" else "pushed", d.localTs, d.remoteTs)
                }
                SyncAction.PULL -> if (applyPull(d.item, localByImdb[d.item.imdbId]?.id, resolved[d.item.imdbId])) {
                    pulled++
                    repo.logSync("stremio", d.item.name, if (prefer != null) "forced pull" else "pulled", d.localTs, d.remoteTs)
                } else {
                    skipped++
                    if (skipped <= MAX_SKIP_LOG) repo.logSync("stremio", d.item.name, "skipped (not matched to TMDB)", d.localTs, d.remoteTs)
                }
            }
        }
        if (pushes.isNotEmpty() && !api.putLibrary(key, pushes)) return SyncResult.Failed
        return SyncResult.Done(pushes.size, pulled, skipped)
    }

    /** Looks up several titles at once, a few at a time. A title TMDB does not know maps to null. */
    private suspend fun resolveAll(items: List<SyncItem>): Map<String, TmdbTitle?> = coroutineScope {
        val gate = Semaphore(RESOLVE_PARALLELISM)
        items.map { it.imdbId to async { gate.withPermit { runCatching { resolver.resolve(it.imdbId) }.getOrNull() } } }
            .associate { (id, job) -> id to job.await() }
    }

    private fun applyPull(item: SyncItem, existing: com.fitzy.flowstate.domain.TitleId?, resolved: TmdbTitle?): Boolean {
        val id = existing ?: run {
            val title = resolved ?: return false
            if (title.id.type != item.type) return false
            repo.addToLibrary(title, item.imdbId)
            title.id
        }
        val status = when {
            item.completed -> LibraryStatus.COMPLETED
            item.completion > 0.0 -> LibraryStatus.WATCHING
            else -> LibraryStatus.PLANNED
        }
        repo.setProgressAt(id, status, item.completion, item.lastActivityAt)
        return true
    }

    private companion object {
        const val RESOLVE_PARALLELISM = 8
        const val MAX_SKIP_LOG = 10
    }
}
