package com.anthonyla.paperize.presentation.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.domain.model.Album
import com.anthonyla.paperize.domain.model.AlbumSummary
import com.anthonyla.paperize.domain.model.AppSettings
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.repository.AlbumRepository
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import com.anthonyla.paperize.domain.usecase.ImportWallpapersUseCase
import com.anthonyla.paperize.presentation.common.stateWhileSubscribed
import com.anthonyla.paperize.presentation.screens.album_view.ImportProgress
import com.anthonyla.paperize.service.SettingsCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    albumRepository: AlbumRepository,
    private val settingsRepository: SettingsRepository,
    private val wallpaperRepository: WallpaperRepository,
    private val importWallpapersUseCase: ImportWallpapersUseCase,
    private val coordinator: SettingsCoordinator
) : ViewModel() {

    val albums: StateFlow<List<AlbumSummary>> =
        albumRepository.getAlbumSummaries().stateWhileSubscribed(viewModelScope, emptyList())

    val scheduleSettings: StateFlow<ScheduleSettings> =
        settingsRepository.getScheduleSettingsFlow().stateWhileSubscribed(viewModelScope, ScheduleSettings())

    val appSettings: StateFlow<AppSettings> =
        settingsRepository.getAppSettingsFlow().stateWhileSubscribed(viewModelScope, AppSettings())

    val wallpaperMode: StateFlow<WallpaperMode?> =
        settingsRepository.getWallpaperModeFlow().stateWhileSubscribed(viewModelScope, null)

    /**
     * URI of the wallpaper Paperize last applied for the home / lock screen, for the in-app preview.
     * Reading the source URI (rather than WallpaperManager.getDrawable) avoids the storage permission
     * the preview would otherwise need. Falls back to the BOTH-mode record when home/lock are synced.
     */
    val currentHomeWallpaperUri: StateFlow<String?> = currentWallpaperUri(ScreenType.HOME) { it.homeAlbumIds }

    val currentLockWallpaperUri: StateFlow<String?> = currentWallpaperUri(ScreenType.LOCK) { it.lockAlbumIds }

    val currentLiveWallpaperUri: StateFlow<String?> = currentWallpaperUri(ScreenType.LIVE) { it.liveAlbumIds }

    /** True from a look edit until its redrawn wallpaper is on the phone. */
    val applyingLook: StateFlow<Boolean> = coordinator.reapplying.stateWhileSubscribed(viewModelScope, false)

    /** True from Change wallpaper now until the next picture is on the phone. */
    val changingNow: StateFlow<Boolean> = coordinator.changing.stateWhileSubscribed(viewModelScope, false)

    /** How many look edits reached the phone; each new one shows Applied. */
    val looksApplied: StateFlow<Long> = coordinator.looksApplied

    /** How many Change wallpaper now taps put their picture up; each new one shows Changed. */
    val changesApplied: StateFlow<Long> = coordinator.changesApplied

    private val _showLiveWallpaperPrompt = MutableStateFlow(false)
    val showLiveWallpaperPrompt: StateFlow<Boolean> = _showLiveWallpaperPrompt.asStateFlow()

    private val _importProgress = MutableStateFlow<ImportProgress>(ImportProgress.Idle)
    val importProgress: StateFlow<ImportProgress> = _importProgress.asStateFlow()

    private val _importError = MutableStateFlow<Int?>(null)
    val importError: StateFlow<Int?> = _importError.asStateFlow()

    private val _albumAdded = MutableStateFlow(false)
    /** A folder or photos just became an album; the screen reads it once and calls [consumeAlbumAdded]. */
    val albumAdded: StateFlow<Boolean> = _albumAdded.asStateFlow()

    private var pendingSettingsJob: Job? = null
    private var pendingSettings: ScheduleSettings? = null
    private var importJob: Job? = null

    init {
        // Launched in order so later edits queue behind this check in the coordinator.
        viewModelScope.launch { coordinator.matchPhoneWallpaper() }
    }

    fun dismissLiveWallpaperPrompt() {
        _showLiveWallpaperPrompt.value = false
    }

    /** A new album named after the folder, placed where [screen]'s picker asked (else every empty slot). */
    fun addFolderAlbum(uri: String, screen: ScreenType? = null) = import(ImportProgress.Scanning(0), screen) {
        importWallpapersUseCase.createAlbumFromFolder(
            uri,
            onScanning = { _importProgress.value = ImportProgress.Scanning(it) },
            onSaving = { saved, total -> _importProgress.value = ImportProgress.Saving(saved, total) }
        )
    }

    fun addPhotosAlbum(uris: List<String>, name: String) = import(ImportProgress.Saving(0, uris.size), null) {
        importWallpapersUseCase.createAlbumFromImages(uris, name) { saved, total ->
            _importProgress.value = ImportProgress.Saving(saved, total)
        }
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    fun dismissImportError() {
        _importError.value = null
    }

    fun consumeAlbumAdded() {
        _albumAdded.value = false
    }

    private fun import(start: ImportProgress, screen: ScreenType?, create: suspend () -> Album) {
        if (importJob?.isActive == true) return
        _importError.value = null
        _importProgress.value = start
        importJob = viewModelScope.launch {
            try {
                val album = create()
                if (coordinator.adoptNewAlbum(album.id, screen)) _showLiveWallpaperPrompt.value = true
                _albumAdded.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                Log.e(TAG, "No permission for the chosen pictures", e)
                _importError.value = R.string.import_permission_error
            } catch (e: Exception) {
                Log.e(TAG, "Could not add the album", e)
                _importError.value = R.string.import_failed
            }
        }.also { job -> job.invokeOnCompletion { _importProgress.value = ImportProgress.Idle } }
    }

    fun setAlbums(screen: ScreenType, albumIds: List<String>) {
        viewModelScope.launch { coordinator.setAlbums(screen, albumIds) }
    }

    fun setChangerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (coordinator.setChangerEnabled(enabled)) _showLiveWallpaperPrompt.value = true
        }
    }

    fun updateScheduleSettings(settings: ScheduleSettings) {
        pendingSettingsJob?.cancel()
        pendingSettingsJob = null
        pendingSettings = null
        viewModelScope.launch { coordinator.updateScheduleSettings(settings) }
    }

    fun updateScheduleSettingsDebounced(settings: ScheduleSettings) {
        pendingSettingsJob?.cancel()
        pendingSettings = settings
        pendingSettingsJob = viewModelScope.launch {
            delay(Constants.SETTINGS_DEBOUNCE_MS)
            pendingSettingsJob = null
            pendingSettings = null
            coordinator.updateScheduleSettings(settings)
        }
    }

    /** Save an edit still waiting on its debounce now, for a page that closes before the wait ends. */
    fun flushPendingSettings() {
        pendingSettings?.let(::updateScheduleSettings)
    }

    fun changeWallpaperNow() {
        viewModelScope.launch { if (coordinator.changeWallpaperNow()) _showLiveWallpaperPrompt.value = true }
    }

    /** A picture set from outside the library while it shows, else the most recent one of the screen's albums. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun currentWallpaperUri(screen: ScreenType, albumIds: (ScheduleSettings) -> List<String>): StateFlow<String?> =
        combine(
            settingsRepository.getShownPictureFlow(screen),
            scheduleSettings
                .map(albumIds)
                .distinctUntilChanged()
                .flatMapLatest { ids -> if (ids.isEmpty()) flowOf(null) else currentWallpaperUriFlow(ids, screen) }
        ) { shown, current -> shown ?: current }
            .stateWhileSubscribed(viewModelScope, null)

    private fun currentWallpaperUriFlow(albumIds: List<String>, screen: ScreenType): Flow<String?> = combine(
        wallpaperRepository.getCurrentWallpaperFlow(albumIds, screen),
        wallpaperRepository.getCurrentWallpaperFlow(albumIds, ScreenType.BOTH)
    ) { specific, both -> (specific ?: both)?.uri }

    private companion object {
        const val TAG = "HomeViewModel"
    }
}
