package com.anthonyla.paperize.connections

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.util.generateId
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.domain.repository.AlbumRepository
import com.anthonyla.paperize.service.worker.ApplyPictureWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** Where a Connections wallpaper goes on the phone: the home or lock screen right now. */
enum class WallpaperTarget(val screen: ScreenType) {
    BOTH(ScreenType.BOTH),
    HOME(ScreenType.HOME),
    LOCK(ScreenType.LOCK)
}

/**
 * Full-quality Connections originals on the phone. Setting one as the wallpaper needs no account; adding one to
 * an album goes through [ConnectionsLibrary], which asks for the account first and adds it by reference
 * ([ConnectionsPictures]), fetching nothing. Download keeps the original from the gallery's public full door in the
 * app's own storage (files/connections), which no other app sees. Saving one to the phone is for a
 * signed-in member (the download door, counted): it goes into Pictures/Connections Wallpaper and
 * shows up in the gallery app, and the wallpaper or an album then reuses that copy. The app owns the
 * MediaStore row it inserts, so it reads it back without a storage permission.
 */
class ConnectionsOriginals @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: ConnectionsApi,
    private val albumRepository: AlbumRepository,
    private val pictures: ConnectionsPictures
) {
    /** Adds every one of [wallpapers] to the album in one go, by reference: nothing is fetched. Returns how many it did not have yet. */
    suspend fun addAllToAlbum(wallpapers: List<ConnectionsWallpaper>, albumId: String): Int {
        if (wallpapers.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val entries = wallpapers.map { wallpaper ->
            Wallpaper(
                id = generateId(),
                albumId = albumId,
                uri = pictures.uriFor(wallpaper).toString(),
                fileName = wallpaper.title.ifBlank { wallpaper.slug },
                dateModified = now
            )
        }
        return albumRepository.addWallpapersToAlbum(albumId, entries)
    }

    /** Download: keeps the original and its preview on the phone, so the picture shows and rotates with no internet. */
    suspend fun keepOffline(wallpaper: ConnectionsWallpaper) {
        appCopy(wallpaper)
        keepPreview(wallpaper)
        pictures.dropScreenCopy(wallpaper.slug)
    }

    /** The ids among [wallpapers] whose original Download kept on the phone. */
    suspend fun downloaded(wallpapers: List<ConnectionsWallpaper>): Set<String> = withContext(Dispatchers.IO) {
        val slugs = connectionsDir(context).listFiles { file -> file.isFile && !file.name.endsWith(".part") }
            ?.mapTo(HashSet()) { it.nameWithoutExtension }.orEmpty()
        wallpapers.filter { it.slug in slugs }.mapTo(HashSet()) { it.id }
    }

    /** What a saved picture shows from on the phone: its preview, else its original; pictures with neither are left out. */
    suspend fun localImages(wallpapers: List<ConnectionsWallpaper>): Map<String, File> = withContext(Dispatchers.IO) {
        val originals = connectionsDir(context).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.associateBy { it.nameWithoutExtension }.orEmpty()
        wallpapers.mapNotNull { wallpaper ->
            (previewFile(wallpaper).takeIf { it.isFile } ?: originals[wallpaper.slug])?.let { wallpaper.id to it }
        }.toMap()
    }

    /** The saved preview of [wallpaper], when there is one. */
    fun savedPreview(wallpaper: ConnectionsWallpaper): File? = previewFile(wallpaper).takeIf { it.isFile }

    /**
     * Sets [wallpaper] through the app's own wallpaper work ([ApplyPictureWorker]), never straight on the phone: it is drawn with the screen's
     * scaling and effects, our live wallpaper stays on and shows it, and rotation goes on from it (owner report, 2026-10-22:
     * a picture set from a collection broke the rotator and Change wallpaper now).
     */
    suspend fun setAsWallpaper(wallpaper: ConnectionsWallpaper, target: WallpaperTarget) {
        val (uri, _) = kept(wallpaper)
        ApplyPictureWorker.set(context, uri.toString(), target.screen)
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
        val dir = connectionsDir(context)
        withContext(Dispatchers.IO) { downloadedOriginal(dir, wallpaper.slug) }?.let { return contentUri(it) to it.name }
        val full = api.full(wallpaper.id)
        val file = File(dir, "${wallpaper.slug}.${full.extension}")
        api.readInto(full.url, file)
        return contentUri(file) to file.name
    }

    private suspend fun keepPreview(wallpaper: ConnectionsWallpaper) {
        val file = previewFile(wallpaper)
        if (withContext(Dispatchers.IO) { file.isFile }) return
        api.readInto(wallpaper.previewUrl, file)
    }

    private fun previewFile(wallpaper: ConnectionsWallpaper): File {
        val extension = wallpaper.previewUrl.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
            .lowercase().takeIf { it.length in 3..4 && it.all(Char::isLetterOrDigit) } ?: "webp"
        return File(previewsDir(context), "${wallpaper.slug}.$extension")
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
        val name = "${wallpaper.slug}.${download.extension}"
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

    private companion object {
        val RELATIVE_PATH = "${Environment.DIRECTORY_PICTURES}/Connections Wallpaper/"
    }
}
