package com.anthonyla.paperize.connections

import androidx.lifecycle.ViewModelStore
import com.anthonyla.paperize.domain.repository.AlbumRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModelTest {
    private val api = mockk<ConnectionsApi>()
    private val preferences = mockk<ExplorePreferences>(relaxUnitFun = true)
    private val albums = mockk<AlbumRepository>()
    private val collections = mockk<ConnectionsCollections> { every { all } returns flowOf(emptyList()) }
    private val store = ViewModelStore()
    private val phone = DeviceFit.of(411f, 914f, foldable = false, computer = false)

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { albums.getAlbumSummaries() } returns flowOf(emptyList())
        coEvery { api.tags() } returns emptyList()
        coEvery { api.wallpapers(any(), any(), any(), any()) } returns ConnectionsWallpaperPage(emptyList())
        coEvery { preferences.shape() } returns ExploreShape.FIT
    }

    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun viewModel(): ConnectionsViewModel =
        ConnectionsViewModel(api, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), preferences, albums, collections)
            .also { store.put("explore", it) }

    @Test fun `Explore waits for the screen, then asks for its shape with the closest picture first`() = runTest {
        coEvery { api.wallpapers(any(), any(), any(), any()) } returns ConnectionsWallpaperPage(
            listOf(wallpaper("square", 1000, 1000), wallpaper("tall", 1080, 2400), wallpaper("frame", 1600, 2000))
        )
        val viewModel = viewModel()
        advanceUntilIdle()
        coVerify(exactly = 0) { api.wallpapers(any(), any(), any(), any()) }

        viewModel.setDevice(phone)
        advanceUntilIdle()

        coVerify(exactly = 1) { api.wallpapers("", null, "portrait", null) }
        assertEquals(listOf("tall", "frame", "square"), viewModel.state.value.wallpapers.map { it.id })
    }

    @Test fun `choosing All asks for every shape and is remembered`() = runTest {
        val viewModel = viewModel()
        viewModel.setDevice(phone)
        advanceUntilIdle()

        viewModel.selectShape(ExploreShape.ALL)
        advanceUntilIdle()

        coVerify(exactly = 1) { api.wallpapers("", null, null, null) }
        coVerify(exactly = 1) { preferences.setShape(ExploreShape.ALL) }
    }

    @Test fun `a remembered shape comes back on the next visit`() = runTest {
        coEvery { preferences.shape() } returns ExploreShape.LANDSCAPE
        val viewModel = viewModel()
        viewModel.setDevice(phone)
        advanceUntilIdle()

        assertEquals(ExploreShape.LANDSCAPE, viewModel.state.value.shape)
        coVerify(exactly = 1) { api.wallpapers("", null, "landscape", null) }
        coVerify(exactly = 0) { api.wallpapers("", null, "portrait", null) }
    }

    private fun wallpaper(id: String, width: Int, height: Int) = ConnectionsWallpaper(
        id = id,
        slug = id,
        title = id,
        width = width,
        height = height,
        previewUrl = "https://example.invalid/$id.webp",
        previewWidth = width / 4,
        previewHeight = height / 4,
        license = "CC0"
    )
}
