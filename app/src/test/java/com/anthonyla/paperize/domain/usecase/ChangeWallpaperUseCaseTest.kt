package com.anthonyla.paperize.domain.usecase

import android.graphics.Bitmap
import com.anthonyla.paperize.core.EmptyAlbumException
import com.anthonyla.paperize.core.NoValidWallpaperException
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.domain.model.PreparedWallpaper
import com.anthonyla.paperize.domain.model.WallpaperImage
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import com.anthonyla.paperize.domain.source.WallpaperRenderer
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import io.mockk.verify
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.domain.source.UpcomingPictures

class ChangeWallpaperUseCaseTest {

    private val repository = mockk<WallpaperRepository>(relaxed = true)
    private val renderer = mockk<WallpaperRenderer>()
    private val upcoming = mockk<UpcomingPictures>(relaxed = true)
    private val useCase = ChangeWallpaperUseCase(
        wallpaperRepository = repository,
        settingsRepository = mockk<SettingsRepository>(relaxed = true),
        renderer = renderer,
        upcoming = upcoming
    )

    @Test
    fun `complete fetches the next picture of that screen ahead of its change`() = runTest {
        val prepared = preparedWallpaper()
        val next = Wallpaper(id = "next", albumId = prepared.pictureAlbumId, uri = "content://next", fileName = "next", dateModified = 0)
        coEvery { repository.peekNextWallpaper(prepared.albumIds, ScreenType.HOME) } returns next

        useCase.complete(prepared, ScreenType.HOME)

        verify(exactly = 1) { upcoming.warm(next) }
    }

    @Test
    fun `complete records current then synchronizes exact queue item`() = runTest {
        val prepared = preparedWallpaper()
        coEvery {
            repository.ensureWallpaperQueue(prepared.albumIds, ScreenType.LOCK, false)
        } just Runs

        useCase.complete(prepared, ScreenType.LOCK)

        coVerifyOrder {
            repository.setCurrentWallpaper(
                prepared.pictureAlbumId,
                ScreenType.LOCK,
                prepared.wallpaperId
            )
            repository.ensureWallpaperQueue(prepared.albumIds, ScreenType.LOCK, false)
            repository.removeWallpaperFromQueue(
                prepared.albumIds,
                ScreenType.LOCK,
                prepared.wallpaperId
            )
        }
    }

    @Test
    fun `restore returns rejected item to its original queue`() = runTest {
        val prepared = preparedWallpaper()

        useCase.restore(prepared)

        coVerify(exactly = 1) {
            repository.restoreWallpaperToQueueFront(
                prepared.albumIds,
                prepared.screenType,
                prepared.wallpaperId
            )
        }
        coVerify(exactly = 0) {
            repository.setCurrentWallpaper(any(), any(), any())
        }
    }

    @Test
    fun `complete specific records selected item and respects shuffle`() = runTest {
        coEvery {
            repository.ensureWallpaperQueue(listOf("album"), ScreenType.HOME, true)
        } just Runs

        useCase.completeSpecific(listOf("album"), "album", ScreenType.HOME, "selected", shuffle = true)

        coVerifyOrder {
            repository.setCurrentWallpaper("album", ScreenType.HOME, "selected")
            repository.ensureWallpaperQueue(listOf("album"), ScreenType.HOME, true)
            repository.removeWallpaperFromQueue(listOf("album"), ScreenType.HOME, "selected")
        }
    }

    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun `cancelled rendering restores the dequeued item without retrying`() = runTest {
        val wallpaper = com.anthonyla.paperize.domain.model.Wallpaper.empty("image", "album")
        coEvery { repository.getAndDequeueWallpaper(listOf("album"), ScreenType.HOME) } returns wallpaper
        coEvery { renderer.render(wallpaper, ScreenType.HOME, any()) } throws kotlinx.coroutines.CancellationException()
        try {
            useCase(listOf("album"), ScreenType.HOME)
        } finally {
            coVerify(exactly = 1) { repository.getAndDequeueWallpaper(listOf("album"), ScreenType.HOME) }
            coVerify(exactly = 1) { repository.restoreWallpaperToQueueFront(listOf("album"), ScreenType.HOME, "image") }
        }
    }

    @Test
    fun `unreadable single image album is not reported as empty`() = runTest {
        val wallpaper = com.anthonyla.paperize.domain.model.Wallpaper.empty("image", "album")
        var dequeues = 0
        coEvery { repository.getAndDequeueWallpaper(listOf("album"), ScreenType.HOME) } answers {
            if (dequeues++ % 2 == 0) null else wallpaper
        }
        coEvery { repository.ensureWallpaperQueue(listOf("album"), ScreenType.HOME, any()) } just Runs
        coEvery { renderer.render(wallpaper, ScreenType.HOME, any()) } returns null

        assertTrue(runCatching { useCase(listOf("album"), ScreenType.HOME) }.exceptionOrNull() is NoValidWallpaperException)
        coVerify(exactly = Constants.MAX_WALLPAPER_LOAD_RETRIES) { renderer.render(wallpaper, ScreenType.HOME, any()) }
    }

    @Test
    fun `empty album stops after rebuilding once`() = runTest {
        coEvery { repository.getAndDequeueWallpaper(listOf("album"), ScreenType.HOME) } returns null
        coEvery { repository.ensureWallpaperQueue(listOf("album"), ScreenType.HOME, any()) } just Runs

        assertTrue(runCatching { useCase(listOf("album"), ScreenType.HOME) }.exceptionOrNull() is EmptyAlbumException)
        coVerify(exactly = 1) { repository.ensureWallpaperQueue(listOf("album"), ScreenType.HOME, any()) }
        coVerify(exactly = 0) { renderer.render(any(), any(), any()) }
    }

    private fun preparedWallpaper() = PreparedWallpaper(
        image = WallpaperImage.Drawn(mockk<Bitmap>(relaxed = true)),
        albumIds = listOf("album"),
        pictureAlbumId = "album",
        screenType = ScreenType.HOME,
        wallpaperId = "wallpaper",
        shuffle = false
    )
}
