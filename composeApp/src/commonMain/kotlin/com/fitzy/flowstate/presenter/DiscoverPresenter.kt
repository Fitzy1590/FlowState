package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.data.Catalog
import com.fitzy.flowstate.data.local.FlowRepository
import com.fitzy.flowstate.domain.*
import com.fitzy.flowstate.reco.Recommender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoverState(
    val current: Candidate? = null,
    val next: Candidate? = null,
    val remaining: Int = 0,
    val canUndo: Boolean = false,
    val loading: Boolean = true,
)

/** Card stack for the Discover tab (SPEC 8b). Votes use source SWIPE. */
class DiscoverPresenter(
    private val repo: FlowRepository,
    private val catalog: Catalog,
    private val reco: Recommender,
    private val scope: CoroutineScope,
) {
    private class Undo(val index: Int, val previousVote: VotedTitle?, val id: TitleId, val addedToLibrary: Boolean)

    private val _state = MutableStateFlow(DiscoverState())
    val state: StateFlow<DiscoverState> = _state.asStateFlow()

    private var deck: List<Candidate> = emptyList()
    private var index = 0
    private val undoStack = ArrayDeque<Undo>()

    fun load() {
        scope.launch {
            _state.update { it.copy(loading = true) }
            val library = repo.libraryIds()
            deck = reco.rank(catalog.pool(), repo.profile()).map { it.candidate }.filter { it.id !in library }
            index = 0
            undoStack.clear()
            publish()
        }
    }

    /** Yes = swipe right, Never = swipe left, Maybe = swipe up. */
    fun vote(v: Vote) {
        val c = deck.getOrNull(index) ?: return
        undoStack.addLast(Undo(index, repo.votedTitle(c.id), c.id, addedToLibrary = false))
        repo.castVote(c.id, v, VoteSource.SWIPE, c.features())
        advance()
    }

    /** Adds to the library as Planned and counts as Yes unless the user already voted. Moves to the next card. */
    fun addToLibrary() {
        val c = deck.getOrNull(index) ?: return
        val wasInLibrary = c.id in repo.libraryIds()
        undoStack.addLast(Undo(index, repo.votedTitle(c.id), c.id, addedToLibrary = !wasInLibrary))
        repo.quickAdd(c)
        advance()
    }

    fun undo() {
        val u = undoStack.removeLastOrNull() ?: return
        if (u.previousVote == null) repo.deleteVote(u.id) else repo.restoreVote(u.previousVote)
        if (u.addedToLibrary) repo.removeFromLibrary(u.id)
        index = u.index
        publish()
    }

    private fun advance() {
        index++
        publish()
    }

    private fun publish() {
        _state.value = DiscoverState(
            current = deck.getOrNull(index),
            next = deck.getOrNull(index + 1),
            remaining = (deck.size - index).coerceAtLeast(0),
            canUndo = undoStack.isNotEmpty(),
            loading = false,
        )
    }
}
