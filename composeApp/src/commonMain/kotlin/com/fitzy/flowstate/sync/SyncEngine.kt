package com.fitzy.flowstate.sync

import com.fitzy.flowstate.domain.MediaType

/** One title's progress as seen by either side. [lastActivityAt] is epoch millis. */
data class SyncItem(
    val imdbId: String,
    val type: MediaType,
    val name: String,
    val completion: Double,
    val completed: Boolean,
    val lastActivityAt: Long,
)

enum class SyncAction { PUSH, PULL }

data class SyncDecision(val item: SyncItem, val action: SyncAction, val localTs: Long?, val remoteTs: Long?)

/**
 * Two-way conflict rule (SPEC 4): the side with the later [SyncItem.lastActivityAt] wins; equal times change nothing.
 * Items present on one side only are copied across, except that shows are never pushed (see [pushShows]).
 * Never produces a delete: a failed or empty fetch on either side cannot remove anything.
 */
/** Overrides the "latest wins" rule for a one-off force pull (Stremio wins) or force push (this app wins). */
enum class Prefer { REMOTE, LOCAL }

object SyncEngine {
    fun plan(local: List<SyncItem>, remote: List<SyncItem>, pushShows: Boolean = false, prefer: Prefer? = null): List<SyncDecision> {
        val l = local.associateBy { it.imdbId }
        val r = remote.associateBy { it.imdbId }
        val out = ArrayList<SyncDecision>()
        for (id in l.keys + r.keys) {
            val a = l[id]
            val b = r[id]
            when {
                a != null && b == null -> if (a.type == MediaType.MOVIE || pushShows) out += SyncDecision(a, SyncAction.PUSH, a.lastActivityAt, null)
                a == null && b != null -> out += SyncDecision(b, SyncAction.PULL, null, b.lastActivityAt)
                a != null && b != null && prefer != null -> {
                    val differs = a.completion != b.completion || a.completed != b.completed
                    if (differs && prefer == Prefer.REMOTE) out += SyncDecision(b, SyncAction.PULL, a.lastActivityAt, b.lastActivityAt)
                    if (differs && prefer == Prefer.LOCAL && (a.type == MediaType.MOVIE || pushShows)) out += SyncDecision(a, SyncAction.PUSH, a.lastActivityAt, b.lastActivityAt)
                }
                a != null && b != null -> when {
                    a.lastActivityAt > b.lastActivityAt -> if (a.type == MediaType.MOVIE || pushShows) out += SyncDecision(a, SyncAction.PUSH, a.lastActivityAt, b.lastActivityAt)
                    a.lastActivityAt < b.lastActivityAt -> out += SyncDecision(b, SyncAction.PULL, a.lastActivityAt, b.lastActivityAt)
                    else -> Unit
                }
            }
        }
        return out
    }
}
