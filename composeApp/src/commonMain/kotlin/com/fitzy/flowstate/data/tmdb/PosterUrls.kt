package com.fitzy.flowstate.data.tmdb

/** Poster URLs: TMDB image CDN first, Metahub (by IMDb id) as fallback. */
object PosterUrls {
    enum class Size(val path: String) { SMALL("w185"), MEDIUM("w342"), LARGE("w500"), ORIGINAL("original") }

    fun tmdb(posterPath: String, size: Size = Size.MEDIUM): String =
        "https://image.tmdb.org/t/p/${size.path}$posterPath"

    /** URL pattern not yet verified against the live service. */
    fun metahub(imdbId: String, size: String = "medium"): String =
        "https://images.metahub.space/poster/$size/$imdbId/img"

    /** Ordered candidates to try: TMDB when a poster path exists, then Metahub when an IMDb id exists. */
    fun candidates(posterPath: String?, imdbId: String?, size: Size = Size.MEDIUM): List<String> =
        listOfNotNull(posterPath?.let { tmdb(it, size) }, imdbId?.let { metahub(it) })
}
