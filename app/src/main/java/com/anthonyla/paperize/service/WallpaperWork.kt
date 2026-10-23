package com.anthonyla.paperize.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Wallpaper work a screen started and is still waiting on, and when it last finished, for that screen to show. Finished
 * is a time ([now]), not a count a screen compares with what it saw: a static wallpaper set re-themes every app from its
 * colours, which recreates the screen, and a recreated screen must still say done (round six, 2026-10-22).
 */
@Singleton
class WallpaperWork @Inject constructor() {
    private val reapplies = MutableStateFlow(0)
    private val changes = MutableStateFlow(0)
    private val looksDone = MutableStateFlow(0L)
    private val changesDone = MutableStateFlow(0L)

    /** True from a look edit (scaling, an effect) until the redrawn wallpaper is on the phone. */
    val reapplying: Flow<Boolean> = reapplies.busy()

    /** True from Change wallpaper now until the next picture is on the phone. */
    val changing: Flow<Boolean> = changes.busy()

    /** When ([now]) a look edit last reached the phone, 0 before the first: a redrawn static wallpaper, or our live wallpaper taking the new look. */
    val looksApplied: StateFlow<Long> = looksDone.asStateFlow()

    /** When ([now]) a Change wallpaper now tap last put its next picture up, 0 before the first. */
    val changesApplied: StateFlow<Long> = changesDone.asStateFlow()

    fun reapplyStarted() = reapplies.update { it + 1 }

    fun reapplyDone(applied: Boolean) {
        // Marked before running ends, so a screen never sees neither.
        if (applied) looksDone.value = now()
        reapplies.update { (it - 1).coerceAtLeast(0) }
    }

    /** Our live wallpaper drew with a new look, which no service run redraws. */
    fun liveLookApplied() {
        looksDone.value = now()
    }

    fun changeStarted() = changes.update { it + 1 }

    fun changeDone(changed: Boolean) {
        if (changed) changesDone.value = now()
        changes.update { (it - 1).coerceAtLeast(0) }
    }

    private fun MutableStateFlow<Int>.busy(): Flow<Boolean> = map { it > 0 }.distinctUntilChanged()

    companion object {
        /** Milliseconds on a clock that only moves forward, for this process: when work finished, and how long ago. */
        fun now(): Long = System.nanoTime() / 1_000_000
    }
}
