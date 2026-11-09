package com.fitzy.flowstate.data.tmdb

import com.fitzy.flowstate.domain.MediaType
import com.fitzy.flowstate.domain.TitleId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ListResponse(val results: List<ResultDto> = emptyList())

@Serializable
internal data class FindResponse(
    @SerialName("movie_results") val movieResults: List<ResultDto> = emptyList(),
    @SerialName("tv_results") val tvResults: List<ResultDto> = emptyList(),
)

@Serializable
internal data class ResultDto(
    val id: Long,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("vote_average") val voteAverage: Double = 0.0,
)

@Serializable
internal data class DetailsDto(
    val id: Long,
    val title: String? = null,
    val name: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    val genres: List<IdName> = emptyList(),
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val overview: String? = null,
    val seasons: List<SeasonDto> = emptyList(),
    @SerialName("next_episode_to_air") val nextEpisode: NextEpisodeDto? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("external_ids") val externalIds: ExternalIds? = null,
    val credits: Credits? = null,
    val keywords: KeywordsDto? = null,
    val videos: VideosDto? = null,
)

@Serializable
internal data class SeasonDto(
    @SerialName("season_number") val number: Int,
    @SerialName("episode_count") val episodeCount: Int = 0,
    val name: String = "",
)

@Serializable internal data class SeasonResponse(val episodes: List<EpisodeDto> = emptyList())

@Serializable
internal data class EpisodeDto(
    @SerialName("episode_number") val number: Int,
    @SerialName("season_number") val season: Int = 0,
    val name: String = "",
    val overview: String? = null,
    @SerialName("air_date") val airDate: String? = null,
)

@Serializable internal data class IdName(val id: Int, val name: String = "")
@Serializable internal data class ExternalIds(@SerialName("imdb_id") val imdbId: String? = null)
@Serializable internal data class Credits(val cast: List<CastDto> = emptyList())
@Serializable internal data class CastDto(val id: Long, val name: String = "", val order: Int = 0, val character: String = "")

@Serializable
internal data class NextEpisodeDto(
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("season_number") val season: Int = 0,
    @SerialName("episode_number") val episode: Int = 0,
    val name: String = "",
)

/** Movies return `keywords`, shows return `results`. */
@Serializable
internal data class KeywordsDto(
    val keywords: List<IdName> = emptyList(),
    val results: List<IdName> = emptyList(),
)

@Serializable internal data class VideosDto(val results: List<VideoDto> = emptyList())

@Serializable
internal data class VideoDto(
    val key: String,
    val site: String = "",
    val type: String = "",
    val official: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
)

/** A title as returned by search, similar, recommendations or discover. */
data class TmdbTitle(
    val id: TitleId,
    val name: String,
    val year: Int?,
    val posterPath: String?,
    val genreIds: Set<Int>,
    val voteAverage: Double,
)

data class TmdbDetails(
    val title: TmdbTitle,
    val imdbId: String?,
    /** Cast ids in billing order. */
    val cast: List<Long>,
    val keywords: Set<Int>,
    /** YouTube key of the best trailer, if any. */
    val trailerKey: String?,
    val overview: String = "",
    val seasons: List<SeasonInfo> = emptyList(),
    val castMembers: List<CastMember> = emptyList(),
    val nextEpisode: UpcomingEpisode? = null,
)

data class CastMember(val id: Long, val name: String, val character: String)

data class UpcomingEpisode(val season: Int, val episode: Int, val name: String, val airDate: String)

data class SeasonInfo(val number: Int, val episodeCount: Int, val name: String)

data class EpisodeInfo(val season: Int, val number: Int, val name: String, val overview: String, val airDate: String?)

data class DiscoverQuery(
    val genres: Set<Int> = emptySet(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val minVotes: Int? = null,
)

internal fun ResultDto.toTitle(fallback: MediaType?): TmdbTitle? {
    val type = when (mediaType) {
        "movie" -> MediaType.MOVIE
        "tv" -> MediaType.SHOW
        null -> fallback
        else -> return null // person or unknown
    } ?: return null
    val date = releaseDate ?: firstAirDate
    return TmdbTitle(
        id = TitleId(type, id),
        name = title ?: name ?: return null,
        year = date?.take(4)?.toIntOrNull(),
        posterPath = posterPath,
        genreIds = genreIds.toSet(),
        voteAverage = voteAverage,
    )
}

/** Best trailer: YouTube, type Trailer, official first, then newest. */
internal fun VideosDto.bestTrailerKey(): String? =
    results.filter { it.site == "YouTube" && it.type == "Trailer" }
        .sortedWith(compareByDescending<VideoDto> { it.official }.thenByDescending { it.publishedAt ?: "" })
        .firstOrNull()?.key
