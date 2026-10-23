package com.anthonyla.paperize.presentation.screens.home

import android.util.Log
import androidx.lifecycle.ViewModelStore
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.domain.model.AppSettings
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.repository.AlbumRepository
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import com.anthonyla.paperize.domain.usecase.ImportWallpapersUseCase
import com.anthonyla.paperize.presentation.screens.album_view.ImportProgress
import com.anthonyla.paperize.service.SettingsCoordinator
import com.anthonyla.paperize.service.WallpaperWork
import com.anthonyla.paperize.service.livewallpaper.PaperizeLiveWallpaperService
import com.anthonyla.paperize.service.worker.WallpaperScheduler
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val settings = mockk<SettingsRepository>()
    private val albums = mockk<AlbumRepository>()
    private val wallpapers = mockk<WallpaperRepository>(relaxed = true)
    private val scheduler = mockk<WallpaperScheduler>(relaxed = true)
    private val importer = mockk<ImportWallpapersUseCase>()
    private val stored = MutableStateFlow(ScheduleSettings(homeEnabled = true, homeAlbumIds = listOf("old")))
    private val store = ViewModelStore()
    private lateinit var viewModel: HomeViewModel

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { albums.getAlbumSummaries() } returns flowOf(emptyList())
        every { settings.getScheduleSettingsFlow() } returns stored
        every { settings.getAppSettingsFlow() } returns flowOf(AppSettings())
        every { settings.getWallpaperModeFlow() } returns flowOf(WallpaperMode.STATIC)
        coEvery { settings.getWallpaperMode() } returns WallpaperMode.STATIC
        coEvery { settings.getScheduleSettings() } answers { stored.value }
        coEvery { settings.updateEnableChanger(any()) } answers { stored.value = stored.value.copy(enableChanger = firstArg()) }
        coEvery { settings.updateAlbumIds(ScreenType.HOME, any()) } answers { stored.value = stored.value.copy(homeAlbumIds = secondArg()) }
        coEvery { settings.updateScheduleSettings(any<(ScheduleSettings) -> ScheduleSettings>()) } answers {
            stored.value = firstArg<(ScheduleSettings) -> ScheduleSettings>()(stored.value)
            stored.value
        }
        every { wallpapers.getCurrentWallpaperFlow(any(), any()) } returns flowOf(null)
        every { settings.getShownPictureFlow(any()) } returns flowOf(null)
        // Opening the app asks Android where Paperize's live wallpaper shows; here it shows nowhere.
        mockkObject(PaperizeLiveWallpaperService.Companion)
        every { PaperizeLiveWallpaperService.showingOn(any()) } returns emptySet()
        val coordinator = SettingsCoordinator(mockk(relaxed = true), settings, wallpapers, albums, scheduler, WallpaperWork())
        viewModel = HomeViewModel(albums, settings, wallpapers, importer, coordinator)
        store.put("home", viewModel)
    }

    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test fun `delayed effect edits preserve a newer pause and album selection`() = runTest {
        stored.value = stored.value.copy(enableChanger = true)
        val draft = stored.value.copy(homeEffects = stored.value.homeEffects.copy(blurPercentage = 60))
        viewModel.updateScheduleSettingsDebounced(draft)
        viewModel.setChangerEnabled(false)
        runCurrent()
        viewModel.setAlbums(ScreenType.HOME, listOf("new"))
        advanceUntilIdle()

        assertFalse(stored.value.enableChanger)
        assertEquals(listOf("new"), stored.value.homeAlbumIds)
        assertEquals(60, stored.value.homeEffects.blurPercentage)
    }

    @Test fun `closing the effects page saves a waiting slider value once, without the debounce`() = runTest {
        val draft = stored.value.copy(homeEffects = stored.value.homeEffects.copy(enableBlur = true, blurPercentage = 35))
        viewModel.updateScheduleSettingsDebounced(draft)
        runCurrent()
        assertEquals(0, stored.value.homeEffects.blurPercentage)

        viewModel.flushPendingSettings()
        runCurrent()
        assertEquals(35, stored.value.homeEffects.blurPercentage)

        viewModel.flushPendingSettings()
        advanceUntilIdle()
        coVerify(exactly = 1) { settings.updateScheduleSettings(any<(ScheduleSettings) -> ScheduleSettings>()) }
    }

    @Test fun `a screen switched off and on again keeps its album`() = runTest {
        stored.value = stored.value.copy(lockEnabled = true, lockAlbumIds = listOf("lock"))
        viewModel.updateScheduleSettings(stored.value.copy(homeEnabled = false))
        advanceUntilIdle()
        assertFalse(ScreenType.HOME in stored.value.activeScreens(WallpaperMode.STATIC))
        assertEquals(listOf("lock"), stored.value.lockAlbumIds)

        viewModel.updateScheduleSettings(stored.value.copy(homeEnabled = true))
        advanceUntilIdle()
        assertEquals(listOf("old"), stored.value.homeAlbumIds)
        assertTrue(ScreenType.HOME in stored.value.activeScreens(WallpaperMode.STATIC))
    }

    @Test fun `rapid edits keep scheduling side effects in persistence order`() = runTest {
        val firstSchedule = CompletableDeferred<Unit>()
        val scheduled = mutableListOf<Boolean>()
        coEvery { scheduler.updateSchedules(any(), any(), any()) } coAnswers {
            val snapshot = firstArg<ScheduleSettings>()
            if (!snapshot.enableChanger) firstSchedule.await()
            scheduled.add(snapshot.enableChanger)
        }
        viewModel.updateScheduleSettings(stored.value.copy(homeIntervalMinutes = 45))
        runCurrent()
        viewModel.setChangerEnabled(true)
        runCurrent()
        assertFalse(stored.value.enableChanger)
        firstSchedule.complete(Unit)
        advanceUntilIdle()
        assertTrue(stored.value.enableChanger)
        assertEquals(listOf(false, true), scheduled)
    }

    @Test fun `a failed folder import says why and leaves the importer ready to retry`() = runTest {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        coEvery { importer.createAlbumFromFolder(any(), any(), any()) } throws
            SecurityException("permission revoked") andThenThrows IllegalStateException("disk full")

        viewModel.addFolderAlbum("content://tree/pictures")
        advanceUntilIdle()
        assertEquals(R.string.import_permission_error, viewModel.importError.value)
        assertEquals(ImportProgress.Idle, viewModel.importProgress.value)

        viewModel.addFolderAlbum("content://tree/pictures")
        advanceUntilIdle()
        assertEquals(R.string.import_failed, viewModel.importError.value)
        assertEquals(ImportProgress.Idle, viewModel.importProgress.value)
        coVerify(exactly = 2) { importer.createAlbumFromFolder(any(), any(), any()) }
    }

    @Test fun `effect edits do not restart preview subscriptions but album changes do`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.currentHomeWallpaperUri.collect() }
        runCurrent()
        stored.value = stored.value.copy(homeEffects = stored.value.homeEffects.copy(blurPercentage = 60))
        runCurrent()
        verify(exactly = 1) { wallpapers.getCurrentWallpaperFlow(listOf("old"), ScreenType.HOME) }
        verify(exactly = 1) { wallpapers.getCurrentWallpaperFlow(listOf("old"), ScreenType.BOTH) }

        stored.value = stored.value.copy(homeAlbumIds = listOf("new"))
        runCurrent()
        verify(exactly = 1) { wallpapers.getCurrentWallpaperFlow(listOf("new"), ScreenType.HOME) }
        verify(exactly = 1) { wallpapers.getCurrentWallpaperFlow(listOf("new"), ScreenType.BOTH) }
    }
}
