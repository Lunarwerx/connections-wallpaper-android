package com.anthonyla.paperize.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.data.database.entities.WallpaperEntity
import com.anthonyla.paperize.data.database.entities.WallpaperQueueEntity

/**
 * A screen's queue holds the pictures of every album it rotates. Each row keeps its picture's own albumId.
 */
@Dao
interface WallpaperQueueDao {
    @Query("""
        SELECT w.* FROM wallpapers w
        INNER JOIN wallpaper_queue wq ON w.id = wq.wallpaperId
        WHERE wq.albumId IN (:albumIds) AND wq.screenType = :screenType
        ORDER BY wq.queuePosition ASC
        LIMIT 1
    """)
    suspend fun getNextWallpaperInQueue(albumIds: List<String>, screenType: ScreenType): WallpaperEntity?

    @Transaction
    suspend fun getAndDequeueWallpaper(albumIds: List<String>, screenType: ScreenType): WallpaperEntity? {
        val wallpaper = getNextWallpaperInQueue(albumIds, screenType)
        if (wallpaper != null) {
            deleteQueueItem(albumIds, screenType, wallpaper.id)
        }
        return wallpaper
    }

    @Query("""
        SELECT * FROM wallpaper_queue
        WHERE albumId IN (:albumIds) AND screenType = :screenType
        ORDER BY queuePosition ASC
    """)
    suspend fun getQueueItems(albumIds: List<String>, screenType: ScreenType): List<WallpaperQueueEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQueueItems(items: List<WallpaperQueueEntity>)

    @Query("""
        DELETE FROM wallpaper_queue
        WHERE albumId IN (:albumIds) AND screenType = :screenType
        AND wallpaperId = :wallpaperId
    """)
    suspend fun deleteQueueItem(albumIds: List<String>, screenType: ScreenType, wallpaperId: String)

    @Query("""
        SELECT MIN(queuePosition) FROM wallpaper_queue
        WHERE albumId IN (:albumIds) AND screenType = :screenType
    """)
    suspend fun getFirstQueuePosition(albumIds: List<String>, screenType: ScreenType): Int?

    /** Puts a picture at the front of [screenType]'s queue under its own [albumId]. */
    @Transaction
    suspend fun restoreQueueItem(
        albumIds: List<String>,
        albumId: String,
        screenType: ScreenType,
        wallpaperId: String
    ) {
        deleteQueueItem(albumIds + albumId, screenType, wallpaperId)
        val firstPosition = getFirstQueuePosition(albumIds, screenType) ?: 0
        insertQueueItems(
            listOf(
                WallpaperQueueEntity(
                    albumId = albumId,
                    wallpaperId = wallpaperId,
                    screenType = screenType,
                    queuePosition = firstPosition - 1
                )
            )
        )
    }

    @Query("DELETE FROM wallpaper_queue WHERE screenType = :screenType")
    suspend fun clearScreenQueue(screenType: ScreenType)

    @Query("DELETE FROM wallpaper_queue WHERE albumId IN (:albumIds) AND screenType = :screenType")
    suspend fun clearQueue(albumIds: List<String>, screenType: ScreenType)

    @Query("DELETE FROM wallpaper_queue WHERE albumId = :albumId")
    suspend fun clearAllQueues(albumId: String)

    @Query("DELETE FROM wallpaper_queue")
    suspend fun deleteAllQueueItems()

    /**
     * Replaces the [albumIds] part of [screenType]'s queue with [pictures], each an (albumId, wallpaperId) pair in play
     * order. Rows of other albums stay: on a shuffled screen they are the other screen's own queue.
     */
    @Transaction
    suspend fun rebuildQueue(
        screenType: ScreenType,
        albumIds: List<String>,
        pictures: List<Pair<String, String>>
    ) {
        clearQueue(albumIds, screenType)
        val items = pictures.mapIndexed { index, (albumId, wallpaperId) ->
            WallpaperQueueEntity(
                albumId = albumId,
                wallpaperId = wallpaperId,
                screenType = screenType,
                queuePosition = index
            )
        }
        insertQueueItems(items)
    }
}
