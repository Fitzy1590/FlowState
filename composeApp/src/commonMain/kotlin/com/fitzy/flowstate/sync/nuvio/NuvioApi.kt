package com.fitzy.flowstate.sync.nuvio

import com.fitzy.flowstate.secrets.SecretNames
import com.fitzy.flowstate.secrets.SecretStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** What is kept (encrypted) about a connected Nuvio account. Never the password. */
@Serializable
data class NuvioAccount(
    val backendUrl: String,
    val publishableKey: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val profileIndex: Int = 1,
    val profileName: String = "",
)

class NuvioAccountStore(private val secrets: SecretStore) {
    private val json = Json { ignoreUnknownKeys = true }
    fun load(): NuvioAccount? = secrets.get(SecretNames.NUVIO_ACCOUNT)?.let { runCatching { json.decodeFromString<NuvioAccount>(it) }.getOrNull() }
    fun save(a: NuvioAccount) = secrets.put(SecretNames.NUVIO_ACCOUNT, json.encodeToString(a))
    fun clear() = secrets.remove(SecretNames.NUVIO_ACCOUNT)
}

data class NuvioProfile(val index: Int, val name: String)

/** A title or episode marked watched on the server. [type] is "movie" or "series". */
data class WatchedRow(val contentId: String, val type: String, val title: String, val season: Int?, val episode: Int?, val watchedAt: Long)

/** Partial progress on the server. Times are milliseconds. */
data class ProgressRow(
    val contentId: String, val type: String, val videoId: String, val season: Int?, val episode: Int?,
    val position: Long, val duration: Long, val lastWatched: Long, val progressKey: String,
)

/**
 * Nuvio's account API: Supabase email and password sign-in, then the same sync functions Nuvio's own apps call.
 * Names, parameters and field names are taken from the source of NuvioMedia/NuvioMobile (`SupabaseWatchedSyncAdapter`,
 * `SupabaseProgressSyncAdapter`, `ProfileRepository`). Never run against a real server from here.
 * Every call returns null on failure, so "failed" is never mistaken for "empty".
 */
class NuvioApi(
    private val http: HttpClient,
    private val store: NuvioAccountStore,
    private val clientId: () -> String,
    private val clock: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Signs in and returns the account to keep, or null for a wrong password or any failure. */
    suspend fun signIn(server: NuvioServer, email: String, password: String): NuvioAccount? = tokenRequest(
        server.backendUrl, server.publishableKey, "password",
        buildJsonObject { put("email", email); put("password", password) },
    )?.toAccount(server.backendUrl, server.publishableKey)

    suspend fun profiles(): List<NuvioProfile>? = (rpc("sync_pull_profiles", buildJsonObject {}) as? JsonArray)?.map {
        val o = it.jsonObject
        NuvioProfile(o["profile_index"]?.jsonPrimitive?.intOrNull ?: 1, o["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
    }

    suspend fun pullWatched(profile: Int): List<WatchedRow>? {
        val all = ArrayList<WatchedRow>()
        var firstOfLastPage: WatchedRow? = null
        for (page in 1..MAX_PAGES) {
            val rows = (rpc("sync_pull_watched_items", buildJsonObject { put("p_profile_id", profile); put("p_page", page); put("p_page_size", PAGE_SIZE) }) as? JsonArray)
                ?.map { toWatched(it.jsonObject) } ?: return null
            if (rows.isEmpty() || rows.first() == firstOfLastPage) break // end, or a server that ignores paging
            all += rows
            firstOfLastPage = rows.first()
            if (rows.size < PAGE_SIZE) break
        }
        return all
    }

    suspend fun pushWatched(profile: Int, rows: List<WatchedRow>): Boolean = rows.chunked(CHUNK).all { chunk ->
        rpc("sync_push_watched_items", buildJsonObject {
            put("p_profile_id", profile)
            put("p_items", JsonArray(chunk.map { r ->
                buildJsonObject {
                    put("content_id", r.contentId); put("content_type", r.type); put("title", r.title)
                    put("season", r.season); put("episode", r.episode); put("watched_at", r.watchedAt)
                }
            }))
            put("p_origin_client_id", clientId())
        }) != null
    }

    suspend fun pullProgress(profile: Int): List<ProgressRow>? =
        (rpc("sync_pull_watch_progress", buildJsonObject { put("p_profile_id", profile) }) as? JsonArray)?.map { toProgress(it.jsonObject) }

    suspend fun pushProgress(profile: Int, rows: List<ProgressRow>): Boolean = rows.chunked(CHUNK).all { chunk ->
        rpc("sync_push_watch_progress", buildJsonObject {
            put("p_profile_id", profile)
            put("p_entries", JsonArray(chunk.map { r ->
                buildJsonObject {
                    put("content_id", r.contentId); put("content_type", r.type); put("video_id", r.videoId)
                    put("season", r.season); put("episode", r.episode); put("position", r.position); put("duration", r.duration)
                    put("last_watched", r.lastWatched); put("progress_key", r.progressKey)
                }
            }))
            put("p_origin_client_id", clientId())
        }) != null
    }

    // ---- plumbing ----

    /** POSTs to a PostgREST function. Refreshes the login once if it has expired or the server says 401. */
    private suspend fun rpc(name: String, params: JsonObject): JsonElement? {
        var account = store.load() ?: return null
        if (account.expiresAtMs - clock() < REFRESH_MARGIN_MS) account = refresh(account) ?: return null
        var result = call(account, name, params)
        if (result.unauthorized) {
            account = refresh(account) ?: return null
            result = call(account, name, params)
        }
        return result.body
    }

    private class Call(val body: JsonElement?, val unauthorized: Boolean)

    private suspend fun call(a: NuvioAccount, name: String, params: JsonObject): Call = runCatching {
        val resp = http.post("${a.backendUrl}/rest/v1/rpc/$name") {
            header("apikey", a.publishableKey)
            header("Authorization", "Bearer ${a.accessToken}")
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        when {
            resp.status.value == 401 -> Call(null, true)
            !resp.status.isSuccess() -> Call(null, false)
            else -> Call(runCatching { resp.body<JsonElement>() }.getOrNull() ?: JsonNull, false)
        }
    }.getOrDefault(Call(null, false))

    private suspend fun refresh(a: NuvioAccount): NuvioAccount? {
        val updated = tokenRequest(a.backendUrl, a.publishableKey, "refresh_token", buildJsonObject { put("refresh_token", a.refreshToken) })
            ?.toAccount(a.backendUrl, a.publishableKey)?.copy(profileIndex = a.profileIndex, profileName = a.profileName) ?: return null
        store.save(updated)
        return updated
    }

    private suspend fun tokenRequest(backend: String, key: String, grant: String, body: JsonObject): JsonObject? = runCatching {
        val resp = http.post("$backend/auth/v1/token?grant_type=$grant") {
            header("apikey", key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!resp.status.isSuccess()) null else resp.body<JsonObject>()
    }.getOrNull()

    private fun JsonObject.toAccount(backend: String, key: String): NuvioAccount? {
        val access = this["access_token"]?.jsonPrimitive?.contentOrNull ?: return null
        val refresh = this["refresh_token"]?.jsonPrimitive?.contentOrNull ?: return null
        val seconds = this["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        return NuvioAccount(backend, key, access, refresh, clock() + seconds * 1000)
    }

    private fun toWatched(o: JsonObject) = WatchedRow(
        o["content_id"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["content_type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["season"]?.jsonPrimitive?.intOrNull, o["episode"]?.jsonPrimitive?.intOrNull,
        normalizeWatchedAt(o["watched_at"]?.jsonPrimitive?.longOrNull ?: 0L),
    )

    private fun toProgress(o: JsonObject) = ProgressRow(
        o["content_id"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["content_type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        o["video_id"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["season"]?.jsonPrimitive?.intOrNull, o["episode"]?.jsonPrimitive?.intOrNull,
        o["position"]?.jsonPrimitive?.longOrNull ?: 0L, o["duration"]?.jsonPrimitive?.longOrNull ?: 0L,
        o["last_watched"]?.jsonPrimitive?.longOrNull ?: 0L, o["progress_key"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )

    private companion object {
        const val PAGE_SIZE = 500
        const val CHUNK = 200
        const val MAX_PAGES = 200
        const val REFRESH_MARGIN_MS = 60_000L
    }
}

/**
 * Nuvio has an older watched-time format, a 14 digit number such as 20240131235959 (yyyyMMddHHmmss, UTC).
 * Anything else is already epoch milliseconds.
 */
fun normalizeWatchedAt(value: Long): Long {
    if (value !in 10_000_000_000_000L..99_999_999_999_999L) return value
    val s = value.toString()
    return runCatching {
        LocalDateTime(
            s.substring(0, 4).toInt(), s.substring(4, 6).toInt(), s.substring(6, 8).toInt(),
            s.substring(8, 10).toInt(), s.substring(10, 12).toInt(), s.substring(12, 14).toInt(),
        ).toInstant(TimeZone.UTC).toEpochMilliseconds()
    }.getOrDefault(value)
}
