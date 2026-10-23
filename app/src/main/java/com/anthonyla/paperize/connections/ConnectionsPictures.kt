package com.anthonyla.paperize.connections

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import coil3.map.Mapper
import coil3.request.Options
import coil3.size.pxOrElse
import com.anthonyla.paperize.core.runCatchingCancellable
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.domain.source.UpcomingPictures
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A gallery picture an album holds by reference: content://<app>.pictures/<id>/<slug>?preview=<640px preview URL>. */
data class Picture(val id: String, val slug: String, val previewUrl: String?)

/** Under files/: the originals the app keeps (res/xml/connections_files.xml lets its FileProvider read them back). */
internal fun connectionsDir(context: Context): File = File(context.filesDir, "connections")

/** Under [connectionsDir]: the previews a downloaded picture shows from. */
internal fun previewsDir(context: Context): File = File(connectionsDir(context), "previews")

/** The original Download (or Set wallpaper) kept of [slug] in [dir], if any; a copy cut short (.part) is never one. */
internal fun downloadedOriginal(dir: File, slug: String): File? =
    dir.listFiles { file -> file.isFile && file.nameWithoutExtension == slug }?.firstOrNull()

/**
 * Library pictures from the Connections gallery. Add to library puts a picture in an album by reference, and nothing
 * comes to the phone until something opens it (ConnectionsPictureProvider): then it opens from its downloaded original
 * when the member chose Download, else from the gallery's compact screen copy, fetched once and kept, so the gallery
 * serves a picture once per phone (Michael, 2026-10-23: "I didn't choose download"; "the next time it doesn't hit my
 * server"). The next picture of each screen is fetched as the one before it goes up, so a change never waits on it.
 */
@Singleton
class ConnectionsPictures @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: ConnectionsApi
) : UpcomingPictures {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val authority: String get() = "${context.packageName}.pictures"
    private val screenDir: File get() = File(connectionsDir(context), "screen")

    fun uriFor(wallpaper: ConnectionsWallpaper): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority(authority)
        .appendPath(wallpaper.id)
        .appendPath(wallpaper.slug)
        .appendQueryParameter(PREVIEW, wallpaper.previewUrl)
        .build()

    /** The picture [uri] names, or null when it is not a Library picture. */
    fun pictureOf(uri: Uri): Picture? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority != authority) return null
        val (id, slug) = uri.pathSegments.takeIf { it.size == 2 } ?: return null
        if (!ID.matches(id) || !SLUG.matches(slug)) return null
        return Picture(id, slug, uri.getQueryParameter(PREVIEW))
    }

    /** What the phone holds of [picture] now, with no fetch: its downloaded original, else its screen copy. */
    fun local(picture: Picture): File? =
        downloadedOriginal(connectionsDir(context), picture.slug) ?: downloadedOriginal(screenDir, picture.slug)

    /** [picture]'s file: what the phone holds, else its screen copy, fetched once however many ask at the same time. */
    suspend fun file(picture: Picture): File = local(picture) ?: locks.computeIfAbsent(picture.slug) { Mutex() }.withLock {
        local(picture) ?: run {
            val copy = api.screen(picture.id)
            File(screenDir, "${picture.slug}.${copy.extension}").also { api.readInto(copy.url, it) }
        }
    }

    /** The preview Download kept of [picture], if any. */
    fun preview(picture: Picture): File? = downloadedOriginal(previewsDir(context), picture.slug)

    /** Drops [slug]'s screen copy once its original is downloaded: the original is what opens from then on. */
    fun dropScreenCopy(slug: String) {
        downloadedOriginal(screenDir, slug)?.delete()
    }

    override fun warm(wallpaper: Wallpaper) {
        val picture = pictureOf(wallpaper.uri.toUri()) ?: return
        if (local(picture) != null) return
        scope.launch {
            runCatchingCancellable { file(picture) }
                .onFailure { Log.i(TAG, "The next picture could not be fetched ahead; its change fetches it", it) }
        }
    }

    private companion object {
        const val TAG = "ConnectionsPictures"
        const val PREVIEW = "preview"
        val ID = Regex("[0-9a-fA-F-]{36}")
        val SLUG = Regex("[a-z0-9][a-z0-9-]{0,199}")
    }
}

/**
 * Draws a Library picture in the app's own screens from what the phone holds. A thumbnail of one the phone holds
 * nothing of shows the gallery's 640px preview instead of fetching the picture, and a large view opens the picture
 * itself, as the wallpaper does. Registered for both Uri and String models, since screens hand Coil either.
 */
class PictureImages(private val pictures: ConnectionsPictures) {
    val forUri = Mapper<Uri, Any> { data, options -> map(data, options) }
    val forString = Mapper<String, Any> { data, options -> if (data.startsWith("content://")) map(data.toUri(), options) else null }

    private fun map(data: Uri, options: Options): Any? {
        val picture = pictures.pictureOf(data) ?: return null
        val thumbnail = maxOf(options.size.width.pxOrElse { Int.MAX_VALUE }, options.size.height.pxOrElse { Int.MAX_VALUE }) <= THUMBNAIL_PX
        if (!thumbnail) return pictures.local(picture)
        return pictures.preview(picture) ?: pictures.local(picture) ?: picture.previewUrl
    }

    private companion object {
        /** The largest drawing a 640px preview serves (a grid tile, an album cover). */
        const val THUMBNAIL_PX = 800
    }
}
