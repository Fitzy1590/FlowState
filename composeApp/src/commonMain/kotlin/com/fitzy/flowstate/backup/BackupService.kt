package com.fitzy.flowstate.backup

import com.fitzy.flowstate.data.local.FlowRepository
import com.fitzy.flowstate.db.FlowDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class BLibrary(val type: String, val tmdbId: Long, val name: String, val year: Long? = null, val posterPath: String? = null, val imdbId: String? = null,
    val genres: String = "", val status: String, val completion: Double = 0.0, val abandoned: Long = 0, val addedAt: Long, val lastActivityAt: Long)
@Serializable data class BRating(val type: String, val tmdbId: Long, val score: Long, val ratedAt: Long)
@Serializable data class BVote(val type: String, val tmdbId: Long, val vote: String, val source: String, val atMillis: Long,
    val genres: String = "", val keywords: String = "", val castIds: String = "")
@Serializable data class BActor(val personId: Long, val name: String, val pref: String)
@Serializable data class BEpisode(val tmdbId: Long, val season: Long, val episode: Long, val watchedAt: Long)

@Serializable
data class Backup(
    val format: String = FORMAT,
    val version: Int = 1,
    val exportedAt: Long,
    val library: List<BLibrary> = emptyList(),
    val ratings: List<BRating> = emptyList(),
    val votes: List<BVote> = emptyList(),
    val actors: List<BActor> = emptyList(),
    val episodes: List<BEpisode> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
) {
    companion object { const val FORMAT = "flowstate-backup" }
}

sealed interface ImportResult {
    data class Done(val library: Int, val ratings: Int, val votes: Int) : ImportResult
    data class Invalid(val reason: String) : ImportResult
}

/**
 * Export and import of the user's own data (SPEC 4g) as one JSON file: library, scores, votes, actor lists,
 * watched episodes and two preferences. API keys, the Stremio session and cached AI text are never included.
 * Import adds or overwrites rows from the file and keeps everything else; it is all-or-nothing.
 */
class BackupService(private val repo: FlowRepository, private val clock: () -> Long) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun export(): String {
        val actors = repo.actorRows().map { BActor(it.id, it.name, it.pref.name) }
        return json.encodeToString(Backup(
            exportedAt = clock(),
            library = repo.allLibraryRows().map { BLibrary(it.type, it.tmdbId, it.name, it.year, it.posterPath, it.imdbId, it.genres, it.status, it.completion, it.abandoned, it.addedAt, it.lastActivityAt) },
            ratings = repo.allRatingRows().map { BRating(it.type, it.tmdbId, it.score, it.ratedAt) },
            votes = repo.allVoteRows().map { BVote(it.type, it.tmdbId, it.vote, it.source, it.atMillis, it.genres, it.keywords, it.castIds) },
            actors = actors,
            episodes = repo.allWatchedRows().map { BEpisode(it.tmdbId, it.season, it.episode, it.watchedAt) },
            settings = SETTINGS.mapNotNull { k -> repo.getSetting(k)?.let { k to it } }.toMap(),
        ))
    }

    fun import(text: String): ImportResult {
        val b = runCatching { json.decodeFromString<Backup>(text) }.getOrNull() ?: return ImportResult.Invalid("This is not a FlowState backup file.")
        if (b.format != Backup.FORMAT) return ImportResult.Invalid("This is not a FlowState backup file.")
        if (b.version != 1) return ImportResult.Invalid("This backup was made by a newer version of FlowState (format ${b.version}).")
        b.library.firstOrNull { !validType(it.type) || it.status !in STATUSES || it.completion !in 0.0..1.0 }?.let { return ImportResult.Invalid("The backup has an invalid library entry (${it.name}).") }
        if (b.ratings.any { it.score !in 0..100 || !validType(it.type) }) return ImportResult.Invalid("The backup has a score outside 0 to 100.")
        if (b.votes.any { it.vote !in VOTES || it.source !in SOURCES || !validType(it.type) }) return ImportResult.Invalid("The backup has an invalid vote.")
        if (b.actors.any { it.pref !in PREFS }) return ImportResult.Invalid("The backup has an invalid actor entry.")

        repo.inTransaction {
            b.library.forEach { repo.restoreLibraryRow(it.type, it.tmdbId, it.name, it.year, it.posterPath, it.imdbId, it.genres, it.status, it.completion, it.abandoned, it.addedAt, it.lastActivityAt) }
            b.ratings.forEach { repo.restoreRating(it.type, it.tmdbId, it.score, it.ratedAt) }
            b.votes.forEach { repo.restoreVoteRow(it.type, it.tmdbId, it.vote, it.source, it.atMillis, it.genres, it.keywords, it.castIds) }
            b.actors.forEach { repo.setActorPref(it.personId, it.name, com.fitzy.flowstate.data.local.ActorPref.valueOf(it.pref)) }
            b.episodes.forEach { repo.restoreWatched(it.tmdbId, it.season, it.episode, it.watchedAt) }
            b.settings.filterKeys { it in SETTINGS }.forEach { (k, v) -> repo.putSetting(k, v) }
        }
        return ImportResult.Done(b.library.size, b.ratings.size, b.votes.size)
    }

    private fun validType(t: String) = t == "MOVIE" || t == "SHOW"

    private companion object {
        val SETTINGS = listOf("player.default", "notify.newEpisodes")
        val STATUSES = setOf("PLANNED", "WATCHING", "COMPLETED", "DROPPED")
        val VOTES = setOf("YES", "MAYBE", "NEVER")
        val SOURCES = setOf("PROMPT", "SWIPE", "SEARCH")
        val PREFS = setOf("LIKED", "DISLIKED")
    }
}
