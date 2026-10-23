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

    /** BOTH updates the home and lock selections together. Ids are kept in the order given. */
    suspend fun updateAlbumIds(screen: ScreenType, ids: List<String>)

    /** Removes [albumId] from every selection atomically; returns whether any selection held it. */
    suspend fun clearAlbumSelectionsIfMatches(albumId: String): Boolean

    /** Removes [albumId] from the selections of [screen]. */
    suspend fun clearEmptyAlbumSelection(albumId: String, screen: ScreenType)

    /** null follows the phone's theme; true or false pins dark or light. */
    suspend fun updateDarkMode(dark: Boolean?)
    suspend fun updateDynamicTheming(enabled: Boolean)
    suspend fun updateAnimate(enabled: Boolean)
    suspend fun updateFirstLaunch(isFirstLaunch: Boolean)

    suspend fun updateEnableChanger(enabled: Boolean)

    /** A picture from outside the library (a Connections wallpaper) that [screen] shows until its album moves on. */
    fun getShownPictureFlow(screen: ScreenType): Flow<String?>
    suspend fun getShownPicture(screen: ScreenType): String?

    /** Null clears it: [screens] show their album's current picture again. */
    suspend fun setShownPicture(screens: Collection<ScreenType>, uri: String?)
}
