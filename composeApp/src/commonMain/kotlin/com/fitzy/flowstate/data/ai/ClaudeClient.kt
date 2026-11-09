package com.fitzy.flowstate.data.ai

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

@Serializable internal data class MessageIn(val role: String, val content: String)

@Serializable
internal data class MessagesRequest(
    val model: String,
    val max_tokens: Int,
    val system: String,
    val messages: List<MessageIn>,
)

@Serializable internal data class ContentBlock(val type: String = "", val text: String = "")

@Serializable internal data class MessagesResponse(val content: List<ContentBlock> = emptyList())

/**
 * Minimal Anthropic Messages API client. [apiKey] is read per request from encrypted storage.
 * Never throws: no key, a network error or a non-success status all return null.
 * Request and response shapes follow Anthropic's public Messages API docs and have not been run against the live API here.
 */
class ClaudeClient(
    private val http: HttpClient,
    private val apiKey: () -> String?,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://api.anthropic.com",
) {
    val hasKey: Boolean get() = !apiKey().isNullOrBlank()

    suspend fun complete(system: String, user: String, maxTokens: Int = 300): String? {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val resp = http.post("$baseUrl/v1/messages") {
                header("x-api-key", key)
                header("anthropic-version", API_VERSION)
                contentType(ContentType.Application.Json)
                setBody(MessagesRequest(model, maxTokens, system, listOf(MessageIn("user", user))))
            }
            if (!resp.status.isSuccess()) return@runCatching null
            resp.body<MessagesResponse>().content.filter { it.type == "text" }.joinToString("") { it.text }.trim().ifEmpty { null }
        }.getOrNull()
    }

    companion object {
        const val DEFAULT_MODEL = "claude-haiku-5-5"
        const val API_VERSION = "2023-06-01"
    }
}
