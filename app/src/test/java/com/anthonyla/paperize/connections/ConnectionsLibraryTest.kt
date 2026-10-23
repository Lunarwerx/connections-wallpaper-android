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

    @Test fun `signed in, N pictures join one album named once, and nothing is downloaded`() = runTest {
        coEvery { account.accessToken(any()) } returns "token"
        coEvery { originals.addAllToAlbum(any(), any()) } returns 3
        val wallpapers = listOf(galleryWallpaper("a"), galleryWallpaper("b"), galleryWallpaper("c"))
        var namings = 0

        val result = library.addToAlbum(pick = { wallpapers }, albumFor = { namings++; "album-1" })

        assertEquals(LibraryAdd.Added("album-1", wallpapers, 3), result)
        coVerify(exactly = 1) { originals.addAllToAlbum(wallpapers, "album-1") }
        coVerify(exactly = 0) { originals.keepOffline(any()) }
        assertEquals(1, namings)
    }
}
