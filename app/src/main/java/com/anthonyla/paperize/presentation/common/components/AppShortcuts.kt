package com.anthonyla.paperize.presentation.common.components

import android.content.Context
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import com.anthonyla.paperize.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter

/** The app's keyboard shortcuts, each wired to an action a screen already has. */
enum class AppShortcut(val keyCode: Int, val ctrl: Boolean, val label: Int) {
    SEARCH(KeyEvent.KEYCODE_F, ctrl = true, R.string.shortcut_search),
    SELECT_ALL(KeyEvent.KEYCODE_A, ctrl = true, R.string.shortcut_select_all),
    DELETE(KeyEvent.KEYCODE_FORWARD_DEL, ctrl = false, R.string.shortcut_delete);

    companion object {
        /** The shortcut a key press is, if any; Alt, Shift and Meta combinations are left to the system. */
        fun of(event: KeyEvent): AppShortcut? = entries.firstOrNull { shortcut ->
            event.keyCode == shortcut.keyCode &&
                event.isCtrlPressed == shortcut.ctrl &&
                !event.isAltPressed && !event.isShiftPressed && !event.isMetaPressed
        }
    }
}

/**
 * Hands the activity's unclaimed key presses to whichever screen listens. A key a focused field or control
 * uses itself (Ctrl+A or Delete while typing) never gets here.
 */
class AppShortcuts {
    private val presses = MutableSharedFlow<AppShortcut>(extraBufferCapacity = 1)
    val events: SharedFlow<AppShortcut> = presses

    /** True when the key is one of ours; the press, not its release or repeats, reaches the screens. */
    fun dispatch(event: KeyEvent): Boolean {
        val shortcut = AppShortcut.of(event) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) presses.tryEmit(shortcut)
        return true
    }

    companion object {
        /** What the system's shortcut list (Meta+/) shows for this app, the keys that already work included. */
        fun helperGroup(context: Context): KeyboardShortcutGroup {
            fun info(label: Int, keyCode: Int, modifiers: Int = 0) =
                KeyboardShortcutInfo(context.getString(label), keyCode, modifiers)
            return KeyboardShortcutGroup(
                context.getString(R.string.app_name),
                AppShortcut.entries.map { info(it.label, it.keyCode, if (it.ctrl) KeyEvent.META_CTRL_ON else 0) } + listOf(
                    info(R.string.shortcut_menu, KeyEvent.KEYCODE_F10, KeyEvent.META_SHIFT_ON),
                    info(R.string.shortcut_previous, KeyEvent.KEYCODE_DPAD_LEFT),
                    info(R.string.shortcut_next, KeyEvent.KEYCODE_DPAD_RIGHT),
                    info(R.string.shortcut_back, KeyEvent.KEYCODE_ESCAPE)
                )
            )
        }
    }
}

val LocalAppShortcuts = staticCompositionLocalOf<SharedFlow<AppShortcut>> { MutableSharedFlow() }

/** Runs [action] each time [shortcut] is pressed while this is on screen and [enabled]. */
@Composable
fun OnShortcut(shortcut: AppShortcut, enabled: Boolean = true, action: () -> Unit) {
    val events = LocalAppShortcuts.current
    val current by rememberUpdatedState(action)
    LaunchedEffect(events, shortcut, enabled) {
        (if (enabled) events else emptyFlow()).filter { it == shortcut }.collect { current() }
    }
}
