package com.anthonyla.paperize.connections

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One wallpaper as the public Connections gallery lists it. The wire contract lives in the
 * Connections repo at infra/shared/wallpaper/wallpaper-contract.ts (WallpaperSummary).
 */
@Serializable
data class ConnectionsWallpaper(
    val id: String,
    val slug: String,
    val title: String,
    val tags: List<String> = emptyList(),
    val width: Int,
    val height: Int,
    val previewUrl: String,
    val previewWidth: Int,
    val previewHeight: Int,
    val color: String? = null,
    val credit: String? = null,
    val license: String,
    val sourceUrl: String? = null
)

@Serializable
data class ConnectionsWallpaperPage(
    val items: List<ConnectionsWallpaper>,
    val nextCursor: String? = null
)

@Serializable
data class ConnectionsTag(val tag: String, val count: Int)

@Serializable
private data class ConnectionsTags(val tags: List<ConnectionsTag>)

/** A short-lived URL to the full-quality original (WallpaperDownloadResult). */
@Serializable
data class ConnectionsDownload(
    val url: String,
    val fileName: String,
    val contentType: String
)

@Serializable
private data class ConnectionsRefusal(val error: String? = null, val message: String? = null)

/** The gallery refused a request; [message] is its own sentence when it sent one. */
class ConnectionsApiException(val status: Int, override val message: String) : IOException(message) {
}

/** The Connections Wallpaper plane's public API: browse for everyone, originals for a signed-in member. */
@Singleton
class ConnectionsApi @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true }

    /** [orientation] is the gallery's portrait, landscape or square; null lists every shape. */
    suspend fun wallpapers(query: String, tag: String?, orientation: String?, cursor: String?): ConnectionsWallpaperPage {
        val params = buildList {
            add("limit" to PAGE_SIZE.toString())
            query.trim().takeIf { it.isNotEmpty() }?.let { add("q" to it) }
            tag?.let { add("tag" to it) }
            orientation?.let { add("orientation" to it) }
            cursor?.let { add("cursor" to it) }
        }
        val search = params.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, "UTF-8")}" }
        return json.decodeFromString(request("GET", "/v1/wallpapers?$search"))
    }

    suspend fun tags(): List<ConnectionsTag> =
        json.decodeFromString<ConnectionsTags>(request("GET", "/v1/tags")).tags

    /** [token] is the member's AEGIS access token (ConnectionsAccount.accessToken). */
    suspend fun download(id: String, token: String): ConnectionsDownload =
        json.decodeFromString(
            request("POST", "/v1/wallpapers/download", body = json.encodeToString(mapOf("id" to id)), token = token)
        )

    /** Streams the original behind a [download] URL into [write]. */
    suspend fun <T> readOriginal(url: String, write: (InputStream) -> T): T = withContext(Dispatchers.IO) {
        val connection = open(url)
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw ConnectionsApiException(status, "The original answered HTTP $status")
            connection.inputStream.use(write)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun request(method: String, path: String, body: String? = null, token: String? = null): String =
        withContext(Dispatchers.IO) {
            val connection = open(BASE_URL + path)
            try {
                connection.requestMethod = method
                connection.setRequestProperty("accept", "application/json")
                token?.let { connection.setRequestProperty("authorization", "Bearer $it") }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("content-type", "application/json")
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) throw ConnectionsApiException(status, refusal(text, status))
                text
            } finally {
                connection.disconnect()
            }
        }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }

    private fun refusal(text: String, status: Int): String {
        val parsed = runCatching { json.decodeFromString<ConnectionsRefusal>(text) }.getOrNull()
        return parsed?.message ?: parsed?.error ?: "HTTP $status"
    }

    companion object {
        const val BASE_URL = "https://wallpaper.connectionsapi.com"
        const val GALLERY_URL = "https://wallpaper.connections.icu"
        private const val PAGE_SIZE = 30
        private const val TIMEOUT_MS = 20_000
    }
}
