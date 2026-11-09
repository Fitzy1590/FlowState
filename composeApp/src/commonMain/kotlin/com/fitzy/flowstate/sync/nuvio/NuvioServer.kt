package com.fitzy.flowstate.sync.nuvio

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Connection details of a self-hosted Nuvio server. The publishable key is meant to be public; it is not a password. */
data class NuvioServer(val backendUrl: String, val publishableKey: String)

enum class DiscoveryFailure { InvalidUrl, OfficialServer, Insecure, ConnectionFailed, HttpError, TooLarge, InvalidDocument, UnsupportedVersion, WrongService, NotSelfHosted, MissingConfiguration, UnsupportedAuth }

class DiscoveryException(val failure: DiscoveryFailure) : Exception(failure.name)

@Serializable
internal data class DiscoveryDocument(
    val version: Int = 0,
    val service: String = "",
    @SerialName("self_hosted") val selfHosted: Boolean = false,
    @SerialName("backend_url") val backendUrl: String = "",
    @SerialName("publishable_key") val publishableKey: String = "",
    val capabilities: DiscoveryCapabilities = DiscoveryCapabilities(),
)

@Serializable
internal data class DiscoveryCapabilities(@SerialName("email_password_auth") val emailPasswordAuth: Boolean = false)

/**
 * Finds a self-hosted Nuvio server's settings the way Nuvio's own apps do: it reads `/.well-known/nuvio` on the server
 * the user typed in (rules taken from the `ServerDiscovery.kt` source of NuvioMedia/NuvioMobile).
 *
 * Nuvio's own apps refuse the official hosted service on this path, because its settings are built into the official
 * apps and are not published. This code refuses it too, and so does not and cannot connect to official Nuvio accounts.
 */
object NuvioDiscovery {
    private const val PATH = "/.well-known/nuvio"
    private const val MAX_BYTES = 64 * 1024
    private const val OFFICIAL_HOST = "api.nuvio.tv"
    private val json = Json { ignoreUnknownKeys = true }

    /** The URL of the discovery file for what the user typed. */
    fun discoveryUrl(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) throw DiscoveryException(DiscoveryFailure.InvalidUrl)
        val withScheme = if ("://" in text) text else "https://$text"
        val url = parse(withScheme, DiscoveryFailure.InvalidUrl)
        if (authority(withScheme).contains('@')) throw DiscoveryException(DiscoveryFailure.InvalidUrl) // no user:password in the address
        val base = url.encodedPath.removeSuffix(PATH).trimEnd('/')
        return origin(url) + base + PATH
    }

    /** Validates a discovery document found at [discoveryUrl] and returns the server settings. */
    fun parseDocument(discoveryUrl: String, body: String): NuvioServer {
        val d = runCatching { json.decodeFromString<DiscoveryDocument>(body) }.getOrElse { throw DiscoveryException(DiscoveryFailure.InvalidDocument) }
        if (d.version != 1) throw DiscoveryException(DiscoveryFailure.UnsupportedVersion)
        if (!d.service.equals("nuvio", ignoreCase = true)) throw DiscoveryException(DiscoveryFailure.WrongService)
        if (!d.selfHosted) throw DiscoveryException(DiscoveryFailure.NotSelfHosted)
        val backend = normalizeBackend(d.backendUrl)
        val key = d.publishableKey.trim()
        if (key.isEmpty()) throw DiscoveryException(DiscoveryFailure.MissingConfiguration)
        if (!d.capabilities.emailPasswordAuth) throw DiscoveryException(DiscoveryFailure.UnsupportedAuth)
        requireSecure(backend)
        return NuvioServer(backend, key)
    }

    suspend fun discover(http: HttpClient, input: String): Result<NuvioServer> = runCatching {
        val url = discoveryUrl(input)
        if (isOfficial(url)) throw DiscoveryException(DiscoveryFailure.OfficialServer)
        requireSecure(url)
        val resp = runCatching { http.get(url) }.getOrElse { throw DiscoveryException(DiscoveryFailure.ConnectionFailed) }
        if (!resp.status.isSuccess()) throw DiscoveryException(DiscoveryFailure.HttpError)
        val text = resp.bodyAsText()
        if (text.length > MAX_BYTES) throw DiscoveryException(DiscoveryFailure.TooLarge)
        val server = parseDocument(url, text)
        if (isOfficial(server.backendUrl)) throw DiscoveryException(DiscoveryFailure.OfficialServer)
        server
    }

    fun isOfficial(url: String): Boolean = runCatching { Url(url).host.equals(OFFICIAL_HOST, ignoreCase = true) }.getOrDefault(false)

    /** Plain http is only allowed for servers on a private network, so a login never crosses the internet unencrypted. */
    private fun requireSecure(url: String) {
        val u = runCatching { Url(url) }.getOrNull() ?: throw DiscoveryException(DiscoveryFailure.InvalidUrl)
        if (u.protocol.name == "http" && isPublicHost(u.host)) throw DiscoveryException(DiscoveryFailure.Insecure)
    }

    internal fun isPublicHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".local") || h == "::1") return false
        if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")) return false
        val p = h.split('.')
        if (p.size == 4) {
            val a = p[0].toIntOrNull(); val b = p[1].toIntOrNull()
            if (a == 172 && b != null && b in 16..31) return false
            if (a == 169 && b == 254) return false
        }
        return true
    }

    private fun normalizeBackend(value: String): String {
        val v = value.trim()
        val u = parse(v, DiscoveryFailure.MissingConfiguration)
        if (authority(v).contains('@') || u.parameters.entries().isNotEmpty() || u.fragment.isNotBlank()) throw DiscoveryException(DiscoveryFailure.MissingConfiguration)
        return (origin(u) + u.encodedPath).trimEnd('/')
    }

    private fun parse(value: String, failure: DiscoveryFailure): Url {
        val u = runCatching { Url(value) }.getOrElse { throw DiscoveryException(failure) }
        if (u.protocol.name != "http" && u.protocol.name != "https") throw DiscoveryException(failure)
        if (u.host.isBlank()) throw DiscoveryException(failure)
        return u
    }

    private fun origin(u: Url): String {
        val defaultPort = if (u.protocol.name == "https") 443 else 80
        val host = if (':' in u.host) "[${u.host}]" else u.host
        return "${u.protocol.name}://$host" + if (u.port == defaultPort) "" else ":${u.port}"
    }

    private fun authority(v: String) = v.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
}

fun DiscoveryFailure.describe(): String = when (this) {
    DiscoveryFailure.InvalidUrl -> "That is not a valid server address."
    DiscoveryFailure.OfficialServer -> "This is the official hosted Nuvio service. Its connection details are not published, so FlowState cannot connect to it. Self-hosted Nuvio servers work."
    DiscoveryFailure.Insecure -> "The server uses plain http over the internet. Use https so your login is encrypted."
    DiscoveryFailure.ConnectionFailed -> "Could not reach the server."
    DiscoveryFailure.HttpError -> "The server did not have the Nuvio discovery file (/.well-known/nuvio)."
    DiscoveryFailure.TooLarge -> "The server's answer was too large to be a Nuvio discovery file."
    DiscoveryFailure.InvalidDocument -> "The server's discovery file could not be read."
    DiscoveryFailure.UnsupportedVersion -> "This server uses a newer discovery format than FlowState understands."
    DiscoveryFailure.WrongService -> "That server is not a Nuvio server."
    DiscoveryFailure.NotSelfHosted -> "That server is not marked as self-hosted."
    DiscoveryFailure.MissingConfiguration -> "The server's discovery file is missing connection details."
    DiscoveryFailure.UnsupportedAuth -> "That server does not offer email and password sign-in."
}
