package com.anthonyla.paperize.connections

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
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

/** The picture on a tag's collection card (WallpaperTagCover): no two tags share one while a tag has another to show. */
@Serializable
data class ConnectionsCover(
    val id: String,
    val slug: String,
    val previewUrl: String,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val color: String? = null
)

/** A gallery tag, which Explore also shows as a collection: its count and its card's cover. */
@Serializable
data class ConnectionsTag(val tag: String, val count: Int, val cover: ConnectionsCover? = null)

@Serializable
private data class ConnectionsTags(val tags: List<ConnectionsTag>)

/** A short-lived URL to the full-quality original (WallpaperDownloadResult). */
@Serializable
data class ConnectionsDownload(
    val url: String,
    val fileName: String,
    val contentType: String
) {
    /** The file extension of what [url] serves. */
    val extension: String get() = when (contentType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/avif" -> "avif"
        else -> fileName.substringAfterLast('.', "jpg")
    }
}

/** What the gallery knows of the signed-in member (WallpaperMe): whether their plan opens Remix, read live from Pass. */
@Serializable
data class ConnectionsMe(val isAdmin: Boolean = false, val canRemix: Boolean = false)

/** A wallpaper drawn again in a style the member typed (WallpaperRemixResult): a base64 JPEG, charged to their credits. */
@Serializable
data class ConnectionsRemix(
    val data: String,
    val width: Int,
    val height: Int,
    val credits: Int = 0,
    val fileName: String
)

/**
 * A member's own picture for the gallery (WallpaperUploadRequest): the plane files it as a draft licensed `original`,
 * and an admin publishes it. [credit] is the name to show (never an email address); [rightsConfirmed] is their word
 * that it is theirs to share.
 */
@Serializable
data class ConnectionsUpload(
    val title: String,
    val tags: List<String>,
    val credit: String,
    val rightsConfirmed: Boolean,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val contentType: String,
    val color: String?,
    val originalObjectId: String,
    val previewUrl: String,
    val previewWidth: Int,
    val previewHeight: Int
)

/** What Storage answers a grant with: where to PUT the bytes, and for the public lane the URL anyone reads them at. */
@Serializable
private data class StorageGrant(val id: String = "", val uploadUrl: String = "", val publicUrl: String = "")

@Serializable
private data class ConnectionsRefusal(val error: String? = null, val message: String? = null)

/** The gallery refused a request; [message] is its own sentence when it sent one. */
class ConnectionsApiException(val status: Int, override val message: String) : IOException(message) {
}

/** The Connections Wallpaper plane's public API: browse and set the full picture for everyone, save originals for a signed-in member. */
@Singleton
class ConnectionsApi @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true }

    /** [orientation] is the gallery's portrait, landscape or square; null lists every shape. */
    suspend fun wallpapers(query: String, tag: String?, orientation: String?, cursor: String?, limit: Int = PAGE_SIZE): ConnectionsWallpaperPage {
        val params = buildList {
            add("limit" to limit.toString())
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

    /** A short-lived URL to the full-quality original, to set it as a wallpaper: no sign-in, not counted as a download. */
    suspend fun full(id: String): ConnectionsDownload =
        json.decodeFromString(request("GET", "/v1/wallpapers/full?id=${URLEncoder.encode(id, "UTF-8")}"))

    /** A short-lived URL to the compact screen copy (WebP, sized for any screen): what a Library picture shows until it is downloaded. */
    suspend fun screen(id: String): ConnectionsDownload =
        json.decodeFromString(request("GET", "/v1/wallpapers/screen?id=${URLEncoder.encode(id, "UTF-8")}"))

    /** The original to save to the phone, counted as a download; [token] is the member's AEGIS access token (ConnectionsAccount.accessToken). */
    suspend fun download(id: String, token: String): ConnectionsDownload =
        json.decodeFromString(
            request("POST", "/v1/wallpapers/download", body = json.encodeToString(mapOf("id" to id)), token = token)
        )

    /** Whether the member behind [token] may remix: GET /v1/me. */
    suspend fun me(token: String): ConnectionsMe = json.decodeFromString(request("GET", "/v1/me", token = token))

    /**
     * Draws wallpaper [id] again in the style [prompt] says, charged to the member's credits: POST /v1/remix. The model
     * takes up to half a minute, so this waits longer than any other call. A refusal is a [ConnectionsApiException]:
     * 402 out of credits, 403 a plan without Remix, 422 the model declined, 429 credits paused, 502/503 unavailable.
     */
    suspend fun remix(id: String, prompt: String, token: String): ConnectionsRemix =
        json.decodeFromString(
            request(
                "POST", "/v1/remix",
                body = json.encodeToString(mapOf("id" to id, "prompt" to prompt)),
                token = token,
                timeoutMs = SLOW_TIMEOUT_MS
            )
        )

    /** Files [upload] for review: POST /v1/uploads. Its two files must be in Storage first ([storeOriginal], [storePreview]). */
    suspend fun submit(upload: ConnectionsUpload, token: String) {
        request("POST", "/v1/uploads", body = json.encodeToString(upload), token = token, timeoutMs = SLOW_TIMEOUT_MS)
    }

    /** Puts a new wallpaper's original in Storage's private locker and returns its object id (storage-locker-client.ts upload). */
    suspend fun storeOriginal(bytes: ByteArray, contentType: String, label: String, token: String): String {
        val grant = grant("/v1/objects", "wallpaper_original", bytes, contentType, label, token)
        put(grant.uploadUrl, bytes, contentType, cacheControl = null)
        request("POST", "/v1/objects/${grant.id}/finalize", body = "", token = token, base = STORAGE_URL)
        return grant.id
    }

    /** Puts a new wallpaper's 640px WebP preview on Storage's public lane and returns the URL it is read at (uploadPublic). */
    suspend fun storePreview(bytes: ByteArray, label: String, token: String): String {
        val grant = grant("/v1/public/objects", "wallpaper_preview", bytes, "image/webp", label, token)
        if (grant.publicUrl.isEmpty()) throw IOException("Storage gave the preview no public address")
        put(grant.uploadUrl, bytes, "image/webp", cacheControl = PUBLIC_CACHE_CONTROL)
        request("POST", "/v1/objects/${grant.id}/finalize", body = "", token = token, base = STORAGE_URL)
        return grant.publicUrl
    }

    private suspend fun grant(path: String, flow: String, bytes: ByteArray, contentType: String, label: String, token: String): StorageGrant {
        val body = buildJsonObject {
            put("flow", flow)
            put("sourcePlane", SOURCE_PLANE)
            put("entityType", SOURCE_PLANE)
            put("contentType", contentType)
            put("bytes", bytes.size)
            put("label", label.take(120))
        }
        val grant = json.decodeFromString<StorageGrant>(request("POST", path, body = body.toString(), token = token, base = STORAGE_URL))
        if (grant.id.isEmpty() || grant.uploadUrl.isEmpty()) throw IOException("Storage gave no upload address")
        return grant
    }

    private suspend fun put(url: String, bytes: ByteArray, contentType: String, cacheControl: String?) = withContext(Dispatchers.IO) {
        val connection = open(url, SLOW_TIMEOUT_MS)
        try {
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.setRequestProperty("content-type", contentType)
            cacheControl?.let { connection.setRequestProperty("cache-control", it) }
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status !in 200..299) throw ConnectionsApiException(status, "Storage refused the upload (HTTP $status)")
        } finally {
            connection.disconnect()
        }
    }

    /** Streams the original behind a [full] or [download] URL into [write]. */
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

    /** Streams [url] into [file], written beside it and renamed, so a copy cut short is never taken for a whole one. */
    suspend fun readInto(url: String, file: File) = withContext(Dispatchers.IO) {
        val dir = checkNotNull(file.parentFile).apply { mkdirs() }
        val part = File(dir, "${file.name}.part")
        try {
            readOriginal(url) { input -> part.outputStream().use { input.copyTo(it) } }
            if (!part.renameTo(file)) throw IOException("${file.name} could not be kept")
        } finally {
            part.delete()
        }
    }

    /** An empty [body] posts nothing, with no content type (Storage's finalize takes no body). */
    private suspend fun request(
        method: String,
        path: String,
        body: String? = null,
        token: String? = null,
        base: String = BASE_URL,
        timeoutMs: Int = TIMEOUT_MS
    ): String =
        withContext(Dispatchers.IO) {
            val connection = open(base + path, timeoutMs)
            try {
                connection.requestMethod = method
                connection.setRequestProperty("accept", "application/json")
                token?.let { connection.setRequestProperty("authorization", "Bearer $it") }
                if (body != null) {
                    val bytes = body.toByteArray()
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(bytes.size)
                    if (bytes.isNotEmpty()) connection.setRequestProperty("content-type", "application/json")
                    connection.outputStream.use { it.write(bytes) }
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

    private fun open(url: String, timeoutMs: Int = TIMEOUT_MS): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = timeoutMs
        }

    private fun refusal(text: String, status: Int): String {
        val parsed = runCatching { json.decodeFromString<ConnectionsRefusal>(text) }.getOrNull()
        return parsed?.message ?: parsed?.error ?: "HTTP $status"
    }

    companion object {
        const val BASE_URL = "https://wallpaper.connectionsapi.com"
        const val GALLERY_URL = "https://wallpaper.connections.icu"
        /** Where a member picks a paid plan (Pass), which opens Remix. */
        const val PLANS_URL = "https://pass.connections.icu/"
        private const val STORAGE_URL = "https://storage.connectionsapi.com"
        /** The Storage source plane and entity type of a wallpaper's files (WALLPAPER_SOURCE_PLANE). */
        private const val SOURCE_PLANE = "wallpaper"
        /** What the public lane's PUT carries, as the browser's does (PUBLIC_MEDIA_CACHE_CONTROL). */
        private const val PUBLIC_CACHE_CONTROL = "public, max-age=31536000, immutable"
        /** A remix, and the uploads of its files: the model takes up to half a minute. */
        private const val SLOW_TIMEOUT_MS = 120_000
        private const val PAGE_SIZE = 30
        /** The most one page holds (the gallery's own ceiling): what reading a whole collection asks for, in fewer requests. */
        const val LONGEST_PAGE = 60
        private const val TIMEOUT_MS = 20_000
    }
}
