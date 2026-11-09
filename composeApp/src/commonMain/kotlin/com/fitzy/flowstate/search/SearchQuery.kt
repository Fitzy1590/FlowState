package com.fitzy.flowstate.search

import com.fitzy.flowstate.domain.MediaType

/** What the user asked for, in structured form. Built by Claude or by the local fallback parser. */
data class SearchQuery(
    val mediaTypes: Set<MediaType> = setOf(MediaType.MOVIE, MediaType.SHOW),
    val genres: Set<String> = emptySet(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val popular: Boolean = false,
    /** Titles the user likes or wants more of. */
    val seedTitles: List<String> = emptyList(),
    val keywords: Set<String> = emptySet(),
    /** Plain title search, used when nothing else could be understood. */
    val titleSearch: String? = null,
) {
    val isEmpty get() = genres.isEmpty() && yearFrom == null && yearTo == null && !popular && seedTitles.isEmpty() && titleSearch == null
}

/** Genre names and ids. Ids are TMDB's; written from memory and not yet checked against the live genre list. */
object Genres {
    private val movie = mapOf(
        "action" to 28, "adventure" to 12, "animation" to 16, "comedy" to 35, "crime" to 80, "documentary" to 99,
        "drama" to 18, "family" to 10751, "fantasy" to 14, "history" to 36, "horror" to 27, "music" to 10402,
        "mystery" to 9648, "romance" to 10749, "science fiction" to 878, "thriller" to 53, "war" to 10752, "western" to 37,
    )
    private val tv = mapOf(
        "action" to 10759, "adventure" to 10759, "animation" to 16, "comedy" to 35, "crime" to 80, "documentary" to 99,
        "drama" to 18, "family" to 10751, "kids" to 10762, "mystery" to 9648, "reality" to 10764,
        "science fiction" to 10765, "fantasy" to 10765, "war" to 10768, "history" to 10768, "western" to 37,
    )

    /** Names the interpreters may use. */
    val names: List<String> = movie.keys.toList()

    fun normalize(name: String): String = when (val n = name.trim().lowercase()) {
        "sci-fi", "scifi", "sci fi" -> "science fiction"
        "sci-fi & fantasy" -> "science fiction"
        "war & politics" -> "war"
        else -> n
    }

    fun ids(names: Set<String>, type: MediaType): Set<Int> =
        names.mapNotNull { (if (type == MediaType.MOVIE) movie else tv)[normalize(it)] }.toSet()

    fun isKnown(name: String) = normalize(name) in movie || normalize(name) in tv
}

/** Moods to genres, for requests like "something cozy" or "I want to laugh". */
object Moods {
    private val table = mapOf(
        "cozy" to setOf("comedy", "family"), "feel-good" to setOf("comedy", "family"), "funny" to setOf("comedy"),
        "laugh" to setOf("comedy"), "scary" to setOf("horror"), "spooky" to setOf("horror"), "romantic" to setOf("romance"),
        "thrilling" to setOf("thriller"), "tense" to setOf("thriller"), "mind-bending" to setOf("science fiction", "mystery"),
        "dark" to setOf("crime", "thriller"), "epic" to setOf("adventure", "fantasy"), "intense" to setOf("war", "thriller"),
        "sad" to setOf("drama"), "emotional" to setOf("drama"),
    )

    fun genresFor(words: Collection<String>): Set<String> = words.flatMap { table[it.trim().lowercase()].orEmpty() }.toSet()
}
