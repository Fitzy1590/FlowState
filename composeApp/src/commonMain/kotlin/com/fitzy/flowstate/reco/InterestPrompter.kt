package com.fitzy.flowstate.reco

import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.domain.InterestVote
import com.fitzy.flowstate.domain.TitleId
import com.fitzy.flowstate.domain.Vote
import kotlin.random.Random

/** Persisted state for the "Would this interest you?" card. [shownThisLaunch] is in-memory only. */
data class PromptState(
    val lastShownAt: Long = 0,
    val consecutiveDismissals: Int = 0,
    val backoffUntil: Long = 0,
    val shownThisLaunch: Boolean = false,
)

/** Rules from SPEC 8a: frequency, back-off, and the 70/30 recommend/explore pick. */
class InterestPrompter(private val random: Random = Random.Default) {

    fun canShow(state: PromptState, now: Long, busy: Boolean): Boolean =
        !busy && !state.shownThisLaunch &&
            now - state.lastShownAt >= DAY_MS &&
            now >= state.backoffUntil

    fun onShown(state: PromptState, now: Long) = state.copy(lastShownAt = now, shownThisLaunch = true)

    fun onAnswered(state: PromptState) = state.copy(consecutiveDismissals = 0)

    /** Three dismissals in a row pause the card for 7 days. */
    fun onDismissed(state: PromptState, now: Long): PromptState {
        val n = state.consecutiveDismissals + 1
        return if (n >= DISMISS_LIMIT) state.copy(consecutiveDismissals = 0, backoffUntil = now + BACKOFF_MS)
        else state.copy(consecutiveDismissals = n)
    }

    /**
     * [recommended] is best-first. [explorable] is any pool to explore from; it is ordered here by novelty.
     * Titles already in [library] or with a current vote are skipped, except Maybe votes older than 30 days.
     */
    fun pick(
        recommended: List<Candidate>,
        explorable: List<Candidate>,
        profile: UserProfile,
        votes: Map<TitleId, InterestVote>,
        library: Set<TitleId>,
        now: Long,
    ): Candidate? {
        fun eligible(c: Candidate): Boolean {
            if (c.id in library || c.id in profile.excluded) return false
            val v = votes[c.id] ?: return true
            return v.vote == Vote.MAYBE && now - v.atMillis >= MAYBE_RETRY_MS
        }
        val recs = recommended.filter(::eligible)
        val explore = explorable.filter(::eligible).sortedByDescending { novelty(it, profile) }
        val preferRecs = random.nextDouble() < RECOMMEND_SHARE
        return if (preferRecs) recs.firstOrNull() ?: explore.firstOrNull()
        else explore.firstOrNull() ?: recs.firstOrNull()
    }

    /** Share of the title's genres the profile knows nothing about (0 = all known, 1 = all new). */
    fun novelty(c: Candidate, p: UserProfile): Double {
        if (c.genres.isEmpty()) return 0.0
        val unknown = c.genres.count { it !in p.genreAffinity && it !in p.voteGenre }
        return unknown.toDouble() / c.genres.size
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val BACKOFF_MS = 7 * DAY_MS
        const val MAYBE_RETRY_MS = 30 * DAY_MS
        const val DISMISS_LIMIT = 3
        const val RECOMMEND_SHARE = 0.7
    }
}
