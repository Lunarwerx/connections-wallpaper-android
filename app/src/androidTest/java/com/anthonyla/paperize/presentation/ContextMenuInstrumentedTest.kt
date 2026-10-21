package com.anthonyla.paperize.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.anthonyla.paperize.presentation.common.components.ContextMenuArea
import com.anthonyla.paperize.presentation.common.components.MenuAction
import com.anthonyla.paperize.presentation.common.theme.PaperizeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** A tile's menu on a laptop: right-click or Shift+F10 opens it, and a right-click is never also a tap. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class ContextMenuInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private var taps = 0
    private var opens = 0
    private lateinit var inputMode: InputModeManager

    private fun tile() = compose.setContent {
        inputMode = LocalInputModeManager.current
        PaperizeTheme(false, false) {
            ContextMenuArea(actions = listOf(MenuAction("Open") { opens++ })) {
                Box(Modifier.size(200.dp).testTag("tile").clickable { taps++ })
            }
        }
    }

    @Test fun rightClickOpensTheTilesMenuWithoutTappingItAndALeftClickStillTaps() {
        tile()
        compose.onNodeWithTag("tile").performMouseInput { rightClick(center) }
        compose.onNodeWithText("Open").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, opens)
            assertEquals(0, taps)
        }
        compose.onNodeWithText("Open").assertDoesNotExist()

        compose.onNodeWithTag("tile").performMouseInput { click(center) }
        compose.runOnIdle { assertEquals(1, taps) }
        compose.onNodeWithText("Open").assertDoesNotExist()
    }

    @Test fun shiftF10OpensTheMenuOfTheTileWithFocus() {
        tile()
        // A tile takes focus only once a keyboard is in use, as after the Tab that reaches it on a laptop.
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.onNodeWithTag("tile").requestFocus().assertIsFocused()
        compose.onNodeWithTag("tile").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) } }
        compose.onNodeWithText("Open").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, taps) }
    }
}
