package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.data.Catalog
import com.fitzy.flowstate.data.ai.Summarizer
import com.fitzy.flowstate.data.local.FlowRepository
import com.fitzy.flowstate.data.local.LibraryStatus
import com.fitzy.flowstate.data.tmdb.EpisodeInfo
import com.fitzy.flowstate.data.tmdb.SeasonInfo
import com.fitzy.flowstate.data.youtube.YoutubeClient
import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.episodes.SeasonPlan
import com.fitzy.flowstate.links.LinkLauncher
import com.fitzy.flowstate.links.TrailerLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EpisodeRow(
    val info: EpisodeInfo,
    val watched: Boolean,
    /** AI summary. Only ever filled for watched episodes. */
    val summary: String? = null,
    val summaryBusy: Boolean = false,
)

data class EpisodesState(
    val seasons: List<SeasonInfo> = emptyList(),
    val selected: Int? = null,
    val rows: List<EpisodeRow> = emptyList(),
    val plan: SeasonPlan = SeasonPlan(null, null, null),
    val catchUp: String? = null,
    val catchUpBusy: Boolean = false,
    val aiAvailable: Boolean = false,
    val loading: Boolean = true,
    val message: String? = null,
)

/**
 * Episodes tab for a show: watched marks, season trailer and recap, AI summaries.
 * Spoiler rule: summaries and catch-ups are only made from episodes the user has marked watched.
 */
class EpisodesPresenter(
    private val show: Candidate,
    private val repo: FlowRepository,
    private val catalog: Catalog,
    private val summarizer: Summarizer,
    private val youtube: YoutubeClient,
    private val launcher: LinkLauncher,
    private val scope: CoroutineScope,
    private val lang: String = "en",
) {
    private val _state = MutableStateFlow(EpisodesState(aiAvailable = summarizer.available))
    val state: StateFlow<EpisodesState> = _state.asStateFlow()

    fun load() {
        scope.launch {
            val seasons = runCatching { catalog.details(show.id) }.getOrNull()?.seasons.orEmpty().filter { it.number > 0 }
            val watched = repo.watchedEpisodes(show.id)
            val plan = SeasonPlan.of(seasons, watched)
            // Open the season the user is in: the first unfinished one, else the last.
            val start = seasons.firstOrNull { s -> watched.count { it.first == s.number } < s.episodeCount }?.number
                ?: seasons.lastOrNull()?.number
            _state.value = EpisodesState(seasons, start, emptyList(), plan, aiAvailable = summarizer.available, loading = false)
            start?.let { loadSeason(it) }
        }
    }

    fun select(season: Int) {
        _state.update { it.copy(selected = season, rows = emptyList(), catchUp = null) }
        scope.launch { loadSeason(season) }
    }

    fun toggleWatched(season: Int, episode: Int) {
        val now = repo.watchedEpisodes(show.id)
        repo.setEpisodeWatched(show.id, season, episode, (season to episode) !in now)
        updateProgress()
        _state.update { it.copy(catchUp = null) }
        refreshRows()
    }

    fun requestSummary(season: Int, episode: Int) {
        if ((season to episode) !in repo.watchedEpisodes(show.id)) return // never summarize unwatched episodes
        val row = _state.value.rows.firstOrNull { it.info.season == season && it.info.number == episode } ?: return
        if (row.summary != null || row.summaryBusy) return
        if (!summarizer.available) return say("Add your Claude API key in Settings to get episode summaries.")
        patchRow(season, episode) { it.copy(summaryBusy = true) }
        scope.launch {
            val text = summarizer.episode(show.name, row.info)
            if (text != null) repo.saveSummary(show.id, season, episode, lang, Summarizer.PROMPT_VERSION, text)
            else say("No summary available for this episode.")
            patchRow(season, episode) { it.copy(summary = text, summaryBusy = false) }
        }
    }

    /** Builds a season catch-up from the watched episodes only, making any missing episode summaries first. */
    fun catchUp() {
        val season = _state.value.selected ?: return
        if (!summarizer.available) return say("Add your Claude API key in Settings to get a catch-up.")
        _state.update { it.copy(catchUpBusy = true) }
        scope.launch {
            val watched = repo.watchedEpisodes(show.id)
            val lines = ArrayList<String>()
            for (ep in _state.value.rows.filter { (it.info.season to it.info.number) in watched }.map { it.info }.sortedBy { it.number }) {
                val cached = repo.summary(show.id, season, ep.number, lang, Summarizer.PROMPT_VERSION)
                val text = cached ?: summarizer.episode(show.name, ep)?.also {
                    repo.saveSummary(show.id, season, ep.number, lang, Summarizer.PROMPT_VERSION, it)
                }
                if (text != null) lines += text
            }
            val recap = summarizer.catchUp(show.name, season, lines)
            if (recap == null) say("Could not make a catch-up. Check that watched episodes have descriptions.")
            _state.update { it.copy(catchUp = recap, catchUpBusy = false) }
            refreshRows()
        }
    }

    fun openSeasonTrailer(season: Int) {
        scope.launch {
            val key = catalog.seasonTrailerKey(show.id, season)
            if (key == null) say("No trailer found for season $season.")
            else if (!launcher.open(TrailerLinks.watch(key))) say("Could not open YouTube.")
        }
    }

    /** Opens the top YouTube result for "<show> season N recap" when a YouTube key is saved, else the search page. */
    fun openRecap(season: Int) {
        scope.launch {
            val query = "${show.name} season $season recap"
            val id = youtube.firstVideoId(query)
            val uri = if (id != null) TrailerLinks.watch(id) else TrailerLinks.searchFor(query)
            if (!launcher.open(uri)) say("Could not open YouTube.")
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private suspend fun loadSeason(season: Int) {
        val eps = catalog.seasonEpisodes(show.id, season)
        _state.update { it.copy(rows = buildRows(eps)) }
    }

    private fun buildRows(eps: List<EpisodeInfo>): List<EpisodeRow> {
        val watched = repo.watchedEpisodes(show.id)
        return eps.map { e ->
            val w = (e.season to e.number) in watched
            EpisodeRow(e, w, summary = if (w) repo.summary(show.id, e.season, e.number, lang, Summarizer.PROMPT_VERSION) else null)
        }
    }

    private fun refreshRows() {
        val current = _state.value
        val watched = repo.watchedEpisodes(show.id)
        _state.value = current.copy(
            rows = buildRows(current.rows.map { it.info }),
            plan = SeasonPlan.of(current.seasons, watched),
        )
    }

    private fun updateProgress() {
        val total = _state.value.seasons.sumOf { it.episodeCount }
        val watched = repo.watchedEpisodes(show.id).size
        repo.addToLibrary(show)
        val status = when {
            watched == 0 -> LibraryStatus.PLANNED
            total > 0 && watched >= total -> LibraryStatus.COMPLETED
            else -> LibraryStatus.WATCHING
        }
        repo.setProgress(show.id, status, if (total > 0) (watched.toDouble() / total).coerceAtMost(1.0) else 0.0)
    }

    private fun patchRow(season: Int, episode: Int, f: (EpisodeRow) -> EpisodeRow) = _state.update { s ->
        s.copy(rows = s.rows.map { if (it.info.season == season && it.info.number == episode) f(it) else it })
    }

    private fun say(text: String) = _state.update { it.copy(message = text) }
}
