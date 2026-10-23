package com.anthonyla.paperize.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.data.database.PaperizeDatabase
import com.anthonyla.paperize.data.database.entities.AlbumEntity
import com.anthonyla.paperize.data.mapper.toEntity
import com.anthonyla.paperize.domain.model.Folder
import com.anthonyla.paperize.domain.model.Wallpaper
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlbumRepositoryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, PaperizeDatabase::class.java).build()
    private val wallpapers = WallpaperRepositoryImpl(db)
    private val albums = AlbumRepositoryImpl(com.anthonyla.paperize.data.source.AndroidDocumentSource(context), db)

    @After fun close() = db.close()

    @Test fun cancellingAChunkedFolderImportRollsBackAllRows() = runBlocking {
        db.albumDao().insertAlbum(AlbumEntity("album", "Album", null))
        val folder = Folder.empty("folder", "album").copy(
            wallpapers = (0..599).map { Wallpaper.empty("image-$it", "album").copy(folderId = "folder", uri = "content://image-$it") }
        )
        try {
            albums.addFolderToAlbum("album", folder) { saved, _ ->
                if (saved >= 500) throw CancellationException("User cancelled")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(0, db.wallpaperDao().getWallpaperCountByAlbum("album"))
        assertNull(db.folderDao().getFolderWithWallpapers("folder").first())
        assertNull(db.albumDao().getAlbumById("album")?.coverUri)
    }

    @Test fun importingImagesUpdatesCoverAndRotationQueueTogether() = runBlocking {
        db.albumDao().insertAlbum(AlbumEntity("album", "Album", null))
        val first = Wallpaper.empty("first", "album").copy(uri = "content://first")
        db.wallpaperDao().insertWallpaper(first.toEntity())
        db.wallpaperQueueDao().rebuildQueue(ScreenType.HOME, listOf("album"), listOf("album" to "first"))
        db.folderDao().insertFolder(Folder.empty("folder", "album").toEntity())
        val second = first.copy(id = "second", uri = "content://second", folderId = "folder")

        assertEquals(1, albums.addWallpapersToAlbum("album", listOf(second)))
        assertTrue(db.wallpaperQueueDao().getQueueItems(listOf("album"), ScreenType.HOME).isEmpty())
        assertEquals(2, db.wallpaperDao().getWallpaperCountByAlbum("album"))
        assertEquals("content://second", db.folderDao().getFolderById("folder")?.coverUri)
    }

    @Test fun shuffledHomeAndLockOnDifferentAlbumsKeepEachOthersQueues() = runBlocking {
        db.albumDao().insertAlbum(AlbumEntity("one", "One", null))
        db.albumDao().insertAlbum(AlbumEntity("two", "Two", null))
        db.wallpaperDao().insertWallpapers(
            listOf("one" to "a1", "one" to "a2", "two" to "b1", "two" to "b2")
                .map { (album, id) -> Wallpaper.empty(id, album).copy(uri = "content://$id").toEntity() }
        )

        wallpapers.ensureWallpaperQueue(listOf("two"), ScreenType.LOCK, shuffle = true)
        wallpapers.ensureWallpaperQueue(listOf("one"), ScreenType.HOME, shuffle = true)

        val lock = db.wallpaperQueueDao().getQueueItems(listOf("two"), ScreenType.LOCK).map { it.wallpaperId }.toSet()
        val home = db.wallpaperQueueDao().getQueueItems(listOf("one"), ScreenType.HOME).map { it.wallpaperId }.toSet()
        assertEquals(setOf("b1", "b2"), lock)
        assertEquals(setOf("a1", "a2"), home)
    }

    @Test fun removingCoverUsesOnlyWallpapersFromThatAlbum() = runBlocking {
        db.albumDao().insertAlbum(AlbumEntity("one", "One", "content://shared"))
        db.albumDao().insertAlbum(AlbumEntity("two", "Two", "content://shared"))
        val cover = Wallpaper.empty("cover", "one").copy(uri = "content://shared")
        val replacement = cover.copy(id = "replacement", uri = "content://replacement", displayOrder = 1)
        db.wallpaperDao().insertWallpapers(listOf(cover, replacement, cover.copy(id = "other", albumId = "two")).map { it.toEntity() })

        albums.removeWallpapersFromAlbum("one", listOf("cover", "other"))
        assertEquals("content://replacement", db.albumDao().getAlbumById("one")?.coverUri)
        assertEquals("content://shared", db.albumDao().getAlbumById("two")?.coverUri)
        assertNotNull(db.wallpaperDao().getWallpaperById("other"))
    }

    @Test fun coverRefreshUsesDisplayOrderAndClearsEmptyFolders() = runBlocking {
        db.albumDao().insertAlbum(AlbumEntity("album", "Album", null))
        db.folderDao().insertFolder(Folder.empty("folder", "album").toEntity())
        val later = Wallpaper.empty("later", "album").copy(folderId = "folder", uri = "content://later", displayOrder = 9)
        val first = later.copy(id = "first", uri = "content://first", displayOrder = 1)
        val direct = later.copy(id = "direct", folderId = null, uri = "content://direct", displayOrder = 99)
        db.wallpaperDao().insertWallpapers(listOf(later, first, direct).map { it.toEntity() })

        db.folderDao().refreshFolderCovers("album")
        assertEquals("content://first", db.folderDao().getFolderById("folder")?.coverUri)
        assertEquals("content://direct", db.wallpaperDao().getAlbumCoverUri("album"))

        db.wallpaperDao().deleteWallpapersByFolder("folder")
        db.folderDao().refreshFolderCovers("album")
        assertNull(db.folderDao().getFolderById("folder")?.coverUri)
    }

    @Test fun concurrentImportsDeduplicateInsideTheTransaction() = runBlocking {
        db.albumDao().insertAlbum(AlbumEntity("album", "Album", null))
        val image = Wallpaper.empty("one", "album").copy(uri = "content://same")
        val first = async(Dispatchers.IO) { albums.addWallpapersToAlbum("album", listOf(image)) }
        val second = async(Dispatchers.IO) { albums.addWallpapersToAlbum("album", listOf(image.copy(id = "two"))) }
        assertEquals(1, first.await() + second.await())
        assertEquals(1, db.wallpaperDao().getWallpaperCountByAlbum("album"))

        db.folderDao().insertFolder(Folder.empty("folder", "album").toEntity())
        assertEquals(1, albums.addWallpapersToAlbum("album", listOf(image.copy(id = "folder-image", folderId = "folder"))))
        db.folderDao().deleteFolderById("folder")
        assertEquals(0, albums.addWallpapersToAlbum("album", listOf(image.copy(id = "stale", folderId = "folder"))))
    }

    @Test fun twoAlbumsOnOneScreenFillOneQueueWithEachPictureOwnAlbum() = runBlocking {
        db.albumDao().insertAlbum(AlbumEntity("album", "Album", null))
        db.albumDao().insertAlbum(AlbumEntity("two", "Two", null))
        albums.addWallpapersToAlbum("album", listOf(Wallpaper.empty("first", "album").copy(uri = "content://first")))
        albums.addWallpapersToAlbum("two", listOf(Wallpaper.empty("second", "two").copy(uri = "content://second")))

        wallpapers.ensureWallpaperQueue(listOf("album", "two"), ScreenType.HOME, false)

        val queue = db.wallpaperQueueDao().getQueueItems(listOf("album", "two"), ScreenType.HOME)
        assertEquals(listOf("first", "second"), queue.map { it.wallpaperId })
        assertEquals(listOf("album", "two"), queue.map { it.albumId })
    }

    @Test fun reorderPersistsNestedImagesWithoutOverwritingMetadata() = runBlocking {
        db.albumDao().insertAlbum(AlbumEntity("album", "Album", null))
        val first = Wallpaper.empty("first", "album").copy(uri = "content://first", folderId = "folder")
        val second = first.copy(id = "second", uri = "content://second")
        val folder = Folder.empty("folder", "album").copy(wallpapers = listOf(first, second))
        albums.addFolderToAlbum("album", folder)
        wallpapers.ensureWallpaperQueue(listOf("album"), ScreenType.HOME, false)
        db.wallpaperDao().updateWallpaper(first.copy(fileName = "new-name.jpg").toEntity())

        albums.reorderAlbum("album", listOf(folder.copy(wallpapers = listOf(second, first))), emptyList())
        assertEquals(listOf("second", "first"), db.wallpaperDao().getOrderedWallpaperIdsByAlbum("album"))
        assertEquals("new-name.jpg", db.wallpaperDao().getWallpaperById("first")?.fileName)
        assertEquals("content://second", db.folderDao().getFolderById("folder")?.coverUri)
        assertTrue(db.wallpaperQueueDao().getQueueItems(listOf("album"), ScreenType.HOME).isEmpty())
    }

    @Test fun failedReorderRollsBackEarlierRowsAndPreservesQueue() = runBlocking {
        db.albumDao().insertAlbum(AlbumEntity("album", "Album", null))
        val first = Wallpaper.empty("first", "album").copy(uri = "content://first")
        val second = first.copy(id = "second", uri = "content://second")
        albums.addWallpapersToAlbum("album", listOf(first, second))
        wallpapers.ensureWallpaperQueue(listOf("album"), ScreenType.HOME, false)
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_order BEFORE UPDATE OF displayOrder ON wallpapers
            WHEN NEW.id = 'first' BEGIN SELECT RAISE(ABORT, 'test write failure'); END
        """)
        assertTrue(runCatching { albums.reorderAlbum("album", emptyList(), listOf(second, first)) }.isFailure)
        assertEquals(listOf("first", "second"), db.wallpaperDao().getOrderedWallpaperIdsByAlbum("album"))
        assertEquals(listOf("first", "second"), db.wallpaperQueueDao().getQueueItems(listOf("album"), ScreenType.HOME).map { it.wallpaperId })
    }

    @Test fun concurrentQueueCreationKeepsBothScreensInSyncAndDoesNotRefillConsumedItems() = runBlocking {
        db.albumDao().insertAlbum(AlbumEntity("album", "Album", null))
        albums.addWallpapersToAlbum("album", (1..8).map {
            Wallpaper.empty("image-$it", "album").copy(uri = "content://image-$it")
        })
        val home = async(Dispatchers.IO) { wallpapers.ensureWallpaperQueue(listOf("album"), ScreenType.HOME, true) }
        val lock = async(Dispatchers.IO) { wallpapers.ensureWallpaperQueue(listOf("album"), ScreenType.LOCK, true) }
        home.await(); lock.await()
        val expected = db.wallpaperQueueDao().getQueueItems(listOf("album"), ScreenType.HOME).map { it.wallpaperId }
        assertEquals(8, expected.toSet().size)
        assertEquals(expected, db.wallpaperQueueDao().getQueueItems(listOf("album"), ScreenType.LOCK).map { it.wallpaperId })
        wallpapers.getAndDequeueWallpaper(listOf("album"), ScreenType.HOME)
        wallpapers.ensureWallpaperQueue(listOf("album"), ScreenType.HOME, true)
        assertEquals(expected.drop(1), db.wallpaperQueueDao().getQueueItems(listOf("album"), ScreenType.HOME).map { it.wallpaperId })
    }
}
