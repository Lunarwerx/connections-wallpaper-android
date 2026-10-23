package com.anthonyla.paperize.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.constants.PreferenceKeys
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
            val initial = ScheduleSettings(homeAlbumIds = listOf("home"), homeIntervalMinutes = 30)
            preferences.updateScheduleSettings(initial)
            coroutineScope {
                repeat(20) {
                    launch {
                        preferences.updateScheduleSettings { current ->
                            current.copy(homeIntervalMinutes = current.homeIntervalMinutes + 1)
                        }
                    }
                }
                launch { preferences.updateAlbumIds(ScreenType.HOME, listOf("new-home")) }
            }
            assertEquals(initial.copy(homeAlbumIds = listOf("new-home"), homeIntervalMinutes = 50), preferences.getScheduleSettings())
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
                homeAlbumIds = listOf("new-home"), lockAlbumIds = listOf("lock"), homeIntervalMinutes = 75)
            preferences.updateScheduleSettings(settings)
            preferences.clearEmptyAlbumSelection("old-home", ScreenType.HOME)
            assertEquals(settings, preferences.getScheduleSettings())
            preferences.clearEmptyAlbumSelection("new-home", ScreenType.HOME)
            assertEquals(settings.copy(homeAlbumIds = emptyList()), preferences.getScheduleSettings())
            preferences.clearEmptyAlbumSelection("lock", ScreenType.LOCK)
            assertEquals(settings.copy(homeAlbumIds = emptyList(), lockAlbumIds = emptyList(), enableChanger = false), preferences.getScheduleSettings())
        } finally {
            preferences.updateScheduleSettings(previousSettings)
            preferences.updateWallpaperMode(previousMode)
        }
    }

    @Test fun aSingleAlbumIdStoredByAnOlderBuildReadsBackAsOneAlbum() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = SettingsRepositoryImpl(context)
        val previousSettings = preferences.getScheduleSettings()
        try {
            // The old build wrote one id under the single-album key and no list under the new key.
            context.dataStore.edit { prefs ->
                prefs.remove(stringPreferencesKey(PreferenceKeys.HOME_ALBUM_IDS))
                prefs[stringPreferencesKey(PreferenceKeys.HOME_ALBUM_ID)] = "legacy-home"
            }
            assertEquals(listOf("legacy-home"), preferences.getScheduleSettings().homeAlbumIds)
        } finally {
            // Writing settings runs the migration, which also removes the old key.
            preferences.updateScheduleSettings(previousSettings)
        }
    }
}
