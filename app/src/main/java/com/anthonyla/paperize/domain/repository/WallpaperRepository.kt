package com.anthonyla.paperize.domain.repository

import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.domain.model.Wallpaper
import kotlinx.coroutines.flow.Flow

interface WallpaperRepository {
    suspend fun getWallpaperById(wallpaperId: String): Wallpaper?

    /** Atomic, so concurrent wallpaper changes never receive the same item. [albumIds] is the screen's albums. */
    suspend fun getAndDequeueWallpaper(albumIds: List<String>, screenType: ScreenType): Wallpaper?

    /** The item the next change of this queue will take, left in place. */
    suspend fun peekNextWallpaper(albumIds: List<String>, screenType: ScreenType): Wallpaper?

    suspend fun removeWallpaperFromQueue(
        albumIds: List<String>,
        screenType: ScreenType,
        wallpaperId: String
    )

    /** Restore a prepared wallpaper to the front when applying it did not succeed. */
    suspend fun restoreWallpaperToQueueFront(
        albumIds: List<String>,
        screenType: ScreenType,
        wallpaperId: String
    )

    /**
     * Fill an empty wallpaper queue from every picture of [albumIds]; preserve a queue already filled by another caller.
     * Shuffled across all the albums when [shuffle] is on, otherwise albums in the order given.
     */
    suspend fun ensureWallpaperQueue(
        albumIds: List<String>,
        screenType: ScreenType,
        shuffle: Boolean = false
    )

    /** Used when the shuffle setting changes, so every queue rebuilds in the new order. */
    suspend fun clearAllQueues()

    /** Drops every album's pictures from [screenType]'s queue, so it is rebuilt from the screen's current albums. */
    suspend fun clearScreenQueue(screenType: ScreenType)

    /** The picture most recently applied for [albumIds] on [screenType]. */
    suspend fun getCurrentWallpaper(albumIds: List<String>, screenType: ScreenType): Wallpaper?

    fun getCurrentWallpaperFlow(albumIds: List<String>, screenType: ScreenType): Flow<Wallpaper?>

    /** Records [wallpaperId] under [albumId], the album the picture belongs to. */
    suspend fun setCurrentWallpaper(albumId: String, screenType: ScreenType, wallpaperId: String)

}
