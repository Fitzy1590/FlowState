package com.fitzy.flowstate.sync

import com.fitzy.flowstate.domain.MediaType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.datetime.Instant
import kotlinx.serialization.json.*

/**
 * Stremio account API. Endpoints, request bodies and the library item fields follow Stremio's open-source
 * `stremio-core` (login, datastoreGet, datastorePut, collection "libraryItem"). Not run against the live service here.
 *
 * Library items are handled as raw JSON so fields this app does not understand (watched episode bitfield,
 * behavior hints, notification flags) are sent back untouched.
 */
class StremioApi(private val http: HttpClient, private val baseUrl: String = "https://api.strem.io/api") {

    /** Returns the session key, or null on a wrong password or any failure. The password is not kept. */
    suspend fun login(email: String, password: String): String? = call("login", buildJsonObject {
        put("type", "Login"); put("email", email); put("password", password); put("facebook", false)
    })?.jsonObject?.get("authKey")?.jsonPrimitive?.contentOrNull

    /** All library items, or null if the request failed (an empty list means the library is really empty). */
    suspend fun library(authKey: String): List<JsonObject>? = call("datastoreGet", buildJsonObject {
        put("authKey", authKey); put("collection", COLLECTION); put("ids", JsonArray(emptyList())); put("all", true)
    })?.jsonArray?.map { it.jsonObject }

    suspend fun putLibrary(authKey: String, items: List<JsonObject>): Boolean = call("datastorePut", buildJsonObject {
        put("authKey", authKey); put("collection", COLLECTION); put("changes", JsonArray(items))
    }) != null

    private suspend fun call(path: String, body: JsonObject): JsonElement? = runCatching {
        val resp = http.post("$baseUrl/$path") { contentType(ContentType.Application.Json); setBody(body) }
        if (!resp.status.isSuccess()) return null
        resp.body<JsonObject>()["result"]
    }.getOrNull()

    companion object {
        const val COLLECTION = "libraryItem"
    }
}

/** Conversion between Stremio library items and [SyncItem]. */
object StremioItems {
    private const val COMPLETE_AT = 0.9

    /** Null for removed items and for ids that are not IMDb ids (other catalogs). */
    fun toSync(o: JsonObject): SyncItem? {
        val id = o["_id"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!id.startsWith("tt")) return null
        if (o["removed"]?.jsonPrimitive?.booleanOrNull == true) return null
        val type = when (o["type"]?.jsonPrimitive?.contentOrNull) { "movie" -> MediaType.MOVIE; "series" -> MediaType.SHOW; else -> return null }
        val state = o["state"]?.jsonObject ?: return null
        val offset = state["timeOffset"]?.jsonPrimitive?.longOrNull ?: 0L
        val duration = state["duration"]?.jsonPrimitive?.longOrNull ?: 0L
        val flagged = (state["flaggedWatched"]?.jsonPrimitive?.longOrNull ?: 0L) > 0
        val fraction = if (duration > 0) (offset.toDouble() / duration).coerceIn(0.0, 1.0) else 0.0
        val completed = flagged || fraction >= COMPLETE_AT
        val activity = state["lastWatched"]?.jsonPrimitive?.contentOrNull?.let(::parseTime)
            ?: o["_mtime"]?.jsonPrimitive?.contentOrNull?.let(::parseTime) ?: 0L
        return SyncItem(id, type, o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(), if (completed) 1.0 else fraction, completed, activity)
    }

    /** Updates only the progress fields of an existing remote item; everything else is kept as it was. */
    fun withLocalProgress(existing: JsonObject, local: SyncItem, nowMillis: Long): JsonObject {
        val state = existing["state"]?.jsonObject ?: JsonObject(emptyMap())
        val duration = state["duration"]?.jsonPrimitive?.longOrNull ?: 0L
        val newState = JsonObject(state + mapOf(
            "lastWatched" to JsonPrimitive(iso(local.lastActivityAt)),
            "timeOffset" to JsonPrimitive(if (local.completed) duration else (local.completion * duration).toLong()),
            "flaggedWatched" to JsonPrimitive(if (local.completed) 1 else 0),
        ))
        return JsonObject(existing + mapOf("state" to newState, "_mtime" to JsonPrimitive(iso(nowMillis))))
    }

    /** A new remote item for a movie that exists only locally. */
    fun newMovie(local: SyncItem, nowMillis: Long): JsonObject = buildJsonObject {
        put("_id", local.imdbId); put("name", local.name); put("type", "movie")
        put("posterShape", "poster"); put("removed", false); put("temp", false)
        put("_ctime", iso(nowMillis)); put("_mtime", iso(nowMillis))
        putJsonObject("state") {
            put("lastWatched", iso(local.lastActivityAt)); put("timeWatched", 0); put("timeOffset", 0)
            put("overallTimeWatched", 0); put("timesWatched", if (local.completed) 1 else 0)
            put("flaggedWatched", if (local.completed) 1 else 0); put("duration", 0); put("video_id", local.imdbId)
        }
        putJsonObject("behaviorHints") {}
    }

    fun parseTime(s: String): Long? = runCatching { Instant.parse(s).toEpochMilliseconds() }.getOrNull()
    fun iso(millis: Long): String = Instant.fromEpochMilliseconds(millis).toString()
}
