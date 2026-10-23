package com.anthonyla.paperize

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.res.Configuration.UI_MODE_NIGHT_MASK
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.anthonyla.paperize.connections.ConnectionsPictures
import com.anthonyla.paperize.connections.PictureImages
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.connections.analytics.ConnectionsAnalytics
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.runCatchingCancellable
import com.anthonyla.paperize.core.util.DataResetManager
import com.anthonyla.paperize.core.util.isNightMode
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.service.WallpaperChangeLock
import com.anthonyla.paperize.service.wallpaper.WallpaperController
import com.anthonyla.paperize.service.worker.WallpaperScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

@HiltAndroidApp
class PaperizeApplication : Application(), Configuration.Provider, DefaultLifecycleObserver, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var wallpaperScheduler: WallpaperScheduler

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var analytics: ConnectionsAnalytics

    @Inject
    lateinit var wallpaperController: WallpaperController

    @Inject
    lateinit var wallpaperChangeLock: WallpaperChangeLock

    private var night = false

    @Inject
    lateinit var pictures: ConnectionsPictures

    /** Library pictures draw from what the phone holds, or their gallery preview, never a fetch for a thumbnail (PictureImages). */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val images = PictureImages(pictures)
        return ImageLoader.Builder(context).components {
            add(images.forUri)
            add(images.forString)
        }.build()
    }

    override fun onCreate() {
        super<Application>.onCreate()
        night = isNightMode(this)

        // Must run before any other initialization that accesses DB/preferences
        DataResetManager.performResetIfNeeded(this)

        createNotificationChannel()
        analytics.start()

        // Process lifecycle distinguishes real background/foreground transitions from activity
        // recreation, so folder-backed albums are refreshed whenever the user returns to the app.
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        // Change on unlock's notice has a channel of its own from the first launch, so Settings can open Android's page
        // for it before the watcher has ever run, and turning it off hides nothing else.
        val unlock = NotificationChannel(
            Constants.UNLOCK_CHANNEL_ID,
            getString(R.string.change_on_unlock),
            NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannels(listOf(channel, unlock))
    }

    override fun onStart(owner: LifecycleOwner) {
        wallpaperScheduler.refreshAlbumsNow()
        // The system may have stopped the unlock watcher; only an app in front may start it again.
        owner.lifecycleScope.launch {
            wallpaperScheduler.syncUnlockWatcher(settingsRepository.getScheduleSettings(), settingsRepository.getWallpaperMode())
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        analytics.flushNow()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val nowNight = (newConfig.uiMode and UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES
        if (nowNight == night) return
        night = nowNight
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) { redrawForNightMode() }
    }

    /**
     * Adaptive brightness draws a static picture for dark or light mode, so a switch redraws the same
     * wallpaper for the new mode. This runs while the process is alive; otherwise the next change draws
     * for the new mode. The live wallpaper redraws itself.
     */
    private suspend fun redrawForNightMode() {
        runCatchingCancellable {
            wallpaperChangeLock.mutex.withLock {
                val mode = settingsRepository.getWallpaperMode()
                val settings = settingsRepository.getScheduleSettings()
                if (mode == WallpaperMode.STATIC && settings.enableChanger && settings.appliesAdaptiveBrightness) {
                    for (screen in settings.activeScreens(mode)) wallpaperController.reapply(screen, settings)
                }
            }
        }.onFailure { Log.w(TAG, "Could not redraw the wallpaper for the new dark/light mode", it) }
    }

    private companion object {
        const val TAG = "PaperizeApplication"
    }
}
