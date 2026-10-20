package com.anthonyla.paperize.core.util

import android.app.WallpaperManager
import com.anthonyla.paperize.domain.model.WallpaperImage
import java.io.IOException

/** Apply a wallpaper and treat WallpaperManager's documented zero return value as a failure. */
fun WallpaperManager.setImageChecked(image: WallpaperImage, which: Int): Int = when (image) {
    is WallpaperImage.Drawn -> setBitmap(image.bitmap, null, true, which)
    is WallpaperImage.Encoded -> image.file.inputStream().use { setStream(it, null, true, which) }
}.also { wallpaperId ->
        if (wallpaperId == 0) throw IOException("WallpaperManager rejected the wallpaper")
    }
