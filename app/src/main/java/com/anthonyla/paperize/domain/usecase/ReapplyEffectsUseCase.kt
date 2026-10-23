package com.anthonyla.paperize.domain.usecase

import android.graphics.Bitmap
import com.anthonyla.paperize.core.NoValidWallpaperException
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import com.anthonyla.paperize.domain.source.WallpaperRenderer
import javax.inject.Inject

class ReapplyEffectsUseCase @Inject constructor(
    private val wallpaperRepository: WallpaperRepository,
    private val settingsRepository: SettingsRepository,
    private val renderer: WallpaperRenderer
) {
    /** [wallpaperId], else what [screenType] shows now: a picture set from outside the library, else its album's current one. */
    suspend operator fun invoke(albumId: String, screenType: ScreenType, wallpaperId: String? = null): Bitmap {
        if (wallpaperId == null) settingsRepository.getShownPicture(screenType)?.let { return picture(it, screenType) }
        val wallpaper = if (wallpaperId != null) wallpaperRepository.getWallpaperById(wallpaperId)
            else wallpaperRepository.getCurrentWallpaper(albumId, screenType)
        return wallpaper?.let { renderer.render(it, screenType, settingsRepository.getScheduleSettings()) }
            ?: throw NoValidWallpaperException()
    }

    /** [uri], a picture outside the library, drawn with [screenType]'s scaling and effects. */
    suspend fun picture(uri: String, screenType: ScreenType): Bitmap {
        val picture = Wallpaper(id = uri, albumId = "", uri = uri, fileName = uri.substringAfterLast('/'), dateModified = 0)
        return renderer.render(picture, screenType, settingsRepository.getScheduleSettings()) ?: throw NoValidWallpaperException()
    }
}
