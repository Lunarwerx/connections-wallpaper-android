package com.anthonyla.paperize.service

import android.content.Context
import android.util.Log
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.runCatchingCancellable
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.repository.AlbumRepository
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import com.anthonyla.paperize.service.livewallpaper.PaperizeLiveWallpaperService
import com.anthonyla.paperize.service.wallpaper.WallpaperChangeService
import com.anthonyla.paperize.service.worker.WallpaperScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persists wallpaper settings edits and applies their side effects: schedules, queues, and
 * immediate wallpaper changes. Edits run one at a time, in call order.
 */
@Singleton
class SettingsCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val wallpaperRepository: WallpaperRepository,
    private val albumRepository: AlbumRepository,
    private val scheduler: WallpaperScheduler,
    private val work: WallpaperWork
) {
    private val mutex = Mutex()

    suspend fun selectAlbum(screen: ScreenType, albumId: String?) = mutex.withLock {
        settingsRepository.updateAlbumId(screen, albumId)
        val mode = settingsRepository.getWallpaperMode()
        var settings = settingsRepository.getScheduleSettings()
        if (albumId == null && settings.activeScreens(mode).isEmpty()) {
            settingsRepository.updateEnableChanger(false)
            settings = settings.copy(enableChanger = false)
        }
        scheduler.updateSchedules(settings, mode)
        if (albumId != null && settings.enableChanger && settings.hasRequiredAlbums(mode) && mode == WallpaperMode.STATIC) {
            val active = settings.activeScreens(mode)
            WallpaperChangeService.changeWallpaper(context, if (ScreenType.BOTH in active) ScreenType.BOTH else screen)
        }
    }

    /**
     * A new album goes where it was added from: the [screen] whose picker added it, or every enabled
     * screen still without an album (both screens when none is on yet). When nothing rotated before,
     * adding it starts rotation. Returns true when live mode now needs its live wallpaper picked.
     */
    suspend fun adoptNewAlbum(albumId: String, screen: ScreenType?): Boolean = mutex.withLock {
        val count = albumRepository.getAlbumSummaries().first().find { it.id == albumId }?.wallpaperCount ?: 0
        if (count == 0) return@withLock false
        val mode = settingsRepository.getWallpaperMode()
        lateinit var before: ScheduleSettings
        val after = settingsRepository.updateScheduleSettings { current ->
            before = current
            val adopted = when {
                mode == WallpaperMode.LIVE ->
                    if (screen != null || current.liveAlbumId == null) current.copy(liveAlbumId = albumId) else current
                screen == ScreenType.HOME -> current.copy(homeEnabled = true, homeAlbumId = albumId)
                screen == ScreenType.LOCK -> current.copy(lockEnabled = true, lockAlbumId = albumId)
                else -> {
                    val noneOn = !current.homeEnabled && !current.lockEnabled
                    val home = current.homeEnabled || noneOn
                    val lock = current.lockEnabled || noneOn
                    current.copy(
                        homeEnabled = home,
                        lockEnabled = lock,
                        homeAlbumId = if (home && current.homeAlbumId == null) albumId else current.homeAlbumId,
                        lockAlbumId = if (lock && current.lockAlbumId == null) albumId else current.lockAlbumId
                    )
                }
            }
            if (current.activeScreens(mode).isEmpty() && adopted.hasRequiredAlbums(mode)) adopted.copy(enableChanger = true) else adopted
        }
        if (after == before) return@withLock false
        scheduler.updateSchedules(after, mode)
        if (mode == WallpaperMode.LIVE) {
            return@withLock after.enableChanger && !PaperizeLiveWallpaperService.isActive(context)
        }
        if (after.enableChanger && after.hasRequiredAlbums(mode)) {
            val both = ScreenType.BOTH in after.activeScreens(mode)
            val home = after.homeAlbumId != before.homeAlbumId
            val lock = after.lockAlbumId != before.lockAlbumId
            when {
                home && lock -> WallpaperChangeService.changeWallpaper(context)
                home -> WallpaperChangeService.changeWallpaper(context, if (both) ScreenType.BOTH else ScreenType.HOME)
                lock -> WallpaperChangeService.changeWallpaper(context, if (both) ScreenType.BOTH else ScreenType.LOCK)
            }
        }
        false
    }

    /**
     * Pause or resume rotation. Resuming keeps any countdown that is still scheduled.
     * Returns true when live mode is on but Paperize is not the active live wallpaper.
     */
    suspend fun setChangerEnabled(enabled: Boolean): Boolean = mutex.withLock {
        settingsRepository.updateEnableChanger(enabled)
        val settings = settingsRepository.getScheduleSettings()
        val mode = settingsRepository.getWallpaperMode()
        scheduler.updateSchedules(settings, mode, onlyIfNotScheduled = true)
        if (enabled && lookWaiting && mode == WallpaperMode.STATIC && settings.hasRequiredAlbums(mode)) reapplyLook()
        enabled && mode == WallpaperMode.LIVE && settings.hasRequiredAlbums(mode) &&
            !PaperizeLiveWallpaperService.isActive(context)
    }

    suspend fun updateScheduleSettings(draft: ScheduleSettings) = mutex.withLock {
        lateinit var previous: ScheduleSettings
        val updated = settingsRepository.updateScheduleSettings { current ->
            previous = current
            // Album selection and pause/resume have their own actions. A delayed effect edit
            // must not overwrite changes those actions made after this draft was captured.
            // A screen switched off keeps its album for when it is on again; activeScreens skips it.
            draft.copy(
                enableChanger = current.enableChanger,
                homeAlbumId = current.homeAlbumId,
                lockAlbumId = current.lockAlbumId,
                liveAlbumId = current.liveAlbumId
            ).validate()
        }
        if (previous.shuffleEnabled != updated.shuffleEnabled) {
            // Stale queues only delay the new order until they drain; keep the settings edit.
            runCatchingCancellable { wallpaperRepository.clearAllQueues() }
                .onFailure { Log.w(TAG, "Could not clear queues after shuffle change", it) }
        }
        val mode = settingsRepository.getWallpaperMode()
        if (updated.hasSchedulingChanges(previous)) scheduler.updateSchedules(updated, mode)
        if (updated.hasDisplayChanges(previous) && updated.hasRequiredAlbums(mode) && mode == WallpaperMode.STATIC) {
            // Changing off, the phone keeps its pictures as they are (a person may have set one elsewhere).
            if (updated.enableChanger) reapplyLook() else lookWaiting = true
        }
    }

    // A look edit made while changing was off, drawn once it is on again (review, 2026-10-22).
    private var lookWaiting = false

    private fun reapplyLook() {
        lookWaiting = false
        work.reapplyStarted()
        try {
            WallpaperChangeService.reapplyEffects(context)
        } catch (e: Exception) {
            work.reapplyDone(applied = false)
            throw e
        }
    }

    /** True from a look edit until its redrawn wallpaper is on the phone. */
    val reapplying = work.reapplying

    /** True from Change wallpaper now until the next picture is on the phone. */
    val changing = work.changing

    /** When ([WallpaperWork.now]) a look edit last reached the phone, so the screen says Applied after it. */
    val looksApplied: StateFlow<Long> = work.looksApplied

    /** When ([WallpaperWork.now]) a Change wallpaper now tap last put its picture up, so the button says Changed. */
    val changesApplied: StateFlow<Long> = work.changesApplied

    /**
     * Change every screen the current settings rotate, or show the live wallpaper's next picture. In live mode with
     * Paperize's live wallpaper replaced, nothing would show it: rotation turns back on and this returns true, since the
     * live wallpaper must be picked again first.
     */
    suspend fun changeWallpaperNow(): Boolean {
        if (settingsRepository.getWallpaperMode() == WallpaperMode.LIVE && !PaperizeLiveWallpaperService.isActive(context)) {
            return setChangerEnabled(true)
        }
        work.changeStarted()
        try {
            WallpaperChangeService.changeWallpaper(context, tracked = true)
        } catch (e: Exception) {
            work.changeDone(changed = false)
            throw e
        }
        return false
    }

    /**
     * Square the settings with what the phone shows. In live mode, Paperize's live wallpaper replaced outside the app
     * pauses rotation (the album stays, so resuming only asks for the live wallpaper again). In static mode, Paperize's
     * live wallpaper still showing is frozen, since static mode never moves it, so a static picture replaces it now and
     * the rotating screens keep theirs.
     */
    suspend fun matchPhoneWallpaper(): Unit = mutex.withLock {
        val settings = settingsRepository.getScheduleSettings()
        when (val mode = settingsRepository.getWallpaperMode()) {
            WallpaperMode.LIVE ->
                if (settings.liveAlbumId != null && settings.enableChanger && !PaperizeLiveWallpaperService.isActive(context)) {
                    settingsRepository.updateEnableChanger(false)
                    scheduler.cancelAllWallpaperChanges()
                }
            WallpaperMode.STATIC ->
                if (settings.enableChanger && settings.hasRequiredAlbums(mode) &&
                    PaperizeLiveWallpaperService.showingOn(context).isNotEmpty()
                ) {
                    WallpaperChangeService.replaceLiveWallpaper(context)
                }
        }
    }

    /**
     * Switch to [mode] keeping the library and every setting; a mode without an album takes the one
     * rotating now. Returns true when live mode now needs Paperize picked as the live wallpaper.
     */
    suspend fun switchMode(mode: WallpaperMode): Boolean = mutex.withLock {
        val previous = settingsRepository.getWallpaperMode()
        val settings = settingsRepository.updateScheduleSettings { it.carriedInto(mode) }
        if (previous != mode) {
            // The old mode's jobs and live timer stop before the new mode's start.
            scheduler.cancelAllWallpaperChanges()
            settingsRepository.updateWallpaperMode(mode)
        }
        // Picking the mode already on keeps any countdown still running.
        scheduler.updateSchedules(settings, mode, onlyIfNotScheduled = previous == mode)
        if (!settings.enableChanger || !settings.hasRequiredAlbums(mode)) return@withLock false
        when (mode) {
            WallpaperMode.STATIC -> {
                // A switch changes the wallpaper now, which also replaces our live one; picking Static again only
                // replaces a live wallpaper still showing (owner report, 2026-10-21).
                if (previous != mode) {
                    WallpaperChangeService.changeWallpaper(context)
                } else if (PaperizeLiveWallpaperService.showingOn(context).isNotEmpty()) {
                    WallpaperChangeService.replaceLiveWallpaper(context)
                }
                false
            }
            WallpaperMode.LIVE -> !PaperizeLiveWallpaperService.isActive(context)
        }
    }

    /** Delete the library and every setting. */
    suspend fun resetData() = mutex.withLock {
        // Stop both the visible live timer and background jobs before deleting their source.
        settingsRepository.updateEnableChanger(false)
        scheduler.cancelAllWallpaperChanges()
        albumRepository.deleteAllAlbums()
        settingsRepository.clearAllSettings()
    }

    private companion object {
        const val TAG = "SettingsCoordinator"
    }
}
