package com.anthonyla.paperize.service.livewallpaper.renderer

import com.anthonyla.paperize.core.constants.Constants
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaperizeRenderControllerTest {
    @Test fun `reload during selection discards the stale loader and selects again`() = runTest {
        val uploads = mutableListOf<Pair<ImageLoader, Boolean>>()
        val first = CompletableDeferred<ImageLoader>()
        val stale = mockk<ContentUriImageLoader>()
        val current = mockk<ContentUriImageLoader>()
        var selections = 0
        val controller = PaperizeRenderController({ loader, skip -> uploads.add(loader to skip) }, backgroundScope) {
            OpenedArtwork(if (selections++ == 0) first.await() else current)
        }
        controller.visible = true
        controller.reloadCurrentArtwork(immediate = true)
        runCurrent()
        controller.reloadCurrentArtwork()
        first.complete(stale)
        runCurrent()

        assertEquals(listOf(current to false), uploads)
    }

    @Test fun `hidden debounce waits until visible without selecting twice`() = runTest {
        val uploads = mutableListOf<Pair<ImageLoader, Boolean>>()
        val loader = mockk<ContentUriImageLoader>()
        val controller = PaperizeRenderController({ loader, skip -> uploads.add(loader to skip) }, backgroundScope) { OpenedArtwork(loader) }
        controller.visible = true
        controller.reloadCurrentArtwork()
        runCurrent()
        controller.visible = false
        advanceTimeBy(Constants.RELOAD_THROTTLE_MS)
        runCurrent()
        assertTrue(uploads.isEmpty())

        controller.visible = true
        runCurrent()
        assertEquals(listOf(loader to false), uploads)
    }

    @Test fun `a picture shown while a reload is opening stays, and the reload's pick is not recorded`() = runTest {
        val uploads = mutableListOf<Pair<ImageLoader, Boolean>>()
        val opening = CompletableDeferred<ImageLoader>()
        val picked = mockk<ContentUriImageLoader>()
        val shown = mockk<ContentUriImageLoader>()
        var recorded = false
        val controller = PaperizeRenderController({ loader, skip -> uploads.add(loader to skip) }, backgroundScope) {
            OpenedArtwork(opening.await()) { recorded = true }
        }
        controller.visible = true
        controller.reloadCurrentArtwork(immediate = true)
        runCurrent()
        controller.show(shown)
        opening.complete(picked)
        runCurrent()

        assertEquals(listOf(shown to false), uploads)
        assertFalse(recorded)
    }
}
