package com.anthonyla.paperize.service.unlock

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.presentation.MainActivity
import com.anthonyla.paperize.service.WallpaperChangeLock
import com.anthonyla.paperize.service.wallpaper.ReadyWallpapers
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
 * The next one is drawn ahead (after each change and each unlock), so the change at screen-off only
 * hands Android a finished file. Live mode needs none of this: its engine hears the same broadcast.
 */
@AndroidEntryPoint
class UnlockChangeService : Service() {

    @Inject lateinit var wallpaperController: WallpaperController
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var wallpaperChangeLock: WallpaperChangeLock
    @Inject lateinit var readyWallpapers: ReadyWallpapers

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listening = false
    private var inForeground = false
    private var lastChangeAt = 0L
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:change-on-unlock")
            .apply { setReferenceCounted(false) }
    }

    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> changeNext()
                Intent.ACTION_USER_PRESENT -> scope.launch { prepareNext() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every settings edit starts the watcher again; posting the notice only the first time keeps one the user swiped
        // away gone. A service already in the foreground owes Android no second startForeground.
        if (!inForeground) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                // A sticky restart from the background may be refused the foreground; the next app open starts it.
                Log.w(TAG, "Unlock watcher restarted in the background without the foreground", e)
                stopSelf()
                return START_NOT_STICKY
            }
            inForeground = true
        }
        if (!listening) {
            val events = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_USER_PRESENT) }
            ContextCompat.registerReceiver(this, screenEvents, events, ContextCompat.RECEIVER_NOT_EXPORTED)
            listening = true
        }
        // The system restarts a stopped sticky service on its own; stay only while the setting asks for it.
        scope.launch {
            if (!wanted(settingsRepository.getScheduleSettings(), settingsRepository.getWallpaperMode())) stopSelf()
            else prepareNext()
        }
        return START_STICKY
    }

    private fun changeNext() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastChangeAt < MIN_GAP_MS) return
        lastChangeAt = now
        // Asleep, the phone would finish the change only when the screen came back on, in plain view.
        wakeLock.acquire(WAKE_LIMIT_MS)
        scope.launch {
            try {
                change(now)
                prepareNext()
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    private suspend fun change(screenOffAt: Long) {
        // A change already running (the timer, the widget) is this unlock's new wallpaper.
        if (!wallpaperChangeLock.mutex.tryLock()) return
        try {
            val settings = settingsRepository.getScheduleSettings()
            val mode = settingsRepository.getWallpaperMode()
            if (!wanted(settings, mode)) {
                stopSelf()
                return
            }
            // The lock screen is the one seen first when the screen comes back on.
            for (screen in settings.activeScreens(mode).sortedByDescending { it == ScreenType.LOCK }) {
                wallpaperController.change(screen, settings)
            }
            Log.i(TAG, "Screen-off change done in ${SystemClock.elapsedRealtime() - screenOffAt} ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Change on unlock failed", e)
        } finally {
            wallpaperChangeLock.mutex.unlock()
        }
    }

    private suspend fun prepareNext() {
        try {
            val settings = settingsRepository.getScheduleSettings()
            val mode = settingsRepository.getWallpaperMode()
            if (wanted(settings, mode)) readyWallpapers.prepare(settings.activeScreens(mode), settings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Drawing the next wallpaper ahead failed; the next change draws it", e)
        }
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, Constants.UNLOCK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.unlock_watch_title))
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, getString(R.string.hide), PendingIntent.getActivity(this, 1, noticeSettings(this), PendingIntent.FLAG_IMMUTABLE))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (listening) unregisterReceiver(screenEvents)
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "UnlockChangeService"
        private const val NOTIFICATION_ID = 3

        /** Screen-off events closer together than this (one press read twice) change the wallpaper once. */
        private const val MIN_GAP_MS = 1_000L

        /** The longest the phone is kept awake for one change, should a picture be slow to read. */
        private const val WAKE_LIMIT_MS = 10_000L

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

        /** Whether Android shows the watcher's notice: not when its channel, or the app's notifications, are off. */
        fun noticeShown(context: Context): Boolean {
            val manager = NotificationManagerCompat.from(context)
            val channel = manager.getNotificationChannelCompat(Constants.UNLOCK_CHANNEL_ID)
            return manager.areNotificationsEnabled() && channel?.importance != NotificationManagerCompat.IMPORTANCE_NONE
        }

        /**
         * Android's page for the notice. Android raises a running service's notice to Low whatever the app asks, so
         * only this page's switch hides it (owner ask, 2026-10-21); the watcher keeps running hidden.
         */
        fun noticeSettings(context: Context): Intent =
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, Constants.UNLOCK_CHANNEL_ID)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
