package com.anthonyla.paperize.core.util

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.anthonyla.paperize.service.livewallpaper.PaperizeLiveWallpaperService

/**
 * Whether this device has a screen that turns on a live wallpaper: Android's preview or its chooser. Only that
 * screen can, so without one (Google's Desktop emulator image, measured 2026-10-20) live mode cannot start.
 */
fun canPickLiveWallpaper(context: Context): Boolean =
    listOf(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER, WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
        .any { Intent(it).resolveActivity(context.packageManager) != null }

/** Open Android's preview of this app's live wallpaper, or the closest wallpaper screen the phone has. */
fun openLiveWallpaperPicker(context: Context) {
    val attempts = listOf(
        Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(context, PaperizeLiveWallpaperService::class.java)
        ),
        Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER),
        Intent(Settings.ACTION_SETTINGS)
    )
    for (intent in attempts) {
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // Some phones lack the preview or the chooser; try the next screen.
        }
    }
}
