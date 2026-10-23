package com.anthonyla.paperize.domain.model

import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.ScalingType
import com.anthonyla.paperize.core.constants.Constants

data class ScheduleSettings(
    val enableChanger: Boolean = false,
    val separateSchedules: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val homeEnabled: Boolean = false,
    val lockEnabled: Boolean = false,
    /** Ordered, distinct album ids the screen rotates through; empty means no album. */
    val homeAlbumIds: List<String> = emptyList(),
    val lockAlbumIds: List<String> = emptyList(),
    val homeIntervalMinutes: Int = Constants.DEFAULT_INTERVAL_MINUTES,
    val lockIntervalMinutes: Int = Constants.DEFAULT_INTERVAL_MINUTES,
    val liveIntervalMinutes: Int = Constants.DEFAULT_INTERVAL_MINUTES,
    val homeScalingType: ScalingType = ScalingType.FILL,
    val lockScalingType: ScalingType = ScalingType.FILL,
    val homeScrollingEnabled: Boolean = false,
    val homeEffects: WallpaperEffects = WallpaperEffects(),
    val lockEffects: WallpaperEffects = WallpaperEffects(),
    val liveAlbumIds: List<String> = emptyList(),
    val liveScalingType: ScalingType = ScalingType.FILL,
    val liveEffects: WallpaperEffects = WallpaperEffects(),
    val adaptiveBrightness: Boolean = false,
    /** Off: no visual effect or adaptive brightness is drawn; the choices stay for when it is on again. */
    val visualEffectsEnabled: Boolean = false,
    /** A new wallpaper as the screen turns off, so one is waiting at unlock. */
    val changeOnUnlock: Boolean = true,
    /** Off: only unlock, double tap, the widget and the shortcut change the wallpaper. */
    val intervalEnabled: Boolean = false
) {
    /**
     * Individual scheduling counts only while the timer it splits is running, the only time its switch
     * shows; otherwise both screens change together and the choice is kept for later.
     */
    val appliesSeparateSchedules: Boolean
        get() = separateSchedules && enableChanger && intervalEnabled && homeEnabled && lockEnabled

    val effectiveLockIntervalMinutes: Int
        get() = if (appliesSeparateSchedules) lockIntervalMinutes else homeIntervalMinutes

    val appliesAdaptiveBrightness: Boolean
        get() = visualEffectsEnabled && adaptiveBrightness

    /** What [screen] is drawn with: its visual effects only while they are on; double tap and parallax always. */
    fun appliedEffects(screen: ScreenType): WallpaperEffects {
        val chosen = when (screen) {
            ScreenType.HOME, ScreenType.BOTH -> homeEffects
            ScreenType.LOCK -> lockEffects
            ScreenType.LIVE -> liveEffects
        }
        return if (visualEffectsEnabled) chosen
        else chosen.copy(enableBlur = false, enableDarken = false, enableVignette = false, enableGrayscale = false)
    }

    fun hasRequiredAlbums(mode: WallpaperMode): Boolean = when (mode) {
        WallpaperMode.LIVE -> liveAlbumIds.isNotEmpty()
        WallpaperMode.STATIC -> (homeEnabled || lockEnabled) &&
            (!homeEnabled || homeAlbumIds.isNotEmpty()) && (!lockEnabled || lockAlbumIds.isNotEmpty())
    }

    fun activeScreens(mode: WallpaperMode): Set<ScreenType> {
        if (mode == WallpaperMode.LIVE) return if (liveAlbumIds.isNotEmpty()) setOf(ScreenType.LIVE) else emptySet()
        val home = homeEnabled && homeAlbumIds.isNotEmpty()
        val lock = lockEnabled && lockAlbumIds.isNotEmpty()
        if (home && lock && homeAlbumIds.toSet() == lockAlbumIds.toSet() && !appliesSeparateSchedules) {
            return setOf(ScreenType.BOTH)
        }
        return buildSet {
            if (home) add(ScreenType.HOME)
            if (lock) add(ScreenType.LOCK)
        }
    }

    /** The albums [screen] rotates. BOTH shares the home list, since both screens hold the same set when they are synced. */
    fun albumIds(screen: ScreenType): List<String> = when (screen) {
        ScreenType.HOME, ScreenType.BOTH -> homeAlbumIds
        ScreenType.LOCK -> lockAlbumIds
        ScreenType.LIVE -> liveAlbumIds
    }

    /**
     * These settings in [mode]: when [mode] has no album of its own, it takes the one the other mode
     * rotates, so switching modes keeps the pictures that were showing. Albums it already has stay.
     */
    fun carriedInto(mode: WallpaperMode): ScheduleSettings = when (mode) {
        WallpaperMode.LIVE -> if (liveAlbumIds.isNotEmpty()) this else copy(
            liveAlbumIds = when {
                homeEnabled && homeAlbumIds.isNotEmpty() -> homeAlbumIds
                lockEnabled && lockAlbumIds.isNotEmpty() -> lockAlbumIds
                else -> homeAlbumIds.ifEmpty { lockAlbumIds }
            }
        )
        WallpaperMode.STATIC -> if (liveAlbumIds.isEmpty() || hasRequiredAlbums(WallpaperMode.STATIC)) this else {
            val noneOn = !homeEnabled && !lockEnabled
            val home = homeEnabled || noneOn
            val lock = lockEnabled || noneOn
            copy(
                homeEnabled = home,
                lockEnabled = lock,
                homeAlbumIds = if (home) homeAlbumIds.ifEmpty { liveAlbumIds } else homeAlbumIds,
                lockAlbumIds = if (lock) lockAlbumIds.ifEmpty { liveAlbumIds } else lockAlbumIds
            )
        }
    }

    fun scalingType(screen: ScreenType): ScalingType = when (screen) {
        ScreenType.HOME, ScreenType.BOTH -> homeScalingType
        ScreenType.LOCK -> lockScalingType
        ScreenType.LIVE -> liveScalingType
    }

    /** Everything here that changes how a picture is drawn on [screen]: equal looks draw the same bitmap. */
    fun look(screen: ScreenType): WallpaperLook =
        WallpaperLook(scalingType(screen), appliedEffects(screen), appliesAdaptiveBrightness, homeScrollingEnabled)

    /** Home and lock draw a picture the same way, so one write sets both. */
    val homeAndLockLookAlike: Boolean
        get() = appliedEffects(ScreenType.HOME) == appliedEffects(ScreenType.LOCK) &&
            homeScalingType == lockScalingType && !homeScrollingEnabled

    fun intervalMinutes(screen: ScreenType): Int = when (screen) {
        ScreenType.HOME, ScreenType.BOTH -> homeIntervalMinutes
        ScreenType.LOCK -> effectiveLockIntervalMinutes
        ScreenType.LIVE -> liveIntervalMinutes
    }

    fun validate(): ScheduleSettings = copy(
        homeIntervalMinutes = homeIntervalMinutes.coerceAtLeast(Constants.MIN_INTERVAL_MINUTES),
        lockIntervalMinutes = lockIntervalMinutes.coerceAtLeast(Constants.MIN_INTERVAL_MINUTES),
        liveIntervalMinutes = liveIntervalMinutes.coerceAtLeast(Constants.MIN_LIVE_INTERVAL_MINUTES),
        homeEffects = homeEffects.validate(),
        lockEffects = lockEffects.validate(),
        liveEffects = liveEffects.validate()
    )

    fun hasSchedulingChanges(other: ScheduleSettings): Boolean {
        return enableChanger != other.enableChanger ||
               changeOnUnlock != other.changeOnUnlock ||
               intervalEnabled != other.intervalEnabled ||
               homeAlbumIds != other.homeAlbumIds ||
               lockAlbumIds != other.lockAlbumIds ||
               liveAlbumIds != other.liveAlbumIds ||
               homeEnabled != other.homeEnabled ||
               lockEnabled != other.lockEnabled ||
               homeIntervalMinutes != other.homeIntervalMinutes ||
               lockIntervalMinutes != other.lockIntervalMinutes ||
               separateSchedules != other.separateSchedules ||
               liveIntervalMinutes != other.liveIntervalMinutes
    }

    /** Display changes reapply the current wallpaper without rescheduling periodic work. */
    fun hasDisplayChanges(other: ScheduleSettings): Boolean {
        return homeScalingType != other.homeScalingType ||
               lockScalingType != other.lockScalingType ||
               homeScrollingEnabled != other.homeScrollingEnabled ||
               homeEffects != other.homeEffects ||
               lockEffects != other.lockEffects ||
               liveEffects != other.liveEffects ||
               liveScalingType != other.liveScalingType ||
               adaptiveBrightness != other.adaptiveBrightness ||
               visualEffectsEnabled != other.visualEffectsEnabled
    }

}

/**
 * WorkManager cannot run periodic jobs more often than every 15 minutes. Short live-wallpaper
 * intervals are therefore driven by the visible wallpaper engine and stop when it is hidden.
 */
fun usesVisibleLiveTimer(intervalMinutes: Int): Boolean =
    intervalMinutes in Constants.MIN_LIVE_INTERVAL_MINUTES until Constants.MIN_INTERVAL_MINUTES

data class WallpaperLook(
    val scaling: ScalingType,
    val effects: WallpaperEffects,
    val adaptiveBrightness: Boolean,
    val homeScrolling: Boolean
)
