package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.data.Catalog
import com.fitzy.flowstate.data.local.FlowRepository
import com.fitzy.flowstate.data.local.LibraryEntry
import com.fitzy.flowstate.domain.*
import com.fitzy.flowstate.reco.InterestPrompter
import com.fitzy.flowstate.reco.PromptState
import com.fitzy.flowstate.reco.Recommender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeState(
    val hero: LibraryEntry? = null,
    val recommended: List<Candidate> = emptyList(),
    val prompt: Candidate? = null,
    val loading: Boolean = true,
)

/** Home hero, recommendation row and the "Would this interest you?" card (SPEC 8a). */
class HomePresenter(
    private val repo: FlowRepository,
    private val catalog: Catalog,
    private val reco: Recommender,
    private val prompter: InterestPrompter,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private var promptState: PromptState = repo.promptState()

    fun load() {
        scope.launch {
            val now = clock()
            val pool = catalog.pool()
            val profile = repo.profile()
            val library = repo.libraryIds()
            val recs = reco.rank(pool, profile).map { it.candidate }.filter { it.id !in library }

            var prompt: Candidate? = null
            if (prompter.canShow(promptState, now, busy = false)) {
                val votes = repo.votes().associate { it.vote.titleId to it.vote }
                prompt = prompter.pick(recs.take(PROMPT_TOP), pool, profile, votes, library, now)
                if (prompt != null) {
                    promptState = prompter.onShown(promptState, now)
                    repo.savePromptState(promptState)
                }
            }
            _state.value = HomeState(repo.continueWatching(), recs.take(ROW_SIZE), prompt, loading = false)
        }
    }

    fun answerPrompt(vote: Vote) {
        val c = _state.value.prompt ?: return
        repo.castVote(c.id, vote, VoteSource.PROMPT, c.features())
        promptState = prompter.onAnswered(promptState)
        repo.savePromptState(promptState)
        _state.value = _state.value.copy(prompt = null)
    }

    fun dismissPrompt() {
        if (_state.value.prompt == null) return
        promptState = prompter.onDismissed(promptState, clock())
        repo.savePromptState(promptState)
        _state.value = _state.value.copy(prompt = null)
    }

    private companion object {
        const val ROW_SIZE = 20
        const val PROMPT_TOP = 10
    }
}
