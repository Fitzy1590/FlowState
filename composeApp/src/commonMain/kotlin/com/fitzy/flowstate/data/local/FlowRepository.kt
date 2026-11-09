package com.fitzy.flowstate.data.local

import com.fitzy.flowstate.data.tmdb.TmdbTitle
import com.fitzy.flowstate.db.FlowDatabase
import com.fitzy.flowstate.domain.*
import com.fitzy.flowstate.reco.PromptState
import com.fitzy.flowstate.reco.UserProfile

enum class LibraryStatus { PLANNED, WATCHING, COMPLETED, DROPPED }

enum class ActorPref { LIKED, DISLIKED }

/** Local source of truth for library, ratings, interest votes, actor lists and prompt state. */
class FlowRepository(private val db: FlowDatabase, private val clock: () -> Long) {
    private val q get() = db.flowQueries

    // ---- Library ----

    fun addToLibrary(t: TmdbTitle, imdbId: String? = null, status: LibraryStatus = LibraryStatus.PLANNED) {
        val now = clock()
        q.insertLibraryIfMissing(
            t.id.type.name, t.id.tmdbId, t.name, t.year?.toLong(), t.posterPath, imdbId,
            t.genreIds.joinToString(","), status.name, 0.0, 0L, now, now,
        )
    }

    fun libraryIds(): Set<TitleId> = q.selectLibrary().executeAsList().map { TitleId(MediaType.valueOf(it.type), it.tmdbId) }.toSet()

    fun setProgress(id: TitleId, status: LibraryStatus, completion: Double, abandoned: Boolean = false) {
        require(completion in 0.0..1.0) { "completion must be 0..1" }
        q.updateProgress(status.name, completion, if (abandoned) 1L else 0L, clock(), id.type.name, id.tmdbId)
    }

    fun removeFromLibrary(id: TitleId) = q.deleteLibraryItem(id.type.name, id.tmdbId)

    // ---- Ratings (0..100) ----

    fun setRating(id: TitleId, score: Int) {
        require(score in 0..100) { "score must be 0..100" }
        q.upsertRating(id.type.name, id.tmdbId, score.toLong(), clock())
    }

    fun rating(id: TitleId): Int? =
        q.selectRatings().executeAsList().firstOrNull { it.type == id.type.name && it.tmdbId == id.tmdbId }?.score?.toInt()

    // ---- Interest votes ----

    fun castVote(id: TitleId, vote: Vote, source: VoteSource, features: TitleFeatures) {
        q.upsertVote(
            id.type.name, id.tmdbId, vote.name, source.name, clock(),
            features.genres.joinToString(","), features.keywords.joinToString(","), features.cast.joinToString(","),
        )
    }

    fun votes(): List<VotedTitle> = q.selectVotes().executeAsList().map {
        VotedTitle(
            InterestVote(TitleId(MediaType.valueOf(it.type), it.tmdbId), Vote.valueOf(it.vote), it.atMillis, VoteSource.valueOf(it.source)),
            TitleFeatures(it.genres.ints().toSet(), it.keywords.ints().toSet(), it.castIds.longs()),
        )
    }

    fun voteFor(id: TitleId): InterestVote? = votes().firstOrNull { it.vote.titleId == id }?.vote

    /**
     * Quick add: puts the title in the library as Planned, and counts it as Yes unless the user already voted.
     */
    fun quickAdd(t: TmdbTitle, features: TitleFeatures, imdbId: String? = null, source: VoteSource = VoteSource.SWIPE) {
        addToLibrary(t, imdbId)
        if (voteFor(t.id) == null) castVote(t.id, Vote.YES, source, features)
    }

    // ---- Actors ----

    fun setActorPref(personId: Long, name: String, pref: ActorPref?) {
        if (pref == null) q.deleteActor(personId) else q.upsertActor(personId, name, pref.name)
    }

    fun actorPrefs(): ActorPrefs {
        val rows = q.selectActors().executeAsList()
        return ActorPrefs(
            liked = rows.filter { it.pref == ActorPref.LIKED.name }.map { it.personId }.toSet(),
            disliked = rows.filter { it.pref == ActorPref.DISLIKED.name }.map { it.personId }.toSet(),
        )
    }

    // ---- Profile ----

    /** History is every library title that was started (not just Planned). */
    fun profile(): UserProfile {
        val ratings = q.selectRatings().executeAsList().associate { (it.type to it.tmdbId) to it.score.toInt() }
        val history = q.selectLibrary().executeAsList()
            .filter { it.status != LibraryStatus.PLANNED.name }
            .map {
                WatchRecord(
                    titleId = TitleId(MediaType.valueOf(it.type), it.tmdbId),
                    genres = it.genres.ints().toSet(),
                    userScore = ratings[it.type to it.tmdbId],
                    completion = it.completion,
                    abandoned = it.abandoned != 0L || it.status == LibraryStatus.DROPPED.name && it.completion < 0.25,
                )
            }
        return UserProfile.build(history, votes(), actorPrefs())
    }

    // ---- Interest prompt state ----

    fun promptState(): PromptState = PromptState(
        lastShownAt = setting("prompt.lastShownAt")?.toLongOrNull() ?: 0L,
        consecutiveDismissals = setting("prompt.dismissals")?.toIntOrNull() ?: 0,
        backoffUntil = setting("prompt.backoffUntil")?.toLongOrNull() ?: 0L,
    )

    fun savePromptState(s: PromptState) {
        q.putSetting("prompt.lastShownAt", s.lastShownAt.toString())
        q.putSetting("prompt.dismissals", s.consecutiveDismissals.toString())
        q.putSetting("prompt.backoffUntil", s.backoffUntil.toString())
    }

    private fun setting(key: String): String? = q.getSetting(key).executeAsOneOrNull()

    private fun String.ints() = if (isBlank()) emptyList() else split(",").mapNotNull { it.toIntOrNull() }
    private fun String.longs() = if (isBlank()) emptyList() else split(",").mapNotNull { it.toLongOrNull() }
}
