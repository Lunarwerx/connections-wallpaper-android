package com.anthonyla.paperize.presentation.screens.settings

import android.util.Log
import androidx.lifecycle.ViewModelStore
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.domain.model.AppSettings
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.repository.AlbumRepository
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.service.SettingsCoordinator
import com.anthonyla.paperize.service.WallpaperWork
import com.anthonyla.paperize.service.worker.WallpaperScheduler
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val albums = mockk<AlbumRepository>()
    private val scheduler = mockk<WallpaperScheduler>(relaxed = true)
    private val store = ViewModelStore()
    private lateinit var viewModel: SettingsViewModel

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { settings.getAppSettingsFlow() } returns flowOf(AppSettings())
        every { settings.getWallpaperModeFlow() } returns flowOf(WallpaperMode.STATIC)
        viewModel = SettingsViewModel(settings, SettingsCoordinator(mockk(), settings, mockk(), albums, scheduler, WallpaperWork()))
        store.put("settings", viewModel)
    }

    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test fun `mode switch keeps the library and carries the rotating album into the new mode`() = runTest {
        var stored = ScheduleSettings(lockEnabled = true, lockAlbumIds = listOf("album"))
        coEvery { settings.getWallpaperMode() } returns WallpaperMode.STATIC
        coEvery { settings.updateScheduleSettings(any<(ScheduleSettings) -> ScheduleSettings>()) } answers {
            stored = firstArg<(ScheduleSettings) -> ScheduleSettings>()(stored)
            stored
        }
        var needsLivePicker: Boolean? = null
        viewModel.switchWallpaperMode(WallpaperMode.LIVE) { needsLivePicker = it }
        runCurrent()
        // A second tap while the first switch runs is ignored.
        viewModel.switchWallpaperMode(WallpaperMode.STATIC)
        advanceUntilIdle()

        coVerify(exactly = 1) { settings.updateWallpaperMode(any()) }
        coVerify { settings.updateWallpaperMode(WallpaperMode.LIVE) }
        coVerify(exactly = 0) { albums.deleteAllAlbums(); settings.clearAllSettings() }
        assertEquals(listOf("album"), stored.liveAlbumIds)
        assertEquals(listOf("album"), stored.lockAlbumIds)
        assertEquals(false, needsLivePicker)
        assertNull(viewModel.failure.value)
        assertFalse(viewModel.isBusy.value)
    }

    @Test fun `failed reset keeps settings and allows retry`() = runTest {
        coEvery { albums.deleteAllAlbums() } throws IllegalStateException("Database unavailable")
        viewModel.resetAllData()
        advanceUntilIdle()
        assertEquals(R.string.reset_failed, viewModel.failure.value)
        coVerify(exactly = 0) { settings.clearAllSettings() }

        coEvery { albums.deleteAllAlbums() } just Runs
        viewModel.resetAllData()
        advanceUntilIdle()
        assertNull(viewModel.failure.value)
        // Rotation stops before the albums it reads from are deleted.
        coVerifyOrder {
            settings.updateEnableChanger(false)
            scheduler.cancelAllWallpaperChanges()
            albums.deleteAllAlbums()
            settings.clearAllSettings()
        }
    }
}
