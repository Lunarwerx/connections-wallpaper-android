package com.anthonyla.paperize.connections

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionsLibraryTest {
    private val account = mockk<ConnectionsAccount>()
    private val originals = mockk<ConnectionsOriginals>(relaxUnitFun = true)
    private val library = ConnectionsLibrary(account, originals)

    @Test fun `signed out, adding to the library asks for the account and fetches nothing and makes no album`() = runTest {
        coEvery { account.accessToken(any()) } returns null
        var picked = false
        var named = false

        val result = library.addToAlbum(
            pick = { picked = true; listOf(galleryWallpaper("a")) },
            albumFor = { named = true; "album-1" }
        )

        assertEquals(LibraryAdd.SignInNeeded, result)
        assertEquals(false, picked)
        assertEquals(false, named)
        coVerify(exactly = 0) { originals.keepOffline(any()) }
        coVerify(exactly = 0) { originals.addAllToAlbum(any(), any()) }
    }

    @Test fun `signed in, N pictures are kept and added to one album named once`() = runTest {
        coEvery { account.accessToken(any()) } returns "token"
        coEvery { originals.addAllToAlbum(any(), any()) } returns 3
        val wallpapers = listOf(galleryWallpaper("a"), galleryWallpaper("b"), galleryWallpaper("c"))
        var namings = 0
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = library.addToAlbum(
            pick = { wallpapers },
            albumFor = { namings++; "album-1" },
            onProgress = { done, total -> progress += done to total }
        )

        assertEquals(LibraryAdd.Added("album-1", wallpapers, wallpapers, 3), result)
        wallpapers.forEach { coVerify(exactly = 1) { originals.keepOffline(it) } }
        coVerify(exactly = 1) { originals.addAllToAlbum(wallpapers, "album-1") }
        assertEquals(1, namings)
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), progress)
    }
}
