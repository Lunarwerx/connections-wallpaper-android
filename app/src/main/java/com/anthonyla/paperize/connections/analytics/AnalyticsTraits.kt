package com.anthonyla.paperize.connections.analytics

import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.domain.model.AppSettings
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.model.WallpaperEffects

/** Every app and schedule setting as flat traits, so a changed setting can be found by comparing two maps. */
internal fun settingsTraits(app: AppSettings, mode: WallpaperMode, schedule: ScheduleSettings): Map<String, Any?> =
    linkedMapOf<String, Any?>(
        "theme" to when (app.darkMode) {
            null -> "system"
            true -> "dark"
            false -> "light"
        },
        "dynamic_theming" to app.dynamicTheming,
        "animation" to app.animate,
        "wallpaper_mode" to mode.name,
        "enable_changer" to schedule.enableChanger,
        "separate_schedules" to schedule.separateSchedules,
        "shuffle_enabled" to schedule.shuffleEnabled,
        "home_enabled" to schedule.homeEnabled,
        "lock_enabled" to schedule.lockEnabled,
        "home_album_count" to schedule.homeAlbumIds.size,
        "lock_album_count" to schedule.lockAlbumIds.size,
        "live_album_count" to schedule.liveAlbumIds.size,
        "home_interval_minutes" to schedule.homeIntervalMinutes,
        "lock_interval_minutes" to schedule.lockIntervalMinutes,
        "live_interval_minutes" to schedule.liveIntervalMinutes,
        "home_scaling_type" to schedule.homeScalingType.name,
        "lock_scaling_type" to schedule.lockScalingType.name,
        "live_scaling_type" to schedule.liveScalingType.name,
        "home_scrolling_enabled" to schedule.homeScrollingEnabled,
        "adaptive_brightness" to schedule.adaptiveBrightness,
        "visual_effects_enabled" to schedule.visualEffectsEnabled,
        "change_on_unlock" to schedule.changeOnUnlock,
        "interval_enabled" to schedule.intervalEnabled
    ) + effectTraits("home_effect_", schedule.homeEffects) +
        effectTraits("lock_effect_", schedule.lockEffects) +
        effectTraits("live_effect_", schedule.liveEffects)

private fun effectTraits(prefix: String, effects: WallpaperEffects): Map<String, Any?> = mapOf(
    "${prefix}enable_blur" to effects.enableBlur,
    "${prefix}blur_percentage" to effects.blurPercentage,
    "${prefix}enable_darken" to effects.enableDarken,
    "${prefix}darken_percentage" to effects.darkenPercentage,
    "${prefix}enable_vignette" to effects.enableVignette,
    "${prefix}vignette_percentage" to effects.vignettePercentage,
    "${prefix}enable_grayscale" to effects.enableGrayscale,
    "${prefix}grayscale_percentage" to effects.grayscalePercentage,
    "${prefix}enable_double_tap" to effects.enableDoubleTap,
    "${prefix}enable_parallax" to effects.enableParallax,
    "${prefix}parallax_intensity" to effects.parallaxIntensity
)
