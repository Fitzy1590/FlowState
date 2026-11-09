package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.data.Catalog
import com.fitzy.flowstate.data.local.FlowRepository
import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.domain.Vote
import com.fitzy.flowstate.links.DeepLinks
import com.fitzy.flowstate.links.LinkLauncher
import com.fitzy.flowstate.links.TrailerLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Player(val label: String) { STREMIO("Stremio"), NUVIO("Nuvio") }

data class DetailState(
    val candidate: Candidate,
    val overview: String = "",
    val imdbId: String? = null,
    val trailerKey: String? = null,
    val userScore: Int? = null,
    val inLibrary: Boolean = false,
    val vote: Vote? = null,
    val loading: Boolean = true,
    val message: String? = null,
)

/** One title's page: rating, library, Play Now, trailer. */
class DetailPresenter(
    private val candidate: Candidate,
    private val repo: FlowRepository,
    private val catalog: Catalog,
    private val launcher: LinkLauncher,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(snapshot(DetailState(candidate)))
    val state: StateFlow<DetailState> = _state.asStateFlow()

    fun load() {
        scope.launch {
            val d = runCatching { catalog.details(candidate.id) }.getOrNull()
            _state.update {
                snapshot(it.copy(
                    overview = d?.overview.orEmpty(),
                    imdbId = d?.imdbId ?: candidate.imdbId,
                    trailerKey = d?.trailerKey,
                    loading = false,
                ))
            }
        }
    }

    /** Returns false and stores nothing when [score] is outside 0..100. */
    fun rate(score: Int): Boolean {
        if (score !in 0..100) return false
        repo.setRating(candidate.id, score)
        _state.update { snapshot(it) }
        return true
    }

    fun addToLibrary() {
        repo.quickAdd(candidate.copy(imdbId = _state.value.imdbId ?: candidate.imdbId))
        _state.update { snapshot(it) }
    }

    fun play(player: Player) {
        val imdb = _state.value.imdbId
        if (imdb == null) {
            say("No IMDb id for this title, so it can't be opened in ${player.label}.")
            return
        }
        val uri = when (player) {
            Player.STREMIO -> DeepLinks.stremio(candidate.id.type, imdb)
            Player.NUVIO -> DeepLinks.nuvio(candidate.id.type, imdb)
        }
        if (!launcher.open(uri)) say("${player.label} could not be opened. Is it installed?")
    }

    fun watchTrailer() {
        val key = _state.value.trailerKey
        val uri = if (key != null) TrailerLinks.watch(key) else TrailerLinks.search(candidate.name, candidate.year)
        if (!launcher.open(uri)) say("Could not open YouTube.")
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun say(text: String) = _state.update { it.copy(message = text) }

    private fun snapshot(s: DetailState) = s.copy(
        userScore = repo.rating(candidate.id),
        inLibrary = candidate.id in repo.libraryIds(),
        vote = repo.voteFor(candidate.id)?.vote,
    )
}
