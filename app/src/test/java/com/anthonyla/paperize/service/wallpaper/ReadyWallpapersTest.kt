package com.anthonyla.paperize.service.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.util.getDeviceScreenSize
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.domain.model.WallpaperImage
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import com.anthonyla.paperize.domain.source.WallpaperRenderer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.nio.file.Files

class ReadyWallpapersTest {
    private val cache = Files.createTempDirectory("ready-test").toFile()
    private val repository = mockk<WallpaperRepository>(relaxed = true)
    private val renderer = mockk<WallpaperRenderer>()
    private val picture = Wallpaper.empty(id = "next", albumId = "album")
    private val settings = ScheduleSettings(homeEnabled = true, lockEnabled = true, homeAlbumId = "album", lockAlbumId = "album")
    private lateinit var ready: ReadyWallpapers

    @Before fun setUp() {
        mockkStatic("com.anthonyla.paperize.core.util.WallpaperUtilKt")
        every { getDeviceScreenSize(any()) } returns mockk<Size> {
            every { width } returns 1080
            every { height } returns 2400
        }
        coEvery { renderer.render(any(), any(), any()) } answers {
            mockk<Bitmap>(relaxed = true) {
                every { compress(any(), any(), any()) } answers { thirdArg<OutputStream>().write(byteArrayOf(1, 2, 3)); true }
            }
        }
        coEvery { repository.peekNextWallpaper("album", ScreenType.HOME) } returns picture
        coEvery { repository.getAndDequeueWallpaper("album", ScreenType.HOME) } returns picture
        ready = ReadyWallpapers(mockk<Context> { every { cacheDir } returns cache }, repository, renderer)
    }

    @After fun tearDown() {
        unmockkAll()
        cache.deleteRecursively()
    }

    @Test fun `a picture drawn ahead is used once, and only while the settings still draw it that way`() = runTest {
        ready.prepare(listOf(ScreenType.BOTH), settings)
        val gray = settings.homeEffects.copy(enableGrayscale = true, grayscalePercentage = 100)
        val grayscale = settings.copy(visualEffectsEnabled = true, homeEffects = gray, lockEffects = gray)
        assertNull("effects turned on since: the change draws it again", ready.takeNext("album", ScreenType.HOME, grayscale))

        ready.prepare(listOf(ScreenType.BOTH), grayscale)
        assertEquals("the old drawing is dropped, not kept beside the new one", 1, File(cache, "ready").listFiles()!!.size)
        val taken = ready.takeNext("album", ScreenType.HOME, grayscale)
        assertEquals("next", taken?.wallpaperId)
        assertTrue(taken?.image is WallpaperImage.Encoded)
        assertNull("it is handed out once", ready.takeNext("album", ScreenType.HOME, grayscale))
    }
}
