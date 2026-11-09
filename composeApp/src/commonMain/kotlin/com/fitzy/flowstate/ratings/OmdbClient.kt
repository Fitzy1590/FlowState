package com.fitzy.flowstate.ratings

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal data class OmdbRating(@SerialName("Source") val source: String = "", @SerialName("Value") val value: String = "")

@Serializable
internal data class OmdbResponse(
    @SerialName("Response") val response: String = "False",
    val imdbRating: String? = null,
    @SerialName("Ratings") val ratings: List<OmdbRating> = emptyList(),
)

/** IMDb rating (0-10) and Rotten Tomatoes Tomatometer (0-100). OMDb has no Audience score. */
data class ExternalRatings(val imdb: Double?, val rottenTomatoes: Int?)

/** OMDb lookup by IMDb id. Needs the user's free key. Shapes follow OMDb's public docs and are not yet run live. */
class OmdbClient(
    private val http: HttpClient,
    private val apiKey: () -> String?,
    private val baseUrl: String = "https://www.omdbapi.com/",
) {
    val hasKey: Boolean get() = !apiKey().isNullOrBlank()

    suspend fun ratings(imdbId: String): ExternalRatings? {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val resp = http.get(baseUrl) { parameter("i", imdbId); parameter("apikey", key) }
            if (!resp.status.isSuccess()) return null
            val r: OmdbResponse = resp.body()
            if (r.response != "True") return null
            ExternalRatings(
                imdb = r.imdbRating?.toDoubleOrNull(),
                rottenTomatoes = r.ratings.firstOrNull { it.source == "Rotten Tomatoes" }?.value?.removeSuffix("%")?.toIntOrNull(),
            )
        }.getOrNull()
    }
}
