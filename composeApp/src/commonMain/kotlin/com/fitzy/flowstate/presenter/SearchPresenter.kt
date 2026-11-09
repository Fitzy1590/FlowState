package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.data.Catalog
import com.fitzy.flowstate.data.local.FlowRepository
import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.domain.MediaType
import com.fitzy.flowstate.domain.VoteSource
import com.fitzy.flowstate.search.*
import com.fitzy.flowstate.voice.VoiceInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One piece of how the request was understood. Removing it re-runs the search without it. */
data class Chip(val key: String, val label: String)

data class SearchState(
    val text: String = "",
    val chips: List<Chip> = emptyList(),
    val results: List<Candidate> = emptyList(),
    val loading: Boolean = false,
    val searched: Boolean = false,
    /** True when Claude read the request; false for the basic local reader. */
    val usedAi: Boolean = false,
    val listening: Boolean = false,
    val voiceAvailable: Boolean = false,
    val message: String? = null,
)

/** AI search (SPEC 8c) with optional voice input (8d). */
class SearchPresenter(
    private val repo: FlowRepository,
    catalog: Catalog,
    private val ai: QueryInterpreter,
    private val aiAvailable: () -> Boolean,
    private val voice: VoiceInput,
    private val scope: CoroutineScope,
) {
    private val service = SearchService(catalog)
    private val _state = MutableStateFlow(SearchState(voiceAvailable = voice.available))
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private var query: SearchQuery? = null

    /** The voice input may be attached after startup, so the screen asks again when it appears. */
    fun refreshVoice() = _state.update { it.copy(voiceAvailable = voice.available) }

    fun setText(text: String) = _state.update { it.copy(text = text) }

    fun submit() {
        val text = _state.value.text.trim()
        if (text.isEmpty() || _state.value.loading) return
        _state.update { it.copy(loading = true, message = null) }
        scope.launch {
            var usedAi = false
            var note: String? = null
            var q = if (aiAvailable()) ai.interpret(text)?.also { usedAi = true } else null
            if (q == null) {
                if (aiAvailable()) note = "AI search could not be reached, so a basic search was used."
                q = LocalQueryInterpreter.interpret(text)
            }
            query = q
            run(usedAi, note)
        }
    }

    fun removeChip(key: String) {
        val q = query ?: return
        query = when {
            key == "popular" -> q.copy(popular = false)
            key == "types" -> q.copy(mediaTypes = setOf(MediaType.MOVIE, MediaType.SHOW))
            key == "years" -> q.copy(yearFrom = null, yearTo = null)
            key == "title" -> q.copy(titleSearch = null)
            key.startsWith("genre:") -> q.copy(genres = q.genres - key.removePrefix("genre:"))
            key.startsWith("seed:") -> q.copy(seedTitles = q.seedTitles - key.removePrefix("seed:"))
            else -> q
        }
        _state.update { it.copy(loading = true) }
        scope.launch { run(_state.value.usedAi, null) }
    }

    /** Adds a result to the library as Planned; counts as Yes unless already voted. */
    fun add(c: Candidate) {
        repo.quickAdd(c, VoteSource.SEARCH)
        _state.update { it.copy(message = "Added ${c.name} to your library.") }
    }

    fun startVoice() {
        if (!voice.available || _state.value.listening) return
        _state.update { it.copy(listening = true, message = null) }
        voice.start(
            onPartial = { t -> _state.update { it.copy(text = t) } },
            onResult = { t -> _state.update { it.copy(text = t, listening = false) }; submit() },
            onError = { m -> _state.update { it.copy(listening = false, message = m) } },
        )
    }

    fun stopVoice() {
        voice.stop()
        _state.update { it.copy(listening = false) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private suspend fun run(usedAi: Boolean, note: String?) {
        val q = query
        val results = if (q == null || q.isEmpty) emptyList() else service.run(q, repo.profile())
        _state.update {
            it.copy(
                chips = q?.let(::chipsFor).orEmpty(), results = results, loading = false, searched = true, usedAi = usedAi,
                message = note ?: if (results.isEmpty()) "Nothing found. Try different words, or remove a filter above." else null,
            )
        }
    }

    private fun chipsFor(q: SearchQuery): List<Chip> = buildList {
        if (q.mediaTypes.size == 1) add(Chip("types", if (MediaType.MOVIE in q.mediaTypes) "Movies" else "Shows"))
        q.genres.sorted().forEach { add(Chip("genre:$it", it.replaceFirstChar(Char::uppercase))) }
        when {
            q.yearFrom != null && q.yearTo != null -> add(Chip("years", "${q.yearFrom}–${q.yearTo}"))
            q.yearTo != null -> add(Chip("years", "Before ${q.yearTo + 1}"))
            q.yearFrom != null -> add(Chip("years", "From ${q.yearFrom}"))
        }
        if (q.popular) add(Chip("popular", "Popular"))
        q.seedTitles.forEach { add(Chip("seed:$it", "Similar to $it")) }
        q.titleSearch?.let { add(Chip("title", "Title: $it")) }
    }
}
