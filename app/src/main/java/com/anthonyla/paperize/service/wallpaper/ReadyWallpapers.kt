package com.anthonyla.paperize.service.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.util.getDeviceScreenSize
import com.anthonyla.paperize.core.util.isNightMode
import com.anthonyla.paperize.domain.model.PreparedWallpaper
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.domain.model.WallpaperImage
import com.anthonyla.paperize.domain.model.WallpaperLook
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import com.anthonyla.paperize.domain.source.WallpaperRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * The next wallpaper of each screen, drawn with its effects and encoded ahead of time. A change at
 * screen-off then only hands Android a finished file, and is done before the screen can come back on.
 */
@Singleton
class ReadyWallpapers @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val wallpaperRepository: WallpaperRepository,
    private val renderer: WallpaperRenderer
) {
    /** [night]: adaptive brightness draws a picture for one mode, so one drawn before a dark/light switch is not taken after it. */
    private data class Key(val wallpaperId: String, val screen: ScreenType, val look: WallpaperLook, val width: Int, val height: Int, val night: Boolean)

    /** One picture being drawn or drawn; [file] and [dropped] are guarded by [drawings]. */
    private class Drawing {
        val done = CompletableDeferred<Unit>()
        var file: File? = null
        var dropped = false
    }

    private val drawings = HashMap<Key, Drawing>()
    private val dir = File(context.cacheDir, "ready").apply { deleteRecursively() }

    /** Draw what the next change of each of [screens] will show, and drop what was drawn for anything else. */
    suspend fun prepare(screens: Collection<ScreenType>, settings: ScheduleSettings) {
        val wanted = LinkedHashMap<Key, Wallpaper>()
        for (screen in screens) {
            val (albumId, queue) = when (screen) {
                ScreenType.HOME, ScreenType.BOTH -> settings.homeAlbumId to ScreenType.HOME
                ScreenType.LOCK -> settings.lockAlbumId to ScreenType.LOCK
                ScreenType.LIVE -> continue
            }
            if (albumId == null) continue
            val next = peek(albumId, queue, settings.shuffleEnabled) ?: continue
            val drawnFor = when {
                screen != ScreenType.BOTH -> listOf(screen)
                settings.homeAndLockLookAlike -> listOf(ScreenType.HOME)
                else -> listOf(ScreenType.HOME, ScreenType.LOCK)
            }
            for (target in drawnFor) wanted[key(next.id, target, settings)] = next
        }
        val started = synchronized(drawings) {
            (drawings.keys - wanted.keys).forEach { drawings.remove(it)?.drop() }
            wanted.keys.filter { it !in drawings }.associateWith { key -> Drawing().also { drawings[key] = it } }
        }
        for ((key, drawing) in started) {
            var file: File? = null
            try {
                file = draw(wanted.getValue(key), key.screen, settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The next wallpaper could not be drawn ahead; its change will draw it", e)
            } finally {
                synchronized(drawings) { if (drawing.dropped) file?.delete() else drawing.file = file }
                drawing.done.complete(Unit)
            }
        }
    }

    /** The file drawn ahead of [wallpaperId] for [screen], as [settings] draw it now, or null. The caller owns the file. */
    suspend fun take(wallpaperId: String, screen: ScreenType, settings: ScheduleSettings): File? {
        val drawing = synchronized(drawings) { drawings.remove(key(wallpaperId, screen, settings)) } ?: return null
        try {
            drawing.done.await()
        } catch (e: CancellationException) {
            synchronized(drawings) { drawing.drop() }
            throw e
        }
        return synchronized(drawings) { drawing.file }
    }

    /** The next wallpaper of [albumId]'s [queue] when it was drawn ahead: taken off the queue, with its file. */
    suspend fun takeNext(albumId: String, queue: ScreenType, settings: ScheduleSettings): PreparedWallpaper? {
        val next = wallpaperRepository.peekNextWallpaper(albumId, queue) ?: return null
        val file = take(next.id, queue, settings) ?: return null
        val taken = try {
            wallpaperRepository.getAndDequeueWallpaper(albumId, queue)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        if (taken?.id != next.id) {
            // The queue moved while the drawing finished (an album refresh): its new head is drawn as usual.
            file.delete()
            taken?.let { wallpaperRepository.restoreWallpaperToQueueFront(albumId, queue, it.id) }
            return null
        }
        return PreparedWallpaper(WallpaperImage.Encoded(file), albumId, queue, next.id, settings.shuffleEnabled)
    }

    private suspend fun peek(albumId: String, queue: ScreenType, shuffle: Boolean): Wallpaper? =
        wallpaperRepository.peekNextWallpaper(albumId, queue) ?: run {
            wallpaperRepository.ensureWallpaperQueue(albumId, queue, shuffle)
            wallpaperRepository.peekNextWallpaper(albumId, queue)
        }

    private suspend fun draw(wallpaper: Wallpaper, screen: ScreenType, settings: ScheduleSettings): File? {
        val bitmap = renderer.render(wallpaper, screen, settings) ?: return null
        val file = File(dir.apply { mkdirs() }, "${UUID.randomUUID()}.png")
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encoding failed" } }
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            bitmap.recycle()
        }
    }

    private fun key(wallpaperId: String, screen: ScreenType, settings: ScheduleSettings): Key {
        val size = getDeviceScreenSize(context)
        return Key(wallpaperId, screen, settings.look(screen), size.width, size.height, isNightMode(context))
    }

    /** Call holding [drawings]. */
    private fun Drawing.drop() {
        dropped = true
        file?.delete()
        file = null
    }

    private companion object {
        const val TAG = "ReadyWallpapers"
    }
}
