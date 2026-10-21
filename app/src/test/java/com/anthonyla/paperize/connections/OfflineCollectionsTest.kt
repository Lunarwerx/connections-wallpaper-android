package com.anthonyla.paperize.connections

import com.anthonyla.paperize.domain.model.Album
import com.anthonyla.paperize.domain.repository.AlbumRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineCollectionsTest {
    @get:Rule val folder = TemporaryFolder()

    private val api = mockk<ConnectionsApi>()
    private val account = mockk<ConnectionsAccount>()
    private val originals = mockk<ConnectionsOriginals>(relaxUnitFun = true)
    private val albums = mockk<AlbumRepository>()
    private lateinit var collections: CollectionsTestStore
    private val art = WallpaperCollection(id = tagCollectionId("art"), name = "Art", tag = "art")

    @Before fun setUp() {
        collections = CollectionsTestStore(folder.root.resolve("collections.preferences_pb"))
    }

    @After fun tearDown() = runTest { collections.close() }

    private fun offline() = OfflineCollections(api, account, originals, albums, collections.store)

    @Test fun `signed out, Save offline asks for the account and fetches nothing`() = runTest {
        coEvery { account.accessToken(any()) } returns null

        val result = offline().save(art)

        assertEquals(OfflineResult.SignInNeeded(art.id), result)
        coVerify(exactly = 0) { api.wallpapers(any(), any(), any(), any()) }
        coVerify(exactly = 0) { originals.keepOffline(any()) }
        assertEquals(emptyList<WallpaperCollection>(), collections.store.all.first())
    }

    @Test fun `signed in, a gallery collection is saved page by page into one album and opens offline from what it saved`() = runTest {
        coEvery { account.accessToken(any()) } returns "token"
        coEvery { api.wallpapers("", "art", null, null) } returns
            ConnectionsWallpaperPage(listOf(galleryWallpaper("a"), galleryWallpaper("b")), nextCursor = "page-2")
        coEvery { api.wallpapers("", "art", null, "page-2") } returns ConnectionsWallpaperPage(listOf(galleryWallpaper("c")))
        coEvery { originals.addAllToAlbum(any(), any()) } returns 3
        every { albums.getAlbumById(any()) } returns flowOf(null)
        coEvery { albums.getAlbumByName("Art") } returns null
        coEvery { albums.createAlbum("Art", null) } returns Album(id = "album-1", name = "Art", coverUri = null)

        val result = offline().save(art)

        assertEquals(OfflineResult.Saved(art.id, "Art", 3, "album-1"), result)
        coVerify(exactly = 1) { originals.keepOffline(galleryWallpaper("a")) }
        coVerify(exactly = 1) { originals.keepOffline(galleryWallpaper("b")) }
        coVerify(exactly = 1) { originals.keepOffline(galleryWallpaper("c")) }
        coVerify(exactly = 1) { originals.addAllToAlbum(listOf(galleryWallpaper("a"), galleryWallpaper("b"), galleryWallpaper("c")), "album-1") }
        val saved = collections.store.all.first().single()
        assertEquals(listOf("a", "b", "c"), saved.wallpapers.map { it.id })
        assertEquals(listOf("a", "b", "c"), saved.savedIds)
        assertEquals("album-1", saved.albumId)
        assertNotNull(saved.savedAt)
    }
}
