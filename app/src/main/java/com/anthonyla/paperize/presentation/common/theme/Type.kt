package com.anthonyla.paperize.presentation.common.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.anthonyla.paperize.R

/**
 * One face, Google Sans Flex (SIL OFL 1.1, bundled in res/font, slimmed to its wght and opsz axes),
 * at two weights: 400 for display, headline and body, 500 for titles and labels. Each size sets the
 * optical-size axis to itself, so large text draws with display spacing and small text stays open.
 * The Material 3 type scale, untracked.
 */
private fun flex(size: Int): FontFamily = FontFamily(
    listOf(Regular, Medium).map { weight ->
        Font(
            resId = R.font.google_sans_flex,
            weight = weight,
            variationSettings = FontVariation.Settings(
                FontVariation.weight(weight.weight),
                FontVariation.Setting("opsz", size.toFloat())
            )
        )
    }
)

private fun style(size: Int, lineHeight: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = flex(size),
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp
)

private val Regular = FontWeight.Normal
private val Medium = FontWeight.Medium

val ExpressiveTypography = Typography(
    displayLarge = style(57, 64, Regular, tracking = -0.25),
    displayMedium = style(45, 52, Regular),
    displaySmall = style(36, 44, Regular),
    headlineLarge = style(32, 40, Regular),
    headlineMedium = style(28, 36, Regular),
    headlineSmall = style(24, 32, Regular),
    titleLarge = style(22, 28, Regular),
    titleMedium = style(16, 24, Medium),
    titleSmall = style(14, 20, Medium),
    bodyLarge = style(16, 24, Regular),
    bodyMedium = style(14, 20, Regular),
    bodySmall = style(12, 16, Regular, tracking = 0.1),
    labelLarge = style(14, 20, Medium),
    labelMedium = style(12, 16, Medium, tracking = 0.1),
    labelSmall = style(11, 16, Medium, tracking = 0.1)
)
