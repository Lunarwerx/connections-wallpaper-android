package com.anthonyla.paperize.connections

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.anthonyla.paperize.core.util.generateId
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.domain.repository.AlbumRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/**
 * Saves a Connections wallpaper's full-quality original into Pictures/Connections Wallpaper and adds it
 * to an album. The app owns the MediaStore row it inserts, so it reads it back without a storage
 * permission or a persisted grant, and the photo also shows up in the gallery app.
 */
class SaveConnectionsWallpaperUseCase @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: ConnectionsApi,
    private val albumRepository: AlbumRepository
) {
    /** Returns false when the album already had this wallpaper. */
    suspend operator fun invoke(wallpaper: ConnectionsWallpaper, albumId: String, key: String): Boolean {
        val (uri, name) = savedOriginal(wallpaper.slug) ?: downloadOriginal(wallpaper, key)
        val entry = Wallpaper(
            id = generateId(),
            albumId = albumId,
            uri = uri.toString(),
            fileName = name,
            dateModified = System.currentTimeMillis()
        )
        return albumRepository.addWallpapersToAlbum(albumId, listOf(entry)) > 0
    }

    /** A copy this app saved before (same slug), so a second album costs no second download. */
    private suspend fun savedOriginal(slug: String): Pair<Uri, String>? = withContext(Dispatchers.IO) {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf(RELATIVE_PATH, "$slug.%"),
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)) to cursor.getString(1)
        }
    }

    private suspend fun downloadOriginal(wallpaper: ConnectionsWallpaper, key: String): Pair<Uri, String> {
        val download = api.download(wallpaper.id, key)
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
    }
}
