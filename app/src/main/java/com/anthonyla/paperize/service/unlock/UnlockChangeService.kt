package com.anthonyla.paperize.service.unlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.presentation.MainActivity
import com.anthonyla.paperize.service.WallpaperChangeLock
import com.anthonyla.paperize.service.wallpaper.WallpaperController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Static mode's "Change on unlock". Only a running process hears the screen turn off, so this quiet
 * foreground service listens and sets the next wallpaper then, ready before the phone is unlocked.
 * Live mode needs none of this: its engine hears the same broadcast.
 */
@AndroidEntryPoint
class UnlockChangeService : Service() {

    @Inject lateinit var wallpaperController: WallpaperController
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var wallpaperChangeLock: WallpaperChangeLock

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listening = false
    private var lastChangeAt = 0L

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) changeNext()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!listening) {
            ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            listening = true
        }
        // The system restarts a stopped sticky service on its own; stay only while the setting asks for it.
        scope.launch {
            if (!wanted(settingsRepository.getScheduleSettings(), settingsRepository.getWallpaperMode())) stopSelf()
        }
        return START_STICKY
    }

    private fun changeNext() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastChangeAt < MIN_GAP_MS) return
        lastChangeAt = now
        scope.launch {
            // A change already running (the timer, the widget) is this unlock's new wallpaper.
            if (!wallpaperChangeLock.mutex.tryLock()) return@launch
            try {
                val settings = settingsRepository.getScheduleSettings()
                val mode = settingsRepository.getWallpaperMode()
                if (!wanted(settings, mode)) {
                    stopSelf()
                    return@launch
                }
                for (screen in settings.activeScreens(mode)) wallpaperController.change(screen, settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Change on unlock failed", e)
            } finally {
                wallpaperChangeLock.mutex.unlock()
            }
        }
    }

    private fun notification(): Notification {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.change_on_unlock), NotificationManager.IMPORTANCE_MIN)
            .apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.unlock_watch_title))
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (listening) unregisterReceiver(screenOff)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "UnlockChangeService"
        private const val CHANNEL_ID = "change_on_unlock"
        private const val NOTIFICATION_ID = 3

        /** Screen-off events closer together than this (a pocket, a call) change the wallpaper once. */
        private const val MIN_GAP_MS = 3_000L

        fun wanted(settings: ScheduleSettings, mode: WallpaperMode): Boolean =
            mode == WallpaperMode.STATIC && settings.enableChanger && settings.changeOnUnlock && settings.hasRequiredAlbums(mode)

        /** Start or stop the watcher. Android refuses a start from the background; the next app open retries. */
        fun sync(context: Context, run: Boolean) {
            val intent = Intent(context, UnlockChangeService::class.java)
            if (!run) {
                context.stopService(intent)
                return
            }
            try {
                context.startForegroundService(intent)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Unlock watcher not started from the background", e)
            }
        }
    }
}
