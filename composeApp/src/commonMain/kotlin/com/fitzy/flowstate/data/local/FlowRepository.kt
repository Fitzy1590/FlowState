package com.fitzy.flowstate.data.local

import com.fitzy.flowstate.data.tmdb.TmdbTitle
import com.fitzy.flowstate.db.FlowDatabase
import com.fitzy.flowstate.domain.*
import com.fitzy.flowstate.reco.PromptState
import com.fitzy.flowstate.reco.UserProfile

enum class LibraryStatus { PLANNED, WATCHING, COMPLETED, DROPPED }

enum class ActorPref { LIKED, DISLIKED }

data class CachedRatings(val imdb: Double?, val rottenTomatoes: Int?, val fetchedAt: Long)

data class ActorRow(val id: Long, val name: String, val pref: ActorPref)

data class LibraryItemView(val candidate: Candidate, val status: LibraryStatus, val completion: Double)

data class LocalSyncRow(
    val id: TitleId,
    val name: String,
    val imdbId: String,
    val status: LibraryStatus,
    val completion: Double,
    val lastActivityAt: Long,
)

data class SyncLogLine(val at: Long, val service: String, val item: String, val action: String, val localTs: Long?, val remoteTs: Long?)

data class LibraryEntry(
    val id: TitleId,
    val name: String,
    val year: Int?,
    val posterPath: String?,
    val completion: Double,
    val lastActivityAt: Long,
)

/** Local source of truth for library, ratings, interest votes, actor lists and prompt state. */
class FlowRepository(private val db: FlowDatabase, private val clock: () -> Long) {
    /** Runs [body] as one database transaction: all of it is saved, or none. */
    fun <T> inTransaction(body: () -> T): T = db.transactionWithResult { body() }

    private val q get() = db.flowQueries

    // ---- Library ----

    fun addToLibrary(t: TmdbTitle, imdbId: String? = null, status: LibraryStatus = LibraryStatus.PLANNED) {
        val now = clock()
        q.insertLibraryIfMissing(
            t.id.type.name, t.id.tmdbId, t.name, t.year?.toLong(), t.posterPath, imdbId,
            t.genreIds.joinToString(","), status.name, 0.0, 0L, now, now,
        )
    }

    fun addToLibrary(c: Candidate, status: LibraryStatus = LibraryStatus.PLANNED) =
        addToLibrary(TmdbTitle(c.id, c.name, c.year, c.posterPath, c.genres, c.voteAverage), c.imdbId, status)

    fun libraryIds(): Set<TitleId> = q.selectLibrary().executeAsList().map { TitleId(MediaType.valueOf(it.type), it.tmdbId) }.toSet()

    fun setProgress(id: TitleId, status: LibraryStatus, completion: Double, abandoned: Boolean = false) {
        require(completion in 0.0..1.0) { "completion must be 0..1" }
        q.updateProgress(status.name, completion, if (abandoned) 1L else 0L, clock(), id.type.name, id.tmdbId)
    }

    /** The most recently active title that is in progress, for the Home hero. */
    fun continueWatching(): LibraryEntry? = q.selectLibrary().executeAsList()
        .filter { it.status == LibraryStatus.WATCHING.name }
        .maxByOrNull { it.lastActivityAt }
        ?.let {
            LibraryEntry(TitleId(MediaType.valueOf(it.type), it.tmdbId), it.name, it.year?.toInt(), it.posterPath, it.completion, it.lastActivityAt)
        }

    /** Library rows that have an IMDb id, for syncing. */
    fun syncCandidates(): List<LocalSyncRow> = q.selectLibrary().executeAsList()
        .filter { !it.imdbId.isNullOrBlank() }
        .map {
            LocalSyncRow(
                TitleId(MediaType.valueOf(it.type), it.tmdbId), it.name, it.imdbId!!,
                LibraryStatus.valueOf(it.status), it.completion, it.lastActivityAt,
            )
        }

    /** Sets progress with an explicit activity time, so a pulled change is not mistaken for a newer local one. */
    fun setProgressAt(id: TitleId, status: LibraryStatus, completion: Double, activityAt: Long) {
        require(completion in 0.0..1.0) { "completion must be 0..1" }
        q.updateProgressAt(status.name, completion, activityAt, id.type.name, id.tmdbId)
    }

    fun logSync(service: String, item: String, action: String, localTs: Long?, remoteTs: Long?) =
        q.insertSyncEvent(clock(), service, item, action, localTs, remoteTs)

    fun recentSyncEvents(limit: Int = 20): List<SyncLogLine> =
        q.selectRecentSyncEvents(limit.toLong()).executeAsList().map { SyncLogLine(it.at, it.service, it.item, it.action, it.localTs, it.remoteTs) }

    /** The whole library, most recently active first, as candidates that can open a detail page. */
    fun libraryItems(): List<LibraryItemView> = q.selectLibrary().executeAsList()
        .sortedByDescending { it.lastActivityAt }
        .map {
            LibraryItemView(
                Candidate(
                    TitleId(MediaType.valueOf(it.type), it.tmdbId), it.name, it.year?.toInt(),
                    it.genres.ints().toSet(), posterPath = it.posterPath, imdbId = it.imdbId,
                ),
                LibraryStatus.valueOf(it.status), it.completion,
            )
        }

    fun setImdbId(id: TitleId, imdbId: String) = q.updateImdbId(imdbId, id.type.name, id.tmdbId)

    /** Watched episodes of a show with the time each was marked, as (season, episode, epochMillis). */
    fun watchedEpisodeTimes(show: TitleId): List<Triple<Int, Int, Long>> =
        q.selectAllWatchedEpisodes().executeAsList().filter { it.tmdbId == show.tmdbId }.map { Triple(it.season.toInt(), it.episode.toInt(), it.watchedAt) }

    /** Sets one episode's watched time (used when pulling from another service). */
    fun markEpisodeWatchedAt(show: TitleId, season: Int, episode: Int, at: Long) =
        q.markEpisodeWatched(show.tmdbId, season.toLong(), episode.toLong(), at)

    /** Titles being watched, most recent activity first. */
    fun inProgress(limit: Int = 20): List<LibraryItemView> = libraryItems().filter { it.status == LibraryStatus.WATCHING }.take(limit)

    /** Finished titles, most recent first. */
    fun recentlyWatched(limit: Int = 12): List<LibraryItemView> = libraryItems().filter { it.status == LibraryStatus.COMPLETED }.take(limit)

    fun libraryStatus(id: TitleId): String? =
        q.selectLibrary().executeAsList().firstOrNull { it.type == id.type.name && it.tmdbId == id.tmdbId }?.status

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

    fun voteFor(id: TitleId): InterestVote? = votedTitle(id)?.vote

    fun votedTitle(id: TitleId): VotedTitle? = votes().firstOrNull { it.vote.titleId == id }

    fun deleteVote(id: TitleId) = q.deleteVote(id.type.name, id.tmdbId)

    /** Restores a previous vote exactly, including its original time and source (used by undo). */
    fun restoreVote(v: VotedTitle) {
        val id = v.vote.titleId
        q.upsertVote(
            id.type.name, id.tmdbId, v.vote.vote.name, v.vote.source.name, v.vote.atMillis,
            v.features.genres.joinToString(","), v.features.keywords.joinToString(","), v.features.cast.joinToString(","),
        )
    }

    /** Quick add for a recommendation candidate. */
    fun quickAdd(c: Candidate, source: VoteSource = VoteSource.SWIPE) = quickAdd(
        TmdbTitle(c.id, c.name, c.year, c.posterPath, c.genres, c.voteAverage), c.features(), c.imdbId, source,
    )

    /**
     * Quick add: puts the title in the library as Planned, and counts it as Yes unless the user already voted.
     */
    fun quickAdd(t: TmdbTitle, features: TitleFeatures, imdbId: String? = null, source: VoteSource = VoteSource.SWIPE) {
        addToLibrary(t, imdbId)
        if (voteFor(t.id) == null) castVote(t.id, Vote.YES, source, features)
    }

    // ---- Episodes ----

    fun setEpisodeWatched(show: TitleId, season: Int, episode: Int, watched: Boolean) {
        if (watched) q.markEpisodeWatched(show.tmdbId, season.toLong(), episode.toLong(), clock())
        else q.unmarkEpisodeWatched(show.tmdbId, season.toLong(), episode.toLong())
    }

    /** Watched episodes of a show as (season, episode) pairs. */
    fun watchedEpisodes(show: TitleId): Set<Pair<Int, Int>> =
        q.selectWatchedEpisodes(show.tmdbId).executeAsList().map { it.season.toInt() to it.episode.toInt() }.toSet()

    /** Cached AI text for an episode (or a season when [episode] is 0). Null if missing or from an older prompt version. */
    fun summary(show: TitleId, season: Int, episode: Int, lang: String, promptVersion: Int): String? =
        q.selectSummary(show.tmdbId, season.toLong(), episode.toLong(), lang).executeAsOneOrNull()
            ?.takeIf { it.promptVersion == promptVersion.toLong() }?.text

    fun saveSummary(show: TitleId, season: Int, episode: Int, lang: String, promptVersion: Int, text: String) =
        q.upsertSummary(show.tmdbId, season.toLong(), episode.toLong(), lang, promptVersion.toLong(), text, clock())

    // ---- Backup access ----

    fun allLibraryRows() = q.selectLibrary().executeAsList()
    fun allRatingRows() = q.selectRatings().executeAsList()
    fun allVoteRows() = q.selectVotes().executeAsList()
    fun allWatchedRows() = q.selectAllWatchedEpisodes().executeAsList()

    fun restoreLibraryRow(
        type: String, tmdbId: Long, name: String, year: Long?, posterPath: String?, imdbId: String?, genres: String,
        status: String, completion: Double, abandoned: Long, addedAt: Long, lastActivityAt: Long,
    ) = q.upsertLibrary(type, tmdbId, name, year, posterPath, imdbId, genres, status, completion, abandoned, addedAt, lastActivityAt)

    fun restoreRating(type: String, tmdbId: Long, score: Long, ratedAt: Long) = q.upsertRating(type, tmdbId, score, ratedAt)

    fun restoreVoteRow(type: String, tmdbId: Long, vote: String, source: String, at: Long, genres: String, keywords: String, cast: String) =
        q.upsertVote(type, tmdbId, vote, source, at, genres, keywords, cast)

    fun restoreWatched(tmdbId: Long, season: Long, episode: Long, at: Long) = q.markEpisodeWatched(tmdbId, season, episode, at)

    // ---- Actors ----

    fun setActorPref(personId: Long, name: String, pref: ActorPref?) {
        if (pref == null) q.deleteActor(personId) else q.upsertActor(personId, name, pref.name)
    }

    /** Actors with names, for the settings list. */
    fun actorRows(): List<ActorRow> = q.selectActors().executeAsList().map { ActorRow(it.personId, it.name, ActorPref.valueOf(it.pref)) }

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

    // ---- External ratings cache ----

    fun cachedRatings(imdbId: String): CachedRatings? = q.selectRatingCache(imdbId).executeAsOneOrNull()
        ?.let { CachedRatings(it.imdb, it.rottenTomatoes?.toInt(), it.fetchedAt) }

    fun cacheRatings(imdbId: String, imdb: Double?, rottenTomatoes: Int?) =
        q.upsertRatingCache(imdbId, imdb, rottenTomatoes?.toLong(), clock())

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

    private fun setting(key: String): String? = getSetting(key)

    fun getSetting(key: String): String? = q.getSetting(key).executeAsOneOrNull()

    fun putSetting(key: String, value: String) = q.putSetting(key, value)

    private fun String.ints() = if (isBlank()) emptyList() else split(",").mapNotNull { it.toIntOrNull() }
    private fun String.longs() = if (isBlank()) emptyList() else split(",").mapNotNull { it.toLongOrNull() }
}
