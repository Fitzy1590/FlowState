package com.fitzy.flowstate.data

import com.fitzy.flowstate.data.tmdb.TmdbCatalog
import com.fitzy.flowstate.data.tmdb.TmdbClient
import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.domain.TitleId
import com.fitzy.flowstate.secrets.SecretNames
import com.fitzy.flowstate.secrets.SecretStore
import io.ktor.client.HttpClient

/**
 * Uses TMDB when a token is saved, and the built-in sample list otherwise.
 * Re-reads the token on every call, so saving a new one in Settings takes effect on the next load.
 * [http] must already have JSON handling installed (see `withTmdbJson`).
 */
class SecretAwareCatalog(
    private val secrets: SecretStore,
    private val http: HttpClient,
    private val seeds: () -> Set<TitleId>,
    private val clock: () -> Long,
    private val fallback: Catalog = SampleCatalog(),
) : Catalog {
    private var token: String? = null
    private var delegate: Catalog? = null

    override suspend fun pool(): List<Candidate> {
        val t = secrets.get(SecretNames.TMDB)?.takeIf { it.isNotBlank() }
        if (t == null) {
            token = null
            delegate = null
            return fallback.pool()
        }
        if (t != token) {
            token = t
            delegate = TmdbCatalog(TmdbClient(http, apiKey = { t }), seeds, clock)
        }
        return delegate!!.pool()
    }
}
