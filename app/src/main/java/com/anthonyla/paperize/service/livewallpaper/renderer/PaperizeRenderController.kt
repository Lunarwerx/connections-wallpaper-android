package com.anthonyla.paperize.service.livewallpaper.renderer

import android.util.Log
import com.anthonyla.paperize.core.constants.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What a reload opened: [loader] to draw, and [onShown] to record it, run only once it is the one drawn. */
class OpenedArtwork(val loader: ImageLoader, val onShown: suspend () -> Unit = {})

/** All calls run on the engine's main dispatcher; its scope owns cancellation. */
class PaperizeRenderController(
    private val queueWallpaper: (ImageLoader, Boolean) -> Unit,
    private val controllerScope: CoroutineScope,
    private val openCurrentArtwork: suspend () -> OpenedArtwork
) {
    var visible = false
        set(value) {
            field = value
            if (value && hasPendingReload) reloadCurrentArtwork(immediate = true)
        }

    private var isLoading = false
    private var hasPendingReload = false
    private var throttleJob: Job? = null
    // Counts show() calls, so a reload already loading when one comes cannot replace the picture it showed.
    private var shows = 0

    fun reloadCurrentArtwork(immediate: Boolean = false) {
        if (!visible || isLoading) {
            hasPendingReload = true
            return
        }

        hasPendingReload = false
        throttleJob?.cancel()
        if (immediate) {
            executeReload()
        } else {
            throttleJob = controllerScope.launch {
                delay(Constants.RELOAD_THROTTLE_MS)
                if (visible) executeReload() else hasPendingReload = true
            }
        }
    }

    /** Show [loader] now; a reload still waiting is dropped, so it cannot replace it. */
    fun show(loader: ImageLoader) {
        shows++
        hasPendingReload = false
        throttleJob?.cancel()
        queueWallpaper(loader, false)
    }

    /** Prepare the next wallpaper while the screen is dark, without crossfading. */
    fun forceReloadCurrentArtwork() {
        if (isLoading) return
        hasPendingReload = false
        throttleJob?.cancel()
        executeReload(skipCrossfade = true)
    }

    private fun executeReload(skipCrossfade: Boolean = false) {
        isLoading = true
        val showsAtStart = shows
        controllerScope.launch {
            try {
                val opened = openCurrentArtwork()
                // A picture shown meanwhile stays, and the one this reload picked is not recorded as shown.
                if (isActive && !hasPendingReload && shows == showsAtStart) {
                    queueWallpaper(opened.loader, skipCrossfade)
                    opened.onShown()
                }
            } catch (e: Exception) {
                if (isActive) Log.e("PaperizeRenderController", "Error reloading wallpaper", e)
            } finally {
                isLoading = false
                if (isActive && hasPendingReload && visible) {
                    reloadCurrentArtwork(immediate = true)
                }
            }
        }
    }
}
