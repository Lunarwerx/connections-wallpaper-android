package com.anthonyla.paperize.connections

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
            pages = flow { picked = true; emit(listOf(galleryWallpaper("a"))) },
            albumFor = { named = true; "album-1" }
        )

        assertEquals(LibraryAdd.SignInNeeded, result)
        assertEquals(false, picked)
        assertEquals(false, named)
        coVerify(exactly = 0) { originals.keepOffline(any()) }
        coVerify(exactly = 0) { originals.addAllToAlbum(any(), any()) }
    }

    @Test fun `signed in, pictures join one album named once, page by page, and nothing is downloaded`() = runTest {
        coEvery { account.accessToken(any()) } returns "token"
        val (a, b, c) = listOf(galleryWallpaper("a"), galleryWallpaper("b"), galleryWallpaper("c"))
        coEvery { originals.addAllToAlbum(listOf(a, b), "album-1") } returns 2
        coEvery { originals.addAllToAlbum(listOf(c), "album-1") } returns 1
        var namings = 0
        val heard = mutableListOf<List<String>>()

        val result = library.addToAlbum(
            pages = flowOf(listOf(a, b), listOf(b, c)),
            albumFor = { namings++; "album-1" },
            onPage = { _, wanted -> heard += wanted.map { it.id } }
        )

        assertEquals(LibraryAdd.Added("album-1", listOf(a, b, c), 3), result)
        assertEquals(listOf(listOf("a", "b"), listOf("a", "b", "c")), heard)
        coVerify(exactly = 0) { originals.keepOffline(any()) }
        assertEquals(1, namings)
    }
}
