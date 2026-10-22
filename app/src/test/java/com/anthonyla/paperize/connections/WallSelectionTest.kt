package com.anthonyla.paperize.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallSelectionTest {
    private val wall = listOf(galleryWallpaper("a"), galleryWallpaper("b"), galleryWallpaper("c"))

    @Test fun `a long press selects that picture and starts selection mode`() {
        val selection = WallSelection()
        assertFalse(selection.active)

        selection.enter("b")

        assertTrue(selection.active)
        assertEquals(listOf(galleryWallpaper("b")), selection.picked(wall))
    }

    @Test fun `taps toggle pictures, and untoggling the last one leaves selection mode`() {
        val selection = WallSelection()
        selection.enter("a")

        selection.toggle("c")
        assertEquals(listOf(galleryWallpaper("a"), galleryWallpaper("c")), selection.picked(wall))

        selection.toggle("a")
        assertEquals(listOf(galleryWallpaper("c")), selection.picked(wall))

        selection.toggle("c")
        assertFalse(selection.active)
    }

    @Test fun `select all picks every picture on the wall`() {
        val selection = WallSelection()
        selection.enter("b")

        selection.selectAll(wall.map { it.id })

        assertEquals(wall, selection.picked(wall))
    }

    @Test fun `back clears the selection and leaves selection mode`() {
        val selection = WallSelection()
        selection.enter("a")
        selection.selectAll(wall.map { it.id })

        selection.clear()

        assertFalse(selection.active)
        assertEquals(emptyList<ConnectionsWallpaper>(), selection.picked(wall))
    }
}
