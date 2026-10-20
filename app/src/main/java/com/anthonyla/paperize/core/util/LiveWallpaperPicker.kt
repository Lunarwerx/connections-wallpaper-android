package com.anthonyla.paperize.core.util

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.anthonyla.paperize.service.livewallpaper.PaperizeLiveWallpaperService

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
