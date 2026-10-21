package com.anthonyla.paperize.connections

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * A collections store over a real preferences file, as the app's own is; [close] lets a fresh store open the same file.
 * Okio storage because the JVM file storage renames its scratch file over the old one, which Windows refuses on the second write.
 */
internal class CollectionsTestStore(file: File) {
    private val job = Job()
    val store = ConnectionsCollections(
        PreferenceDataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { file.toOkioPath() },
            scope = CoroutineScope(Dispatchers.IO + job)
        )
    )

    suspend fun close() = job.cancelAndJoin()
}

internal fun galleryWallpaper(id: String) = ConnectionsWallpaper(
    id = id,
    slug = "slug-$id",
    title = "Title $id",
    width = 1080,
    height = 2400,
    previewUrl = "https://example.invalid/$id.webp",
    previewWidth = 270,
    previewHeight = 600,
    license = "cc0"
)
