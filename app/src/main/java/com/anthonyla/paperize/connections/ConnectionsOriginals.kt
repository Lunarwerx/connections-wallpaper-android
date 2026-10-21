package com.anthonyla.paperize.connections

import android.app.WallpaperManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.anthonyla.paperize.core.util.generateId
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.domain.repository.AlbumRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** Where a Connections wallpaper goes on the phone: the home or lock screen right now. */
enum class WallpaperTarget(val flags: Int) {
    BOTH(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK),
    HOME(WallpaperManager.FLAG_SYSTEM),
    LOCK(WallpaperManager.FLAG_LOCK)
}

/**
 * Full-quality Connections originals on the phone. Setting one as the wallpaper or adding it to an
 * album needs no account: the original comes from the gallery's public full door and is kept in the
 * app's own storage (files/connections), which no other app sees. Saving one to the phone is for a
 * signed-in member (the download door, counted): it goes into Pictures/Connections Wallpaper and
 * shows up in the gallery app, and the wallpaper or an album then reuses that copy. The app owns the
 * MediaStore row it inserts, so it reads it back without a storage permission.
 */
class ConnectionsOriginals @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: ConnectionsApi,
    private val albumRepository: AlbumRepository
) {
    /** Returns false when the album already had this wallpaper. */
    suspend fun addToAlbum(wallpaper: ConnectionsWallpaper, albumId: String): Boolean =
        addAllToAlbum(listOf(wallpaper), albumId) > 0

    /** Adds every one of [wallpapers] to the album in one go; returns how many it did not have yet. */
    suspend fun addAllToAlbum(wallpapers: List<ConnectionsWallpaper>, albumId: String): Int {
        if (wallpapers.isEmpty()) return 0
        val entries = wallpapers.map { wallpaper ->
            val (uri, name) = kept(wallpaper)
            Wallpaper(
                id = generateId(),
                albumId = albumId,
                uri = uri.toString(),
                fileName = name,
                dateModified = System.currentTimeMillis()
            )
        }
        return albumRepository.addWallpapersToAlbum(albumId, entries)
    }

    /** Keeps the original and its preview on the phone, so the picture shows and sets with no internet. */
    suspend fun keepOffline(wallpaper: ConnectionsWallpaper) {
        kept(wallpaper)
        keepPreview(wallpaper)
    }

    /** What a saved picture shows from on the phone: its preview, else its original; pictures with neither are left out. */
    suspend fun localImages(wallpapers: List<ConnectionsWallpaper>): Map<String, File> = withContext(Dispatchers.IO) {
        val originals = File(context.filesDir, APP_DIR).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.associateBy { it.nameWithoutExtension }.orEmpty()
        wallpapers.mapNotNull { wallpaper ->
            (previewFile(wallpaper).takeIf { it.isFile } ?: originals[wallpaper.slug])?.let { wallpaper.id to it }
        }.toMap()
    }

    /** The saved preview of [wallpaper], when there is one. */
    fun savedPreview(wallpaper: ConnectionsWallpaper): File? = previewFile(wallpaper).takeIf { it.isFile }

    suspend fun setAsWallpaper(wallpaper: ConnectionsWallpaper, target: WallpaperTarget) {
        val (uri, _) = kept(wallpaper)
        withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("The saved picture could not be opened")
            input.use { WallpaperManager.getInstance(context).setStream(it, null, true, target.flags) }
        }
    }

    /** Saves the original into the phone's pictures with a member's [token]; a copy saved before is kept as it is. */
    suspend fun saveToPhone(wallpaper: ConnectionsWallpaper, token: String) {
        savedOriginal(wallpaper.slug) ?: downloadOriginal(wallpaper, token)
    }

    /** The copy saved to the phone when there is one, else the app's own. */
    private suspend fun kept(wallpaper: ConnectionsWallpaper): Pair<Uri, String> =
        savedOriginal(wallpaper.slug) ?: appCopy(wallpaper)

    /** The app's own copy of the original, fetched once from the full door; a second use costs no second download. */
    private suspend fun appCopy(wallpaper: ConnectionsWallpaper): Pair<Uri, String> {
        val dir = File(context.filesDir, APP_DIR)
        withContext(Dispatchers.IO) { dir.listFiles()?.firstOrNull { it.isFile && it.nameWithoutExtension == wallpaper.slug } }
            ?.let { return contentUri(it) to it.name }
        val full = api.full(wallpaper.id)
        val name = "${wallpaper.slug}.${extensionOf(full)}"
        val file = File(dir, name)
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            // Written beside it and renamed, so a copy cut short is never taken for a whole one.
            val part = File(dir, "$name.part")
            try {
                api.readOriginal(full.url) { input -> part.outputStream().use { input.copyTo(it) } }
                if (!part.renameTo(file)) throw IOException("The picture could not be kept")
            } finally {
                part.delete()
            }
        }
        return contentUri(file) to name
    }

    private suspend fun keepPreview(wallpaper: ConnectionsWallpaper) {
        val file = previewFile(wallpaper)
        if (withContext(Dispatchers.IO) { file.isFile }) return
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val part = File(file.parentFile, "${file.name}.part")
            try {
                api.readOriginal(wallpaper.previewUrl) { input -> part.outputStream().use { input.copyTo(it) } }
                if (!part.renameTo(file)) throw IOException("The preview could not be kept")
            } finally {
                part.delete()
            }
        }
    }

    private fun previewFile(wallpaper: ConnectionsWallpaper): File {
        val extension = wallpaper.previewUrl.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
            .lowercase().takeIf { it.length in 3..4 && it.all(Char::isLetterOrDigit) } ?: "webp"
        return File(File(File(context.filesDir, APP_DIR), PREVIEW_DIR), "${wallpaper.slug}.$extension")
    }

    private fun contentUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.connections", file)

    /** A copy this app saved before (same slug), so a second use costs no second download. */
    private suspend fun savedOriginal(slug: String): Pair<Uri, String>? = withContext(Dispatchers.IO) {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
            // A slug's own _ or % must match itself, not any character.
            arrayOf(RELATIVE_PATH, slug.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + ".%"),
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)) to cursor.getString(1)
        }
    }

    private suspend fun downloadOriginal(wallpaper: ConnectionsWallpaper, token: String): Pair<Uri, String> {
        val download = api.download(wallpaper.id, token)
        val name = "${wallpaper.slug}.${extensionOf(download)}"
        return withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val pending = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, download.contentType)
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pending)
                ?: throw IOException("The phone refused to create the image file")
            try {
                api.readOriginal(download.url) { input ->
                    val output = resolver.openOutputStream(uri) ?: throw IOException("The image file could not be opened")
                    output.use { input.copyTo(it) }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                uri to name
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
    }

    private fun extensionOf(download: ConnectionsDownload): String = when (download.contentType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/avif" -> "avif"
        else -> download.fileName.substringAfterLast('.', "jpg")
    }

    private companion object {
        val RELATIVE_PATH = "${Environment.DIRECTORY_PICTURES}/Connections Wallpaper/"
        /** Under files/, where res/xml/connections_files.xml lets the app's FileProvider read it back. */
        const val APP_DIR = "connections"
        /** Under APP_DIR: the previews a collection saved offline shows from. */
        const val PREVIEW_DIR = "previews"
    }
}
