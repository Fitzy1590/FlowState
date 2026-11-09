package com.fitzy.flowstate.search

import com.fitzy.flowstate.data.ai.ClaudeClient
import com.fitzy.flowstate.domain.MediaType
import kotlinx.serialization.json.*

/** Turns free text into a [SearchQuery]. */
interface QueryInterpreter {
    /** Null when it could not be understood or the service is unavailable. */
    suspend fun interpret(text: String): SearchQuery?
}

/**
 * Asks Claude to fill in a [SearchQuery]. Claude only describes what to look for; it never names results,
 * so every title shown comes from the movie database.
 */
class ClaudeQueryInterpreter(private val claude: ClaudeClient) : QueryInterpreter {
    val available get() = claude.hasKey

    override suspend fun interpret(text: String): SearchQuery? {
        val reply = claude.complete(SYSTEM, text.take(500), maxTokens = 300) ?: return null
        return parse(reply)
    }

    companion object {
        private val SYSTEM = """
            You turn a request for something to watch into a JSON search description for a movie and TV database.
            Reply with only one JSON object, no other text, with these keys:
            "mediaTypes": array of "movie" and/or "tv" (both if the user does not say),
            "genres": array of genre names chosen only from: ${Genres.names.joinToString(", ")},
            "yearFrom": number or null, "yearTo": number or null ("old" or "classic" means yearTo 1999),
            "popular": true if the user wants well known or popular titles, otherwise false,
            "seedTitles": array of titles the user says they liked or wants similar to (copy them as written),
            "keywords": array of short mood or theme words (for example "cozy", "mind-bending").
            Use null or [] for anything the user did not mention. Never invent titles.
        """.trimIndent()

        /** Reads the JSON object out of a reply, ignoring any text or code fence around it. */
        fun parse(reply: String): SearchQuery? {
            val start = reply.indexOf('{'); val end = reply.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val o = runCatching { Json.parseToJsonElement(reply.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
            fun strings(key: String) = (o[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) } ?: emptyList()
            fun year(key: String) = (o[key] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1870..2100 }
            val types = strings("mediaTypes").mapNotNull { when (it.lowercase()) { "movie", "movies", "film" -> MediaType.MOVIE; "tv", "show", "series" -> MediaType.SHOW; else -> null } }.toSet()
            val keywords = strings("keywords").toSet()
            val genres = (strings("genres").filter(Genres::isKnown).map(Genres::normalize) + Moods.genresFor(keywords)).toSet()
            return SearchQuery(
                mediaTypes = types.ifEmpty { setOf(MediaType.MOVIE, MediaType.SHOW) },
                genres = genres,
                yearFrom = year("yearFrom"), yearTo = year("yearTo"),
                popular = (o["popular"] as? JsonPrimitive)?.booleanOrNull == true,
                seedTitles = strings("seedTitles"),
                keywords = keywords,
            )
        }
    }
}

/** Used without a Claude key. Understands genre and year words, "like X and Y", and falls back to a title search. */
object LocalQueryInterpreter : QueryInterpreter {
    override suspend fun interpret(text: String): SearchQuery? = parse(text)

    fun parse(text: String): SearchQuery? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val lower = t.lowercase()
        // "Show me ..." is a request, not a wish for TV shows.
        val forTypes = lower.replace(Regex("\\b(show|give|find|get|list)\\s+me\\b|\\ba list of\\b"), " ")
        val words = Regex("[a-z0-9'&-]+").findAll(lower).map { it.value }.toList()
        val typeWords = Regex("[a-z0-9'&-]+").findAll(forTypes).map { it.value }.toList()

        val wantsMovies = typeWords.any { it in setOf("movie", "movies", "film", "films") }
        val wantsShows = typeWords.any { it in setOf("show", "shows", "series", "tv") }
        val types = when {
            wantsMovies && !wantsShows -> setOf(MediaType.MOVIE)
            wantsShows && !wantsMovies -> setOf(MediaType.SHOW)
            else -> setOf(MediaType.MOVIE, MediaType.SHOW)
        }

        val genres = (words.filter(Genres::isKnown).map(Genres::normalize) +
            (if ("sci-fi" in lower || "sci fi" in lower) listOf("science fiction") else emptyList()) +
            Moods.genresFor(words)).toSet()

        var yearFrom: Int? = null
        var yearTo: Int? = null
        if (words.any { it in setOf("old", "classic", "classics", "vintage") }) yearTo = 1999
        Regex("\\b(19|20)?(\\d0)s\\b").find(lower)?.let { m ->
            val decade = m.groupValues[2].toInt().let { if (m.groupValues[1].isNotEmpty()) (m.groupValues[1] + m.groupValues[2]).toInt() else if (it >= 30) 1900 + it else 2000 + it }
            yearFrom = decade; yearTo = decade + 9
        }
        val popular = words.any { it in setOf("popular", "famous", "best", "top", "well-known", "acclaimed") }

        val seeds = Regex("(?:liked|like|love|loved|similar to|more like|such as)\\s+(.+)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)
            // Cut the seed list where the rest of the request starts ("..., can you show me ...", "and let me pick ...").
            ?.replace(Regex("(?i)[,.;!?]?\\s*\\b(can you|could you|please|show me|give me|find me|and let me|let me|so that|so i)\\b.*$"), "")
            ?.split(Regex(",|\\band\\b|&"))?.map { it.trim().trimEnd('.', '?', '!', ',') }
            ?.filter { it.length > 1 } ?: emptyList()

        val q = SearchQuery(types, genres, yearFrom, yearTo, popular, seeds)
        return if (q.isEmpty) q.copy(titleSearch = t) else q
    }
}
