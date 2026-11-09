package com.fitzy.flowstate.background

import com.fitzy.flowstate.data.local.FlowRepository
import com.fitzy.flowstate.sync.StremioSync
import com.fitzy.flowstate.sync.SyncResult
import com.fitzy.flowstate.sync.nuvio.NuvioSync

const val NOTIFICATIONS_KEY = "notify.newEpisodes"

data class BackgroundResult(val sync: SyncResult, val newEpisodes: List<UpcomingItem>)

/**
 * The periodic job (SPEC 4c): sync with Stremio, then find episodes airing today. The caller shows notifications,
 * because posting them is platform work. An episode is only returned once, even if the job runs many times that day.
 */
class BackgroundWork(
    private val repo: FlowRepository,
    private val stremio: StremioSync,
    private val upcoming: UpcomingService,
    private val clock: () -> Long,
    private val nuvio: NuvioSync? = null,
) {
    suspend fun run(): BackgroundResult {
        val sync = stremio.sync()
        runCatching { nuvio?.sync() } // a Nuvio failure never blocks the rest of the job
        if (repo.getSetting(NOTIFICATIONS_KEY) != "true") return BackgroundResult(sync, emptyList())
        val due = NewEpisodeLogic.due(upcoming.load(), NewEpisodeLogic.todayIso(clock())) { repo.getSetting(it) != null }
        due.forEach { repo.putSetting(NewEpisodeLogic.key(it), clock().toString()) }
        return BackgroundResult(sync, due)
    }
}
