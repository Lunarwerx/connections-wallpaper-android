package com.anthonyla.paperize.connections

import com.anthonyla.paperize.domain.model.Album
import com.anthonyla.paperize.domain.repository.AlbumRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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

    private fun offline() = OfflineCollections(api, ConnectionsLibrary(account, originals), originals, albums, collections.store)

    @Test fun `signed out, Add to library asks for the account and fetches nothing`() = runTest {
        coEvery { account.accessToken(any()) } returns null

        val result = offline().add(art)

        assertEquals(OfflineResult.SignInNeeded(art.id), result)
        coVerify(exactly = 0) { api.wallpapers(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { originals.keepOffline(any()) }
        assertEquals(emptyList<WallpaperCollection>(), collections.store.all.first())
    }

    private fun newAlbumArt() {
        coEvery { account.accessToken(any()) } returns "token"
        every { albums.getAlbumById(any()) } returns flowOf(null)
        coEvery { albums.getAlbumByName("Art") } returns null
        coEvery { albums.createAlbum("Art", null) } returns Album(id = "album-1", name = "Art", coverUri = null)
    }

    @Test fun `signed in, a gallery collection joins one album page by page, in the longest pages, with no picture downloaded`() = runTest {
        newAlbumArt()
        val longest = ConnectionsApi.LONGEST_PAGE
        coEvery { api.wallpapers("", "art", null, null, longest) } returns
            ConnectionsWallpaperPage(listOf(galleryWallpaper("a"), galleryWallpaper("b")), nextCursor = "page-2")
        coEvery { api.wallpapers("", "art", null, "page-2", longest) } returns ConnectionsWallpaperPage(listOf(galleryWallpaper("c")))
        coEvery { originals.addAllToAlbum(listOf(galleryWallpaper("a"), galleryWallpaper("b")), "album-1") } returns 2
        coEvery { originals.addAllToAlbum(listOf(galleryWallpaper("c")), "album-1") } returns 1

        val result = offline().add(art)

        assertEquals(OfflineResult.Added(art.id, "Art", 3, "album-1"), result)
        coVerify(exactly = 0) { originals.keepOffline(any()) }
        coVerify(exactly = 1) { albums.createAlbum(any(), any()) }
        val saved = collections.store.all.first().single()
        assertEquals(listOf("a", "b", "c"), saved.wallpapers.map { it.id })
        assertEquals(listOf("a", "b", "c"), saved.savedIds)
        assertEquals("album-1", saved.albumId)
        assertNotNull(saved.savedAt)
    }

    @Test fun `Add to library puts the pages already on screen in the Library at once, before the gallery lists the rest`() = runTest {
        newAlbumArt()
        val rest = CompletableDeferred<ConnectionsWallpaperPage>()
        coEvery { api.wallpapers("", "art", null, "page-2", any()) } coAnswers { rest.await() }
        coEvery { originals.addAllToAlbum(any(), "album-1") } answers { firstArg<List<ConnectionsWallpaper>>().size }
        val inLibrary = CompletableDeferred<OfflineResult.Added>()

        val adding = async {
            offline().add(art, KnownPages(listOf(galleryWallpaper("a"), galleryWallpaper("b")), cursor = "page-2")) { inLibrary.complete(it) }
        }

        // The gallery has not answered for the rest, and the collection is already an album in the Library.
        assertEquals(OfflineResult.Added(art.id, "Art", 2, "album-1"), inLibrary.await())
        val early = collections.store.all.first().single()
        assertEquals(listOf("a", "b"), early.savedIds)
        assertNotNull(early.savedAt)
        coVerify(exactly = 0) { api.wallpapers(any(), any(), any(), null, any()) }

        rest.complete(ConnectionsWallpaperPage(listOf(galleryWallpaper("c"))))
        assertEquals(OfflineResult.Added(art.id, "Art", 3, "album-1"), adding.await())
        assertEquals(listOf("a", "b", "c"), collections.store.all.first().single().savedIds)
    }

    @Test fun `Download goes on from the originals already on the phone and keeps going past one that fails`() = runTest {
        val pictures = listOf(galleryWallpaper("a"), galleryWallpaper("b"), galleryWallpaper("c"))
        collections.store.recordSave(art, pictures, "album-1", pictures.map { it.id }, complete = true, now = 1)
        coEvery { originals.downloaded(pictures) } returns setOf("a")
        coEvery { originals.keepOffline(galleryWallpaper("b")) } throws java.io.IOException("offline")

        val result = offline().download(art)

        assertEquals(OfflineResult.Partly(art.id, "Art", 2, 3), result)
        coVerify(exactly = 0) { originals.keepOffline(galleryWallpaper("a")) }
        coVerify(exactly = 1) { originals.keepOffline(galleryWallpaper("c")) }
    }
}
