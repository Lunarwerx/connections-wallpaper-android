package com.anthonyla.paperize.presentation.screens.wallpaper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.anthonyla.paperize.domain.model.ScheduleSettings

/**
 * The settings a screen edits, changed at once on every edit. The saved copy lags behind (a slider
 * waits on the view model's debounce), so a switch flipped meanwhile builds on this copy and keeps
 * the slider's value.
 */
@Stable
class ScheduleDraft(
    initial: ScheduleSettings,
    private val save: (ScheduleSettings) -> Unit,
    private val saveDebounced: (ScheduleSettings) -> Unit
) {
    var value by mutableStateOf(initial)
        internal set

    fun update(settings: ScheduleSettings) {
        value = settings
        save(settings)
    }

    fun updateDebounced(settings: ScheduleSettings) {
        value = settings
        saveDebounced(settings)
    }
}

@Composable
fun rememberScheduleDraft(
    persisted: ScheduleSettings,
    save: (ScheduleSettings) -> Unit,
    saveDebounced: (ScheduleSettings) -> Unit
): ScheduleDraft {
    val currentSave by rememberUpdatedState(save)
    val currentSaveDebounced by rememberUpdatedState(saveDebounced)
    val draft = remember { ScheduleDraft(persisted, { currentSave(it) }, { currentSaveDebounced(it) }) }
    LaunchedEffect(persisted) { draft.value = persisted }
    return draft
}
