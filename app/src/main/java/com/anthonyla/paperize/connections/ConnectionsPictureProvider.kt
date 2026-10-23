package com.anthonyla.paperize.connections

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Opens Library pictures (content://<app>.pictures/...) like any picture on the phone, for the wallpaper, the live
 * wallpaper and the app's own screens. Opening one the phone does not hold yet fetches it there and then (ConnectionsPictures);
 * with no internet it does not open, and rotation skips it this time. A query never needs the network and always finds
 * the picture, so an album refresh never takes one for a deleted file.
 */
class ConnectionsPictureProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun pictures(): ConnectionsPictures
    }

    private val pictures by lazy {
        EntryPointAccessors.fromApplication(requireNotNull(context).applicationContext, Dependencies::class.java).pictures()
    }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val picture = pictures.pictureOf(uri) ?: return null
        val local = pictures.local(picture)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> local?.name ?: picture.slug
                    OpenableColumns.SIZE -> local?.length()
                    else -> null
                }
            })
        }
    }

    override fun getType(uri: Uri): String? {
        val picture = pictures.pictureOf(uri) ?: return null
        val extension = pictures.local(picture)?.extension ?: return WEBP
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: WEBP
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Library pictures open read-only")
        val picture = pictures.pictureOf(uri) ?: throw FileNotFoundException("Not a Library picture: $uri")
        val file = try {
            runBlocking { pictures.file(picture) }
        } catch (e: IOException) {
            throw FileNotFoundException("${picture.slug} could not be fetched: ${e.message}").apply { initCause(e) }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Library pictures are read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Library pictures are read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Library pictures are read-only")

    private companion object {
        const val WEBP = "image/webp"
    }
}
