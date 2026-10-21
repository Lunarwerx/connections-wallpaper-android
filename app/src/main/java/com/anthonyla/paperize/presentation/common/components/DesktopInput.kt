package com.anthonyla.paperize.presentation.common.components

import androidx.compose.foundation.ScrollIndicatorState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.nonInteractiveScrollbar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize

/** True while a mouse or trackpad drives the app; a finger or a pen on the screen turns it off again. */
val LocalMouseInUse = compositionLocalOf { false }

/** Watches every pointer that reaches the app, without taking any, and says whether a mouse is in use. */
@Composable
fun MouseAware(content: @Composable () -> Unit) {
    var mouse by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        when {
                            change.type == PointerType.Mouse || event.type == PointerEventType.Scroll -> mouse = true
                            // Only a pointer that floats over the screen moves without pressing: a trackpad's cursor.
                            event.type == PointerEventType.Move && !change.pressed && change.type != PointerType.Stylus -> mouse = true
                            event.type == PointerEventType.Press -> mouse = false
                        }
                    }
                }
            }
    ) {
        CompositionLocalProvider(LocalMouseInUse provides mouse, content = content)
    }
}

/**
 * A scrollbar on a long list while a mouse or trackpad is in use, so a laptop shows where it is and how much is
 * left; a touch screen keeps its plain list. Put it on the scrolling container, before any inner padding.
 */
@Composable
fun Modifier.mouseScrollbar(state: ScrollIndicatorState?): Modifier =
    if (state != null && LocalMouseInUse.current) {
        nonInteractiveScrollbar(state = state, orientation = Orientation.Vertical, isFadeEnabled = false)
    } else {
        this
    }

/** One line of a tile's menu: an action the tile already offers by tap or long-press. */
class MenuAction(val label: String, val icon: ImageVector? = null, val onClick: () -> Unit)

/**
 * A tile with a menu of its own actions: a right-click opens it where the pointer is, and Shift+F10 or the
 * Menu key opens it on the tile that has the keyboard's focus. A right-click never counts as a tap.
 */
@Composable
fun ContextMenuArea(
    actions: List<MenuAction>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var openAt by remember { mutableStateOf<Offset?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            // Taken before the tile sees it, so the tile does not open as well.
                            event.changes.forEach { it.consume() }
                            openAt = event.changes.first().position
                        }
                    }
                }
            }
            .onKeyEvent { event ->
                val opens = event.key == Key.Menu || (event.key == Key.F10 && event.isShiftPressed)
                if (opens && event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                    openAt = Offset(size.width / 2f, size.height / 2f)
                }
                opens
            }
    ) {
        content()
        val at = openAt ?: Offset.Zero
        DropdownMenu(
            expanded = openAt != null,
            onDismissRequest = { openAt = null },
            // The menu hangs from the tile's bottom edge, so lift it back up to the pointer.
            offset = with(density) { DpOffset(at.x.toDp(), (at.y - size.height).toDp()) }
        ) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    leadingIcon = action.icon?.let { icon -> { Icon(icon, contentDescription = null) } },
                    onClick = {
                        openAt = null
                        action.onClick()
                    }
                )
            }
        }
    }
}
