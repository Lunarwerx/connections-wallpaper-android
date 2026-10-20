package com.anthonyla.paperize.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.domain.model.ScheduleSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class SettingsRepositoryInstrumentedTest {
    @Test fun concurrentScheduleEditsReadTheLatestValueWithoutLosingSelections() = runBlocking {
        val preferences = SettingsRepositoryImpl(ApplicationProvider.getApplicationContext<Context>())
        val previousSettings = preferences.getScheduleSettings()
        try {
            val initial = ScheduleSettings(homeAlbumId = "home", homeIntervalMinutes = 30)
            preferences.updateScheduleSettings(initial)
            coroutineScope {
                repeat(20) {
                    launch {
                        preferences.updateScheduleSettings { current ->
                            current.copy(homeIntervalMinutes = current.homeIntervalMinutes + 1)
                        }
                    }
                }
                launch { preferences.updateAlbumId(ScreenType.HOME, "new-home") }
            }
            assertEquals(initial.copy(homeAlbumId = "new-home", homeIntervalMinutes = 50), preferences.getScheduleSettings())
        } finally {
            preferences.updateScheduleSettings(previousSettings)
        }
    }

    @Test fun clearingAnEmptyAlbumPreservesNewSelectionsAndOtherSettings() = runBlocking {
        val preferences = SettingsRepositoryImpl(ApplicationProvider.getApplicationContext<Context>())
        val previousSettings = preferences.getScheduleSettings()
        val previousMode = preferences.getWallpaperMode()
        try {
            preferences.updateWallpaperMode(WallpaperMode.STATIC)
            val settings = ScheduleSettings(enableChanger = true, homeEnabled = true, lockEnabled = true,
                homeAlbumId = "new-home", lockAlbumId = "lock", homeIntervalMinutes = 75)
            preferences.updateScheduleSettings(settings)
            preferences.clearEmptyAlbumSelection("old-home", ScreenType.HOME)
            assertEquals(settings, preferences.getScheduleSettings())
            preferences.clearEmptyAlbumSelection("new-home", ScreenType.HOME)
            assertEquals(settings.copy(homeAlbumId = null), preferences.getScheduleSettings())
            preferences.clearEmptyAlbumSelection("lock", ScreenType.LOCK)
            assertEquals(settings.copy(homeAlbumId = null, lockAlbumId = null, enableChanger = false), preferences.getScheduleSettings())
        } finally {
            preferences.updateScheduleSettings(previousSettings)
            preferences.updateWallpaperMode(previousMode)
        }
    }
}
