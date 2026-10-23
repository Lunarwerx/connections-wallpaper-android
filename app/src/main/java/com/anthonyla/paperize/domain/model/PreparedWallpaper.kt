package com.anthonyla.paperize.domain.model

import com.anthonyla.paperize.core.ScreenType

/**
 * A wallpaper ready for Android that has not yet been committed as current.
 * [albumIds] are the albums the screen rotates; [pictureAlbumId] is the album the picture itself belongs to.
 */
data class PreparedWallpaper(
    val image: WallpaperImage,
    val albumIds: List<String>,
    val pictureAlbumId: String,
    val screenType: ScreenType,
    val wallpaperId: String,
    val shuffle: Boolean
)
