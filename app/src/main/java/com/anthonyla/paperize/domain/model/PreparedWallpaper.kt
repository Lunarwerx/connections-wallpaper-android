package com.anthonyla.paperize.domain.model

import com.anthonyla.paperize.core.ScreenType

/** A wallpaper ready for Android that has not yet been committed as current. */
data class PreparedWallpaper(
    val image: WallpaperImage,
    val albumId: String,
    val screenType: ScreenType,
    val wallpaperId: String,
    val shuffle: Boolean
)
