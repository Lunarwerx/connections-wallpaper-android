package com.anthonyla.paperize.service.worker

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.anthonyla.paperize.connections.analytics.ConnectionsAnalytics
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.service.WallpaperChangeLock
import com.anthonyla.paperize.service.livewallpaper.PaperizeLiveWallpaperService
import com.anthonyla.paperize.service.wallpaper.WallpaperController
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/**
 * Sets a picture from outside the library (a Connections wallpaper) on a screen. In live mode our live wallpaper shows it
 * while it is on the phone, so rotation goes on from it; otherwise it is drawn like the screen's own pictures. Either way
 * the screen's next change waits a whole interval. Expedited work, not the foreground service: a set can finish after a
 * download the person left the app during, when Android refuses to start a foreground service (review, 2026-10-22).
 */
@HiltWorker
class ApplyPictureWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val wallpaperController: WallpaperController,
    private val wallpaperScheduler: WallpaperScheduler,
    private val settingsRepository: SettingsRepository,
    private val wallpaperChangeLock: WallpaperChangeLock,
    private val analytics: ConnectionsAnalytics
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val uri = inputData.getString(Constants.EXTRA_PICTURE_URI) ?: return Result.failure()
        val screen = inputData.getString(Constants.EXTRA_SCREEN_TYPE)?.let(ScreenType::fromString) ?: ScreenType.BOTH
        return try {
            wallpaperChangeLock.mutex.withLock { apply(uri, screen) }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "The picture could not be set", e)
            Result.failure(workDataOf(KEY_ERROR to e.localizedMessage))
        }
    }

    private suspend fun apply(uri: String, screen: ScreenType) {
        analytics.hintTrigger("wallpaper_view")
        val mode = settingsRepository.getWallpaperMode()
        val settings = settingsRepository.getScheduleSettings()
        val showing = PaperizeLiveWallpaperService.showingOn(context)
        if (mode == WallpaperMode.LIVE && screen != ScreenType.LOCK && ScreenType.HOME in showing) {
            settingsRepository.setShownPicture(listOf(ScreenType.LIVE), uri)
            context.sendBroadcast(
                Intent(Constants.ACTION_SHOW_PICTURE).setPackage(context.packageName).putExtra(Constants.EXTRA_PICTURE_URI, uri)
            )
            // A lock screen holding a picture of its own does not show the live wallpaper.
            if (screen == ScreenType.BOTH && ScreenType.LOCK !in showing &&
                WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_LOCK) > 0
            ) {
                wallpaperController.applyPicture(uri, ScreenType.LOCK, settings, mode)
            }
            wallpaperScheduler.resetAfterManualChange(ScreenType.LIVE, settings, mode)
            return
        }
        wallpaperController.applyPicture(uri, screen, settings, mode)
        wallpaperScheduler.resetAfterManualChange(screen, settings, mode)
    }

    companion object {
        private const val TAG = "ApplyPictureWorker"
        private const val KEY_ERROR = "error"

        /**
         * Sets the picture at [uri] on [screen] (HOME, LOCK or BOTH), drawn and rotated on from like our own. Returns once
         * it is on the phone and throws, with the reason when there is one, when it could not be set: a caller that says
         * it was set says so truthfully (review, 2026-10-22). Leaving the screen stops only the wait, never the set.
         */
        suspend fun set(context: Context, uri: String, screen: ScreenType) {
            val request = OneTimeWorkRequestBuilder<ApplyPictureWorker>()
                .setInputData(workDataOf(Constants.EXTRA_PICTURE_URI to uri, Constants.EXTRA_SCREEN_TYPE to screen.name))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            val manager = WorkManager.getInstance(context)
            manager.enqueue(request)
            val info = manager.getWorkInfoByIdFlow(request.id).first { it == null || it.state.isFinished }
            if (info?.state != WorkInfo.State.SUCCEEDED) throw IllegalStateException(info?.outputData?.getString(KEY_ERROR))
        }
    }
}
