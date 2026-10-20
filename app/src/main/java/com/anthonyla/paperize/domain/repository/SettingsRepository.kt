package com.anthonyla.paperize.domain.repository

import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.domain.model.AppSettings
import com.anthonyla.paperize.domain.model.ScheduleSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    suspend fun getAppSettings(): AppSettings

    fun getAppSettingsFlow(): Flow<AppSettings>

    suspend fun getWallpaperMode(): WallpaperMode

    fun getWallpaperModeFlow(): Flow<WallpaperMode>

    /** The caller must reset library and schedule data when switching modes. */
    suspend fun updateWallpaperMode(mode: WallpaperMode)

    suspend fun getScheduleSettings(): ScheduleSettings

    fun getScheduleSettingsFlow(): Flow<ScheduleSettings>

    suspend fun updateScheduleSettings(settings: ScheduleSettings)

    /** Apply an edit to the latest stored settings in one DataStore transaction. */
    suspend fun updateScheduleSettings(transform: (ScheduleSettings) -> ScheduleSettings): ScheduleSettings

    suspend fun clearAllSettings()

    /** BOTH updates the home and lock selections together. */
    suspend fun updateAlbumId(screen: ScreenType, albumId: String?)

    /** Clears matching selections atomically; returns whether any selection changed. */
    suspend fun clearAlbumSelectionsIfMatches(albumId: String): Boolean

    suspend fun clearEmptyAlbumSelection(albumId: String, screen: ScreenType)

    /** null follows the phone's theme; true or false pins dark or light. */
    suspend fun updateDarkMode(dark: Boolean?)
    suspend fun updateDynamicTheming(enabled: Boolean)
    suspend fun updateAnimate(enabled: Boolean)
    suspend fun updateFirstLaunch(isFirstLaunch: Boolean)

    suspend fun updateEnableChanger(enabled: Boolean)
}
