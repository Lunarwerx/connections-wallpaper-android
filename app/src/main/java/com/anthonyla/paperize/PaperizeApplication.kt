package com.anthonyla.paperize

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.Configuration
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.core.util.DataResetManager
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.service.worker.WallpaperScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltAndroidApp
class PaperizeApplication : Application(), Configuration.Provider, DefaultLifecycleObserver {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var wallpaperScheduler: WallpaperScheduler

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super<Application>.onCreate()

        // Must run before any other initialization that accesses DB/preferences
        DataResetManager.performResetIfNeeded(this)

        createNotificationChannel()

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

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    override fun onStart(owner: LifecycleOwner) {
        wallpaperScheduler.refreshAlbumsNow()
        // The system may have stopped the unlock watcher; only an app in front may start it again.
        owner.lifecycleScope.launch {
            wallpaperScheduler.syncUnlockWatcher(settingsRepository.getScheduleSettings(), settingsRepository.getWallpaperMode())
        }
    }
}
