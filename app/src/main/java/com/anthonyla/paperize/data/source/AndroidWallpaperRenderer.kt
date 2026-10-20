package com.anthonyla.paperize.data.source

import android.content.Context
import android.graphics.Bitmap
import androidx.core.net.toUri
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.util.applyWallpaperEffects
import com.anthonyla.paperize.core.util.getDeviceScreenSize
import com.anthonyla.paperize.core.util.retrieveBitmap
import com.anthonyla.paperize.core.util.usesLauncherManagedScrolling
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.domain.source.WallpaperRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class AndroidWallpaperRenderer @Inject constructor(
    @param:ApplicationContext private val context: Context
) : WallpaperRenderer {
    override suspend fun render(wallpaper: Wallpaper, screen: ScreenType, settings: ScheduleSettings): Bitmap? {
        currentCoroutineContext().ensureActive()
        val scaling = settings.scalingType(screen)
        val size = getDeviceScreenSize(context)
        var bitmap = retrieveBitmap(context, wallpaper.uri.toUri(), size.width, size.height, scaling,
            usesLauncherManagedScrolling(screen, scaling, settings.homeScrollingEnabled)) ?: return null
        try {
            bitmap = applyWallpaperEffects(context, bitmap, settings.appliedEffects(screen), settings.appliesAdaptiveBrightness)
            currentCoroutineContext().ensureActive()
            return bitmap
        } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
    }
}
