package com.anthonyla.paperize.presentation.common.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PaperizeTheme(
    darkMode: Boolean?,
    dynamicTheming: Boolean,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val isDarkMode = darkMode ?: isSystemInDarkTheme()

    val colors = when {
        dynamicTheming && isDarkMode -> dynamicDarkColorScheme(context)
        dynamicTheming && !isDarkMode -> dynamicLightColorScheme(context)
        isDarkMode -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDarkMode
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !isDarkMode
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        typography = ExpressiveTypography,
        shapes = ExpressiveShapes,
        content = content
    )
}

/** The photo viewer is dark in either theme, the way a gallery viewer is: the picture is the light. */
@Composable
fun ViewerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content
    )
}
