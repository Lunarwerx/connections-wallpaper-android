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
}
