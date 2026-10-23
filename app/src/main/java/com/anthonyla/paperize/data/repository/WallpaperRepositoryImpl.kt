package com.anthonyla.paperize.data.repository

import androidx.room.withTransaction
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.util.QueueBuilder
import com.anthonyla.paperize.data.database.PaperizeDatabase
import com.anthonyla.paperize.data.database.entities.WallpaperCurrentEntity
import com.anthonyla.paperize.data.mapper.toDomainModel
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class WallpaperRepositoryImpl @Inject constructor(
    private val database: PaperizeDatabase
) : WallpaperRepository {

    private val wallpaperDao = database.wallpaperDao()
    private val wallpaperQueueDao = database.wallpaperQueueDao()
    private val wallpaperCurrentDao = database.wallpaperCurrentDao()

    override suspend fun getWallpaperById(wallpaperId: String): Wallpaper? =
        wallpaperDao.getWallpaperById(wallpaperId)?.toDomainModel()

    override suspend fun getAndDequeueWallpaper(albumIds: List<String>, screenType: ScreenType): Wallpaper? =
        wallpaperQueueDao.getAndDequeueWallpaper(albumIds, screenType)?.toDomainModel()

    override suspend fun peekNextWallpaper(albumIds: List<String>, screenType: ScreenType): Wallpaper? =
        wallpaperQueueDao.getNextWallpaperInQueue(albumIds, screenType)?.toDomainModel()

    override suspend fun removeWallpaperFromQueue(
        albumIds: List<String>,
        screenType: ScreenType,
        wallpaperId: String
    ) {
        wallpaperQueueDao.deleteQueueItem(albumIds, screenType, wallpaperId)
    }

    override suspend fun restoreWallpaperToQueueFront(
        albumIds: List<String>,
        screenType: ScreenType,
        wallpaperId: String
    ) {
        // The row keeps the picture's own album, so the foreign key and album cascade stay valid.
        val albumId = wallpaperDao.getWallpaperById(wallpaperId)?.albumId ?: return
        wallpaperQueueDao.restoreQueueItem(albumIds, albumId, screenType, wallpaperId)
    }

    override suspend fun ensureWallpaperQueue(
        albumIds: List<String>,
        screenType: ScreenType,
        shuffle: Boolean
    ) {
        if (albumIds.isEmpty()) return
        database.withTransaction {
            // Recheck inside the transaction: another caller may already have filled it.
            if (wallpaperQueueDao.getNextWallpaperInQueue(albumIds, screenType) != null) return@withTransaction
            val otherScreen = if (shuffle) when (screenType) {
                ScreenType.HOME -> ScreenType.LOCK
                ScreenType.LOCK -> ScreenType.HOME
                else -> null
            } else null
            val otherQueue = otherScreen?.let { wallpaperQueueDao.getQueueItems(albumIds, it) }.orEmpty()
            // Each picture as (albumId, wallpaperId), in the order it will be played.
            val pictures: List<Pair<String, String>> = if (shuffle) {
                val available = albumIds.flatMap { albumId ->
                    wallpaperDao.getWallpaperIdsByAlbum(albumId).map { albumId to it }
                }
                if (otherQueue.isEmpty()) {
                    available.shuffled()
                } else {
                    val albumOf = available.associate { (albumId, wallpaperId) -> wallpaperId to albumId }
                    QueueBuilder.mergeWithExistingQueue(otherQueue.map { it.wallpaperId }, albumOf.keys.toList())
                        .mapNotNull { wallpaperId -> albumOf[wallpaperId]?.let { it to wallpaperId } }
                }
            } else {
                albumIds.flatMap { albumId ->
                    wallpaperDao.getOrderedWallpaperIdsByAlbum(albumId).map { albumId to it }
                }
            }
            wallpaperQueueDao.rebuildQueue(screenType, albumIds, pictures)
            if (otherScreen != null && otherQueue.isEmpty()) {
                wallpaperQueueDao.rebuildQueue(otherScreen, albumIds, pictures)
            }
        }
    }

    override suspend fun clearAllQueues() {
        wallpaperQueueDao.deleteAllQueueItems()
    }

    override suspend fun clearScreenQueue(screenType: ScreenType) {
        wallpaperQueueDao.clearScreenQueue(screenType)
    }

    override suspend fun getCurrentWallpaper(albumIds: List<String>, screenType: ScreenType): Wallpaper? =
        wallpaperCurrentDao.getCurrentWallpaper(albumIds, screenType)?.toDomainModel()

    override fun getCurrentWallpaperFlow(albumIds: List<String>, screenType: ScreenType): Flow<Wallpaper?> =
        wallpaperCurrentDao.getCurrentWallpaperFlow(albumIds, screenType).map { it?.toDomainModel() }

    override suspend fun setCurrentWallpaper(albumId: String, screenType: ScreenType, wallpaperId: String) {
        wallpaperCurrentDao.upsertCurrentWallpaper(
            WallpaperCurrentEntity(albumId = albumId, screenType = screenType, wallpaperId = wallpaperId)
        )
    }

}
