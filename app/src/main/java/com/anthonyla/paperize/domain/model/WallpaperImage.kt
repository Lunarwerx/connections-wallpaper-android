package com.anthonyla.paperize.domain.model

import android.graphics.Bitmap
import java.io.File

/** A wallpaper ready for Android: drawn just now, or drawn and encoded ahead of time. */
sealed interface WallpaperImage {
    /** Free what this holds, once Android has it or it is no longer wanted. */
    fun release()

    class Drawn(val bitmap: Bitmap) : WallpaperImage {
        override fun release() = bitmap.recycle()
    }

    class Encoded(val file: File) : WallpaperImage {
        override fun release() {
            file.delete()
        }
    }
}
