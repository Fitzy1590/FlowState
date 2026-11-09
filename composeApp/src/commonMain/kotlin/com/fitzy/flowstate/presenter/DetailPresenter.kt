package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.data.Catalog
import com.fitzy.flowstate.data.local.ActorPref
import com.fitzy.flowstate.data.local.FlowRepository
import com.fitzy.flowstate.data.tmdb.CastMember
import com.fitzy.flowstate.data.tmdb.SeasonInfo
import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.domain.MediaType
import com.fitzy.flowstate.domain.Vote
import com.fitzy.flowstate.episodes.NextEpisode
import com.fitzy.flowstate.links.DeepLinks
import com.fitzy.flowstate.links.LinkLauncher
import com.fitzy.flowstate.links.TrailerLinks
import com.fitzy.flowstate.links.letterboxdLink
import com.fitzy.flowstate.ratings.ExternalRatings
import com.fitzy.flowstate.ratings.OmdbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Player(val label: String) { STREMIO("Stremio"), NUVIO("Nuvio") }

const val DEFAULT_PLAYER_KEY = "player.default"

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
    val cast: List<CastMember> = emptyList(),
    val likedActors: Set<Long> = emptySet(),
    val dislikedActors: Set<Long> = emptySet(),
    val similar: List<Candidate> = emptyList(),
    val ratings: ExternalRatings? = null,
    val defaultPlayer: Player = Player.STREMIO,
    /** Next unwatched episode of a show, as (season, episode). */
    val nextEpisode: Pair<Int, Int>? = null,
)

/** One title's page: rating, library, Play Now, trailer, cast preferences, similar titles, external scores. */
class DetailPresenter(
    private val candidate: Candidate,
    private val repo: FlowRepository,
    private val catalog: Catalog,
    private val launcher: LinkLauncher,
    private val omdb: OmdbClient?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { 0L },
) {
    private var seasons: List<SeasonInfo> = emptyList()
    private val _state = MutableStateFlow(snapshot(DetailState(candidate)))
    val state: StateFlow<DetailState> = _state.asStateFlow()

    fun load() {
        scope.launch {
            val d = runCatching { catalog.details(candidate.id) }.getOrNull()
            seasons = d?.seasons.orEmpty()
            val imdb = d?.imdbId ?: candidate.imdbId
            _state.update {
                snapshot(it.copy(
                    overview = d?.overview.orEmpty(), imdbId = imdb, trailerKey = d?.trailerKey,
                    cast = d?.castMembers.orEmpty().take(MAX_CAST), loading = false,
                ))
            }
            val excluded = repo.profile().excluded
            val similar = runCatching { catalog.related(candidate.id) }.getOrDefault(emptyList()).filter { it.id !in excluded }.take(MAX_SIMILAR)
            _state.update { it.copy(similar = similar) }
            if (imdb != null) loadRatings(imdb)
        }
    }

    /** Re-reads library, rating and next-episode info (for example after ticking episodes). */
    fun refresh() = _state.update { snapshot(it) }

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

    /** Liked / disliked toggle: choosing the current preference again clears it. */
    fun setActor(member: CastMember, pref: ActorPref) {
        val prefs = repo.actorPrefs()
        val current = when (member.id) { in prefs.liked -> ActorPref.LIKED; in prefs.disliked -> ActorPref.DISLIKED; else -> null }
        repo.setActorPref(member.id, member.name, if (current == pref) null else pref)
        _state.update { snapshot(it) }
    }

    fun setDefaultPlayer(player: Player) {
        repo.putSetting(DEFAULT_PLAYER_KEY, player.name)
        _state.update { snapshot(it) }
    }

    /** Plays in the user's default player. Shows open the next unwatched episode in Stremio. */
    fun play() = play(_state.value.defaultPlayer)

    fun play(player: Player) {
        val s = _state.value
        val imdb = s.imdbId
        if (imdb == null) {
            say("No IMDb id for this title, so it can't be opened in ${player.label}.")
            return
        }
        val next = if (candidate.id.type == MediaType.SHOW) currentNext() else null
        val uri = when (player) {
            Player.STREMIO -> DeepLinks.stremio(candidate.id.type, imdb, next?.first, next?.second)
            Player.NUVIO -> DeepLinks.nuvio(candidate.id.type, imdb) // Nuvio's link opens the title page only
        }
        if (!launcher.open(uri)) say("${player.label} could not be opened. Is it installed?")
    }

    fun watchTrailer() {
        val key = _state.value.trailerKey
        val uri = if (key != null) TrailerLinks.watch(key) else TrailerLinks.search(candidate.name, candidate.year)
        if (!launcher.open(uri)) say("Could not open YouTube.")
    }

    fun openLetterboxd() {
        val imdb = _state.value.imdbId ?: return say("No IMDb id for this title.")
        if (!launcher.open(letterboxdLink(imdb))) say("Could not open the browser.")
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private suspend fun loadRatings(imdb: String) {
        val cached = repo.cachedRatings(imdb)
        if (cached != null && clock() - cached.fetchedAt < RATINGS_TTL_MS) {
            _state.update { it.copy(ratings = ExternalRatings(cached.imdb, cached.rottenTomatoes)) }
            return
        }
        val fresh = omdb?.ratings(imdb)
        if (fresh != null) {
            repo.cacheRatings(imdb, fresh.imdb, fresh.rottenTomatoes)
            _state.update { it.copy(ratings = fresh) }
        } else if (cached != null) { // network or key problem: an old score beats none
            _state.update { it.copy(ratings = ExternalRatings(cached.imdb, cached.rottenTomatoes)) }
        }
    }

    private fun currentNext() = NextEpisode.of(seasons, repo.watchedEpisodes(candidate.id))

    private fun say(text: String) = _state.update { it.copy(message = text) }

    private fun snapshot(s: DetailState): DetailState {
        val prefs = repo.actorPrefs()
        return s.copy(
            userScore = repo.rating(candidate.id),
            inLibrary = candidate.id in repo.libraryIds(),
            vote = repo.voteFor(candidate.id)?.vote,
            likedActors = prefs.liked,
            dislikedActors = prefs.disliked,
            defaultPlayer = repo.getSetting(DEFAULT_PLAYER_KEY)?.let { runCatching { Player.valueOf(it) }.getOrNull() } ?: Player.STREMIO,
            nextEpisode = if (candidate.id.type == MediaType.SHOW) currentNext() else null,
        )
    }

    private companion object {
        const val MAX_CAST = 20
        const val MAX_SIMILAR = 20
        const val RATINGS_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
