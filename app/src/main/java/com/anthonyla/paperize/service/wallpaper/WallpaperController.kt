package com.anthonyla.paperize.service.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import com.anthonyla.paperize.core.EmptyAlbumException
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.runCatchingCancellable
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.core.util.setImageChecked
import com.anthonyla.paperize.domain.model.PreparedWallpaper
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.model.WallpaperImage
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.usecase.ChangeWallpaperUseCase
import com.anthonyla.paperize.domain.usecase.ReapplyEffectsUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class WallpaperChangeOutcome(val changed: Boolean = false, val emptyAlbum: Boolean = false)

/** Call while holding WallpaperChangeLock so applying and resetting schedules stay ordered. */
class WallpaperController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val wallpaperManager: WallpaperManager,
    private val prepare: ChangeWallpaperUseCase,
    private val render: ReapplyEffectsUseCase,
    private val settingsRepository: SettingsRepository,
    private val ready: ReadyWallpapers
) {
    /** [now]: the person asked (Change wallpaper now, the shortcut), so our live wallpaper moves on even while hidden. */
    suspend fun change(screen: ScreenType, settings: ScheduleSettings, now: Boolean = false): WallpaperChangeOutcome = when (screen) {
        ScreenType.LIVE -> {
            context.sendBroadcast(
                Intent(Constants.ACTION_RELOAD_WALLPAPER).setPackage(context.packageName).putExtra(Constants.EXTRA_NOW, now)
            )
            WallpaperChangeOutcome(changed = true)
        }
        ScreenType.HOME -> changeSelected(settings.albumIds(ScreenType.HOME), screen, settings)
        ScreenType.LOCK -> changeSelected(settings.albumIds(ScreenType.LOCK), screen, settings)
        ScreenType.BOTH -> {
            val home = settings.homeAlbumIds
            if (home.isNotEmpty() && home.toSet() == settings.lockAlbumIds.toSet() && !settings.appliesSeparateSchedules) {
                changeSynchronized(home, settings)
            } else {
                combine(changeSelected(home, ScreenType.HOME, settings), changeSelected(settings.lockAlbumIds, ScreenType.LOCK, settings))
            }
        }
    }

    private suspend fun changeSelected(albumIds: List<String>, screen: ScreenType, settings: ScheduleSettings): WallpaperChangeOutcome {
        if (albumIds.isEmpty()) return WallpaperChangeOutcome()
        val prepared = prepareOrDisable(albumIds, screen, settings) ?: return WallpaperChangeOutcome(emptyAlbum = true)
        applyPrepared(prepared, screen)
        return WallpaperChangeOutcome(changed = true)
    }

    private suspend fun changeSynchronized(albumIds: List<String>, settings: ScheduleSettings): WallpaperChangeOutcome {
        val prepared = prepareOrDisable(albumIds, ScreenType.BOTH, settings) ?: return WallpaperChangeOutcome(emptyAlbum = true)
        if (settings.homeAndLockLookAlike) {
            applyPrepared(prepared, ScreenType.BOTH)
        } else {
            applyPrepared(prepared, ScreenType.HOME)
            val lockImage = ready.take(prepared.wallpaperId, ScreenType.LOCK, settings)?.let(WallpaperImage::Encoded)
                ?: WallpaperImage.Drawn(render(albumIds, ScreenType.LOCK, prepared.wallpaperId))
            applyImage(lockImage, ScreenType.LOCK) { prepare.complete(prepared, ScreenType.LOCK) }
        }
        return WallpaperChangeOutcome(changed = true)
    }

    private suspend fun prepareOrDisable(albumIds: List<String>, screen: ScreenType, settings: ScheduleSettings): PreparedWallpaper? {
        val queue = if (screen == ScreenType.BOTH) ScreenType.HOME else screen
        try {
            return ready.takeNext(albumIds, queue, settings) ?: prepare(albumIds, queue)
        } catch (_: EmptyAlbumException) {
            // The queue is drawn from every album, so this means each of them is empty.
            albumIds.forEach { settingsRepository.clearEmptyAlbumSelection(it, screen) }
            return null
        }
    }

    private suspend fun applyPrepared(prepared: PreparedWallpaper, screen: ScreenType) {
        var accepted = false
        try {
            currentCoroutineContext().ensureActive()
            wallpaperManager.setImageChecked(prepared.image, screen.flags())
            accepted = true
            // Once Android accepts the bitmap, cancellation must not leave our current item stale.
            withContext(NonCancellable) {
                screen.staticScreens().forEach { prepare.complete(prepared, it) }
            }
        } catch (e: Exception) {
            if (!accepted) withContext(NonCancellable) { prepare.restore(prepared) }
            throw e
        } finally {
            prepared.image.release()
        }
    }

    /** [pictureAlbumId] is the album the picture belongs to; the queues it leaves are those of [screen]'s albums. */
    suspend fun applySpecific(pictureAlbumId: String, wallpaperId: String, screen: ScreenType, settings: ScheduleSettings) {
        val targets = if (screen == ScreenType.BOTH && !settings.homeAndLockLookAlike) {
            listOf(ScreenType.HOME, ScreenType.LOCK)
        } else listOf(screen)
        for (target in targets) {
            val renderScreen = if (target == ScreenType.BOTH) ScreenType.HOME else target
            val bitmap = render(settings.albumIds(renderScreen), renderScreen, wallpaperId)
            applyImage(WallpaperImage.Drawn(bitmap), target) {
                target.staticScreens().forEach {
                    prepare.completeSpecific(settings.albumIds(it), pictureAlbumId, it, wallpaperId, settings.shuffleEnabled)
                }
            }
        }
    }

    /**
     * A picture from outside the library on [screen] (HOME, LOCK or BOTH), drawn with that screen's look (in live mode,
     * the live wallpaper's) and recorded as what the screen shows until its album moves on.
     */
    suspend fun applyPicture(uri: String, screen: ScreenType, settings: ScheduleSettings, mode: WallpaperMode) {
        val separate = screen == ScreenType.BOTH && mode == WallpaperMode.STATIC && !settings.homeAndLockLookAlike
        for (target in if (separate) listOf(ScreenType.HOME, ScreenType.LOCK) else listOf(screen)) {
            val look = when {
                mode == WallpaperMode.LIVE -> ScreenType.LIVE
                target == ScreenType.BOTH -> ScreenType.HOME
                else -> target
            }
            applyImage(WallpaperImage.Drawn(render.picture(uri, look)), target) {
                settingsRepository.setShownPicture(target.staticScreens(), uri)
            }
        }
    }

    /**
     * Static mode never moves Paperize's live wallpaper, so a [screen] left on it would freeze there. It takes its own
     * album's current picture when it rotates, else the current picture of the screen that does; a screen switched off
     * lends nothing. A rotating screen with no current picture yet takes its album's next one, as [reapply] does.
     */
    suspend fun replaceLive(screen: ScreenType, settings: ScheduleSettings) {
        val rotating = settings.activeScreens(WallpaperMode.STATIC).flatMap { it.staticScreens() }
        val source = if (screen in rotating) screen else rotating.firstOrNull() ?: return
        val albumIds = settings.albumIds(source).ifEmpty { return }
        val bitmap = runCatchingCancellable { render(albumIds, source) }.getOrNull()
        when {
            bitmap != null -> applyImage(WallpaperImage.Drawn(bitmap), screen)
            screen == source -> changeSelected(albumIds, screen, settings)
            else -> error("$source has no current picture to lend $screen")
        }
    }

    suspend fun reapply(screen: ScreenType, settings: ScheduleSettings): WallpaperChangeOutcome {
        if (screen == ScreenType.LIVE) return WallpaperChangeOutcome()
        var outcome = WallpaperChangeOutcome()
        for (target in screen.staticScreens()) {
            val albumIds = settings.albumIds(target)
            if (albumIds.isEmpty()) continue
            val bitmap = runCatchingCancellable { render(albumIds, target) }.getOrNull()
            val result = if (bitmap == null) changeSelected(albumIds, target, settings) else {
                applyImage(WallpaperImage.Drawn(bitmap), target)
                WallpaperChangeOutcome(changed = true)
            }
            outcome = combine(outcome, result)
        }
        return outcome
    }

    private suspend fun applyImage(image: WallpaperImage, screen: ScreenType, onApplied: suspend () -> Unit = {}) {
        try {
            currentCoroutineContext().ensureActive()
            wallpaperManager.setImageChecked(image, screen.flags())
            withContext(NonCancellable) { onApplied() }
        } finally {
            image.release()
        }
    }

    private fun combine(first: WallpaperChangeOutcome, second: WallpaperChangeOutcome) = WallpaperChangeOutcome(
        changed = first.changed || second.changed, emptyAlbum = first.emptyAlbum || second.emptyAlbum
    )

    private fun ScreenType.staticScreens() = if (this == ScreenType.BOTH) listOf(ScreenType.HOME, ScreenType.LOCK) else listOf(this)

    private fun ScreenType.flags(): Int = when (this) {
        ScreenType.HOME -> WallpaperManager.FLAG_SYSTEM
        ScreenType.LOCK -> WallpaperManager.FLAG_LOCK
        ScreenType.BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        ScreenType.LIVE -> error("Live wallpaper is applied by its engine")
    }
}
