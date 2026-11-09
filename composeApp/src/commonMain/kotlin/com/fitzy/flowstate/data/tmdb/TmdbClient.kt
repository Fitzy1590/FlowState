package com.fitzy.flowstate.data.tmdb

import com.fitzy.flowstate.domain.MediaType
import com.fitzy.flowstate.domain.TitleId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Applies JSON handling to a client built with any engine (platform engine in the app, MockEngine in tests). */
fun HttpClient.withTmdbJson(): HttpClient = config {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}

/**
 * TMDB v3 client. [apiKey] is read per request so it can come from encrypted storage.
 * A long credential (the v4 read access token) is sent as a Bearer header; a short one (the v3 key) as `api_key`.
 * Response shapes follow TMDB's public docs from memory and are not yet checked against live data.
 */
class TmdbClient(
    private val http: HttpClient,
    private val apiKey: () -> String,
    private val baseUrl: String = "https://api.themoviedb.org/3",
) {
    suspend fun search(query: String): List<TmdbTitle> =
        list("$baseUrl/search/multi", null) { parameter("query", query) }

    suspend fun popular(type: MediaType): List<TmdbTitle> =
        list("$baseUrl/${type.path()}/popular", type)

    suspend fun similar(id: TitleId): List<TmdbTitle> =
        list("$baseUrl/${id.type.path()}/${id.tmdbId}/similar", id.type)

    suspend fun recommendations(id: TitleId): List<TmdbTitle> =
        list("$baseUrl/${id.type.path()}/${id.tmdbId}/recommendations", id.type)

    suspend fun discover(type: MediaType, q: DiscoverQuery): List<TmdbTitle> =
        list("$baseUrl/discover/${type.path()}", type) {
            parameter("sort_by", "vote_count.desc")
            if (q.genres.isNotEmpty()) parameter("with_genres", q.genres.joinToString(","))
            val dateField = if (type == MediaType.MOVIE) "primary_release_date" else "first_air_date"
            q.yearFrom?.let { parameter("$dateField.gte", "$it-01-01") }
            q.yearTo?.let { parameter("$dateField.lte", "$it-12-31") }
            q.minVotes?.let { parameter("vote_count.gte", it) }
        }

    suspend fun details(id: TitleId): TmdbDetails {
        val dto: DetailsDto = http.get("$baseUrl/${id.type.path()}/${id.tmdbId}") {
            authorize()
            parameter("append_to_response", "credits,keywords,videos,external_ids")
        }.body()
        val date = dto.releaseDate ?: dto.firstAirDate
        return TmdbDetails(
            title = TmdbTitle(
                id = id,
                name = dto.title ?: dto.name ?: "",
                year = date?.take(4)?.toIntOrNull(),
                posterPath = dto.posterPath,
                genreIds = dto.genres.map { it.id }.toSet(),
                voteAverage = dto.voteAverage,
            ),
            imdbId = dto.imdbId ?: dto.externalIds?.imdbId,
            cast = dto.credits?.cast?.sortedBy { it.order }?.map { it.id } ?: emptyList(),
            keywords = dto.keywords?.let { (it.keywords + it.results).map { k -> k.id }.toSet() } ?: emptySet(),
            trailerKey = dto.videos?.bestTrailerKey(),
        )
    }

    private suspend fun list(
        url: String,
        fallback: MediaType?,
        extra: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): List<TmdbTitle> {
        val resp: ListResponse = http.get(url) {
            authorize()
            extra()
        }.body()
        return resp.results.mapNotNull { it.toTitle(fallback) }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authorize() {
        val credential = apiKey()
        if (credential.length > BEARER_MIN_LENGTH) header("Authorization", "Bearer $credential")
        else parameter("api_key", credential)
    }

    private fun MediaType.path() = if (this == MediaType.MOVIE) "movie" else "tv"

    private companion object {
        /** v3 keys are 32 characters; v4 tokens are JWTs of 100+ characters. */
        const val BEARER_MIN_LENGTH = 40
    }
}
