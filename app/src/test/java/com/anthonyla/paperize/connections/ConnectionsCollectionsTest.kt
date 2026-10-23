package com.anthonyla.paperize.connections

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConnectionsCollectionsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `a member's own collections, with what they hold and their names, are still there after the app restarts`() = runTest {
        val file = folder.root.resolve("collections.preferences_pb")
        val first = CollectionsTestStore(file)
        val beach = first.store.create("Beach", listOf(galleryWallpaper("a")))
        val night = first.store.create("Night")
        assertTrue(first.store.toggle(beach.id, galleryWallpaper("b")))
        // A second tap takes a picture back out.
        first.store.toggle(beach.id, galleryWallpaper("c"))
        first.store.toggle(beach.id, galleryWallpaper("c"))
        first.store.rename(beach.id, "Beaches")
        first.store.delete(night.id)
        first.close()

        val reopened = CollectionsTestStore(file)
        val saved = reopened.store.all.first()
        reopened.close()

        assertEquals(listOf("Beaches"), saved.map { it.name })
        assertEquals(listOf("b", "a"), saved.single().wallpapers.map { it.id })
        assertTrue(saved.single().own)
    }

    @Test fun `an add to the Library that finishes after its own collection was deleted does not bring the collection back`() = runTest {
        val phone = CollectionsTestStore(folder.root.resolve("collections.preferences_pb"))
        val beach = phone.store.create("Beach", listOf(galleryWallpaper("a")))
        phone.store.delete(beach.id)

        phone.store.recordSave(beach, beach.wallpapers, albumId = "album-1", savedIds = listOf("a"), complete = true, now = 1L)
        val saved = phone.store.all.first()
        phone.close()

        assertEquals(emptyList<WallpaperCollection>(), saved)
    }
}
