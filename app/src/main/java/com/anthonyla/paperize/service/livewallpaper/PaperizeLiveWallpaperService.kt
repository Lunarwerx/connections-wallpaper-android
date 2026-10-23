package com.anthonyla.paperize.service.livewallpaper

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.anthonyla.paperize.R
import com.anthonyla.paperize.connections.analytics.ConnectionsAnalytics
import com.anthonyla.paperize.core.EmptyAlbumException
import com.anthonyla.paperize.core.ScalingType
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.core.util.canOpen
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.domain.model.WallpaperEffects
import com.anthonyla.paperize.domain.model.usesVisibleLiveTimer
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.usecase.ChangeWallpaperUseCase
import com.anthonyla.paperize.service.WallpaperWork
import com.anthonyla.paperize.service.livewallpaper.gl.GLWallpaperService
import com.anthonyla.paperize.service.livewallpaper.renderer.ContentUriImageLoader
import com.anthonyla.paperize.service.livewallpaper.renderer.EmptyImageLoader
import com.anthonyla.paperize.service.livewallpaper.renderer.OpenedArtwork
import com.anthonyla.paperize.service.livewallpaper.renderer.PaperizeRenderController
import com.anthonyla.paperize.service.livewallpaper.renderer.PaperizeWallpaperRenderer
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class PaperizeLiveWallpaperService : GLWallpaperService() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var changeWallpaperUseCase: ChangeWallpaperUseCase
    @Inject lateinit var analytics: ConnectionsAnalytics
    @Inject lateinit var work: WallpaperWork

    companion object {
        private const val TAG = "PaperizeLiveWallpaper"

        // Their stock launchers often do not report wallpaper scroll offsets.
        private val PARALLAX_ISSUE_MANUFACTURERS =
            setOf("SAMSUNG", "XIAOMI", "HUAWEI", "OPPO", "VIVO", "REALME", "ONEPLUS")

        private fun launcherMayIgnoreParallax(): Boolean =
            Build.MANUFACTURER.uppercase().let { maker -> PARALLAX_ISSUE_MANUFACTURERS.any(maker::contains) }

        fun isActive(context: Context): Boolean {
            // Checking from within the service itself (e.g. preview mode) means it is active.
            if (context is PaperizeLiveWallpaperService) return true
            return try {
                WallpaperManager.getInstance(context).wallpaperInfo?.component ==
                    ComponentName(context, PaperizeLiveWallpaperService::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking live wallpaper status", e)
                false
            }
        }

        /** The screens showing Paperize's live wallpaper: home, and since Android 14, a lock screen that holds it itself. */
        fun showingOn(context: Context): Set<ScreenType> {
            val ours = ComponentName(context, PaperizeLiveWallpaperService::class.java)
            return try {
                val manager = WallpaperManager.getInstance(context)
                buildSet {
                    if (manager.wallpaperInfo?.component == ours) add(ScreenType.HOME)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                        manager.getWallpaperInfo(WallpaperManager.FLAG_LOCK)?.component == ours
                    ) add(ScreenType.LOCK)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking where the live wallpaper shows", e)
                emptySet()
            }
        }
    }

    override fun onCreateEngine(): Engine {
        return PaperizeLiveWallpaperEngine()
    }

    inner class PaperizeLiveWallpaperEngine : GLEngine(),
        PaperizeWallpaperRenderer.Callbacks {

        private lateinit var renderer: PaperizeWallpaperRenderer
        private lateinit var renderController: PaperizeRenderController
        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var currentAlbumId: String? = null
        @Volatile private var currentWallpaper: Wallpaper? = null
        private var observedScalingType: ScalingType? = null
        private var observedLook: Triple<ScalingType, WallpaperEffects, Boolean>? = null
        private var hasShownParallaxWarning = false
        private var engineVisible = false
        private var latestSettings = ScheduleSettings()
        private var latestWallpaperMode = WallpaperMode.STATIC
        private var liveIntervalJob: Job? = null

        private val gestureDetector = GestureDetector(
            this@PaperizeLiveWallpaperService,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    handleDoubleTap()
                    return true
                }
            }
        )

        private val reloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Constants.ACTION_RELOAD_WALLPAPER -> {
                        Log.d(TAG, "Received reload broadcast")
                        // Asked for in the app, whose window hides the wallpaper: waiting to be seen left the app showing the
                        // same picture after Change wallpaper now (owner report, 2026-10-22), so it opens the next one now.
                        if (intent.getBooleanExtra(Constants.EXTRA_NOW, false) && !engineVisible) {
                            renderController.forceReloadCurrentArtwork()
                        } else {
                            renderController.reloadCurrentArtwork(immediate = true)
                        }
                        restartLiveIntervalTimer()
                    }
                    Constants.ACTION_SHOW_PICTURE -> intent.getStringExtra(Constants.EXTRA_PICTURE_URI)?.let(::showPicture)
                }
            }
        }

        private val screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    handleScreenOff()
                }
            }
        }

        // The engine draws on request, so a dark/light switch only reaches the picture through this.
        private val configurationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_CONFIGURATION_CHANGED) {
                    renderer.updateNightMode()
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            Log.d(TAG, "Engine created")

            setOffsetNotificationsEnabled(true)

            setTouchEventsEnabled(true)

            renderer = PaperizeWallpaperRenderer(applicationContext, this)
            renderController = PaperizeRenderController(renderer::queueWallpaper, engineScope) {
                withContext(Dispatchers.IO) { openNextWallpaper() }
            }

            setRenderer(renderer)
            requestRender()

            renderController.reloadCurrentArtwork(immediate = true)

            observeSettings()

            val filter = IntentFilter(Constants.ACTION_RELOAD_WALLPAPER).apply { addAction(Constants.ACTION_SHOW_PICTURE) }
            ContextCompat.registerReceiver(
                applicationContext,
                reloadReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            val screenOffFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            ContextCompat.registerReceiver(
                applicationContext,
                screenOffReceiver,
                screenOffFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            ContextCompat.registerReceiver(
                applicationContext,
                configurationReceiver,
                IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        /** Dequeue the next readable live wallpaper. Queue and database errors reach the controller. */
        private suspend fun openNextWallpaper(): OpenedArtwork {
            val empty = OpenedArtwork(EmptyImageLoader)
            // In STATIC mode the static wallpaper worker owns HOME/LOCK; the engine stays empty.
            if (settingsRepository.getWallpaperMode() != WallpaperMode.LIVE) return empty
            val settings = settingsRepository.getScheduleSettings()
            val albumId = settings.liveAlbumId ?: return empty

            repeat(Constants.MAX_WALLPAPER_LOAD_RETRIES) {
                val candidate = try {
                    changeWallpaperUseCase.dequeueNext(albumId, ScreenType.LIVE, settings.shuffleEnabled)
                } catch (_: EmptyAlbumException) {
                    Log.w(TAG, "No wallpapers in album $albumId")
                    return empty
                }
                // Skip unreadable items without deleting them: a permission or storage issue, or no internet for a
                // Library picture the phone does not hold yet, may be transient. AlbumRefreshWorker prunes documents
                // that are actually gone. Opening it here is also what brings a Library picture to the phone.
                if (candidate.uri.toUri().canOpen(contentResolver)) {
                    return OpenedArtwork(ContentUriImageLoader(contentResolver, candidate.uri.toUri(), settings.liveScalingType)) {
                        currentWallpaper = candidate
                        // Recorded as the album's current picture, as a static change records its own, so the app shows
                        // what the live wallpaper shows: it showed a stale one (owner report, 2026-10-22). A picture set
                        // from outside the library stops being the one shown.
                        withContext(Dispatchers.IO) {
                            changeWallpaperUseCase.completeSpecific(albumId, ScreenType.LIVE, candidate.id, settings.shuffleEnabled)
                        }
                        analytics.liveWallpaperShown(albumId, candidate)
                    }
                }
            }
            Log.w(TAG, "No valid wallpaper found after retries")
            return empty
        }

        /** A picture from outside the library (a Connections wallpaper), shown until the album's next one; the interval starts again from it. */
        private fun showPicture(uri: String) {
            val picture = Wallpaper(id = uri, albumId = currentAlbumId.orEmpty(), uri = uri, fileName = uri.substringAfterLast('/'), dateModified = 0)
            currentWallpaper = picture
            renderController.show(ContentUriImageLoader(contentResolver, uri.toUri(), latestSettings.liveScalingType))
            restartLiveIntervalTimer()
        }

        private fun observeSettings() {
            engineScope.launch {
                combine(
                    settingsRepository.getScheduleSettingsFlow(),
                    settingsRepository.getWallpaperModeFlow()
                ) { settings, mode ->
                    Pair(settings, mode)
                }.catch { e ->
                    Log.e(TAG, "Error observing settings", e)
                }.collect { (settings, mode) ->
                    val timerConfigurationChanged =
                        latestWallpaperMode != mode ||
                            latestSettings.enableChanger != settings.enableChanger ||
                            latestSettings.liveAlbumId != settings.liveAlbumId ||
                            latestSettings.liveIntervalMinutes != settings.liveIntervalMinutes ||
                            latestSettings.intervalEnabled != settings.intervalEnabled
                    latestSettings = settings
                    latestWallpaperMode = mode
                    if (timerConfigurationChanged) restartLiveIntervalTimer()

                    if (mode != WallpaperMode.LIVE) {
                        return@collect
                    }
                    
                    val albumId = settings.liveAlbumId
                    val effects = settings.appliedEffects(ScreenType.LIVE)
                    val scalingType = settings.liveScalingType

                    val albumChanged = albumId != currentAlbumId
                    if (albumChanged) {
                        currentWallpaper = null
                    }

                    renderer.updateEffects(effects)
                    renderer.updateScalingType(scalingType)
                    renderer.updateAdaptiveBrightness(settings.appliesAdaptiveBrightness)

                    val scalingChanged =
                        observedScalingType != null && observedScalingType != scalingType
                    observedScalingType = scalingType

                    if (scalingChanged && !albumChanged) {
                        currentWallpaper?.let { wallpaper ->
                            Log.d(TAG, "Live scaling changed; reloading current wallpaper without advancing")
                            renderer.queueWallpaper(
                                ContentUriImageLoader(
                                    contentResolver,
                                    wallpaper.uri.toUri(),
                                    scalingType
                                )
                            )
                        }
                    }
                    
                    // A look edit takes effect here, with nothing for the app to wait on; counting it lets the app say
                    // Applied (owner report, 2026-10-22: a scaling change gave no sign it reached the phone).
                    val drawn = effects.copy(enableDoubleTap = false, enableParallax = false, parallaxIntensity = 0)
                    val look = Triple(scalingType, drawn, settings.appliesAdaptiveBrightness)
                    if (observedLook != null && observedLook != look) work.liveLookApplied()
                    observedLook = look

                    if (effects.enableParallax && !hasShownParallaxWarning && launcherMayIgnoreParallax()) {
                        hasShownParallaxWarning = true
                        Toast.makeText(
                            applicationContext,
                            R.string.parallax_may_not_work,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    
                    if (albumChanged) {
                        Log.d(TAG, "Album changed from $currentAlbumId to $albumId, reloading")
                        currentAlbumId = albumId
                        renderController.reloadCurrentArtwork()
                    }
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            engineVisible = visible
            renderController.visible = visible
            if (visible) {
                // Re-evaluate draw-time effects such as adaptive brightness after
                // configuration changes while the wallpaper was hidden.
                requestRender()
            }
            restartLiveIntervalTimer()
            super.onVisibilityChanged(visible)
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
            renderer.setNormalOffsetX(xOffset)
        }

        override fun onTouchEvent(event: MotionEvent) {
            try {
                gestureDetector.onTouchEvent(event)
            } catch (e: Exception) {
                Log.w(TAG, "Error processing touch event", e)
            }
            super.onTouchEvent(event)
        }

        override fun onDestroy() {
            try {
                applicationContext.unregisterReceiver(reloadReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering reload receiver", e)
            }
            try {
                applicationContext.unregisterReceiver(screenOffReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering screen-off receiver", e)
            }
            try {
                applicationContext.unregisterReceiver(configurationReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering configuration receiver", e)
            }

            renderer.cancelLoading()

            queueEvent {
                renderer.destroy()
            }

            engineScope.cancel()
            super.onDestroy()
        }

        override fun queueEventOnGlThread(event: () -> Unit): Boolean = queueEvent(event)

        private fun handleDoubleTap() {
            engineScope.launch {
                val settings = settingsRepository.getScheduleSettings()
                
                val doubleTapEnabled = settings.liveEffects.enableDoubleTap

                if (doubleTapEnabled) {
                    renderController.reloadCurrentArtwork(immediate = true)
                    restartLiveIntervalTimer()
                }
            }
        }

        private fun handleScreenOff() {
            engineScope.launch {
                val settings = settingsRepository.getScheduleSettings()
                if (settings.enableChanger && settings.changeOnUnlock) {
                    Log.d(TAG, "Screen off - changing wallpaper")
                    renderController.forceReloadCurrentArtwork()
                }
            }
        }

        private fun restartLiveIntervalTimer() {
            liveIntervalJob?.cancel()
            liveIntervalJob = null

            val intervalMinutes = latestSettings.liveIntervalMinutes
            val shouldRun =
                engineVisible &&
                    !isPreview &&
                    latestWallpaperMode == WallpaperMode.LIVE &&
                    latestSettings.enableChanger &&
                    latestSettings.intervalEnabled &&
                    latestSettings.liveAlbumId != null &&
                    usesVisibleLiveTimer(intervalMinutes)
            if (!shouldRun) return

            liveIntervalJob = engineScope.launch {
                val intervalMillis = intervalMinutes.toLong() * 60_000L
                while (isActive) {
                    delay(intervalMillis)
                    Log.d(TAG, "Visible live interval elapsed; changing wallpaper")
                    renderController.reloadCurrentArtwork(
                        immediate = true
                    )
                }
            }
        }
    }
}
