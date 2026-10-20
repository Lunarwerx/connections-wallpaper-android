package com.anthonyla.paperize.presentation.screens.settings

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.domain.model.AppSettings
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.presentation.common.stateWhileSubscribed
import com.anthonyla.paperize.service.SettingsCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val coordinator: SettingsCoordinator
) : ViewModel() {

    // Eager so the first frame already knows whether to show onboarding.
    val appSettings: StateFlow<AppSettings?> =
        settingsRepository.getAppSettingsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val wallpaperMode: StateFlow<WallpaperMode> =
        settingsRepository.getWallpaperModeFlow().stateWhileSubscribed(viewModelScope, WallpaperMode.STATIC)

    private val _isBusy = MutableStateFlow(false)
    val isBusy = _isBusy.asStateFlow()

    /** What the last mode switch or reset could not finish, as a message, or null. */
    private val _failure = MutableStateFlow<Int?>(null)
    val failure = _failure.asStateFlow()

    /** null follows the phone's theme; true or false pins dark or light. */
    fun updateDarkMode(dark: Boolean?) {
        viewModelScope.launch { settingsRepository.updateDarkMode(dark) }
    }

    fun updateDynamicTheming(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateDynamicTheming(enabled) }
    }

    fun updateAnimate(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateAnimate(enabled) }
    }

    fun updateFirstLaunch(isFirstLaunch: Boolean) {
        viewModelScope.launch { settingsRepository.updateFirstLaunch(isFirstLaunch) }
    }

    /** Albums and settings stay; [onSwitched] hears whether live mode now needs its live wallpaper picked. */
    fun switchWallpaperMode(newMode: WallpaperMode, onSwitched: (needsLivePicker: Boolean) -> Unit = {}) =
        perform(R.string.mode_switch_failed) { onSwitched(coordinator.switchMode(newMode)) }

    fun resetAllData() = perform(R.string.reset_failed) { coordinator.resetData() }

    private fun perform(@StringRes failureMessage: Int, action: suspend () -> Unit) {
        if (_isBusy.value) return
        _isBusy.value = true
        _failure.value = null
        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not finish a mode switch or reset", e)
                _failure.value = failureMessage
            } finally {
                _isBusy.value = false
            }
        }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
