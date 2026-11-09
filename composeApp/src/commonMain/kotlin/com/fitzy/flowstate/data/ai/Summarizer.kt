package com.fitzy.flowstate.data.ai

import com.fitzy.flowstate.data.tmdb.EpisodeInfo

/** Turns episode descriptions into short catch-up text (SPEC 7d). */
interface Summarizer {
    val available: Boolean
    suspend fun episode(show: String, ep: EpisodeInfo): String?
    suspend fun catchUp(show: String, season: Int, episodeSummaries: List<String>): String?

    companion object {
        /** Bump when the prompts change so cached text is regenerated. */
        const val PROMPT_VERSION = 1
    }
}

class ClaudeSummarizer(private val claude: ClaudeClient) : Summarizer {
    override val available get() = claude.hasKey

    override suspend fun episode(show: String, ep: EpisodeInfo): String? {
        if (ep.overview.isBlank()) return null // never generate text without a source description
        return claude.complete(
            system = EPISODE_SYSTEM,
            user = "Show: $show\nSeason ${ep.season}, episode ${ep.number}: ${ep.name}\nDescription: ${ep.overview}",
            maxTokens = 200,
        )
    }

    override suspend fun catchUp(show: String, season: Int, episodeSummaries: List<String>): String? {
        if (episodeSummaries.isEmpty()) return null
        val body = episodeSummaries.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
        return claude.complete(
            system = CATCHUP_SYSTEM,
            user = "Show: $show\nSeason $season, episodes the viewer has watched so far:\n$body",
            maxTokens = 350,
        )
    }

    private companion object {
        const val EPISODE_SYSTEM = "You write a 2 to 3 sentence reminder of what happens in one TV episode, in present tense. " +
            "Use only the description provided. Do not add facts, names or events that are not in it, and do not mention other episodes."
        const val CATCHUP_SYSTEM = "You write a 4 to 6 sentence catch-up of a TV season from the episode notes provided, in present tense. " +
            "Use only those notes. Do not add facts, and do not mention anything after the last episode listed."
    }
}
