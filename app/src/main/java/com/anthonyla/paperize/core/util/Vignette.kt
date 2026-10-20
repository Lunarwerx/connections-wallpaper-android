package com.anthonyla.paperize.core.util

import com.anthonyla.paperize.core.constants.Constants
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val SQRT_2 = 1.4142135f

/**
 * How dark the vignette makes the pixel at ([x], [y]) of a [width] x [height] frame, 0..1.
 *
 * The band hugs the four edges, measured in pixels to the nearest edge and sized from the short side,
 * so it is equally thick on every side whatever the aspect ratio. Inside the band the darkness eases
 * in with a smoothstep; the corners are darkest. [percent] is the strength, 0..100.
 */
fun vignetteDarkness(x: Float, y: Float, width: Float, height: Float, percent: Int): Float {
    val p = percent.coerceIn(0, 100) / 100f
    val band = min(width, height) * (Constants.VIGNETTE_BAND_BASE + Constants.VIGNETTE_BAND_RANGE * p)
    if (band <= 0f) return 0f
    val ex = max(0f, band - min(x, width - x)) / band
    val ey = max(0f, band - min(y, height - y)) / band
    // Divided by sqrt(2) so the corner, not the edge midpoint, reaches the full falloff.
    val t = (sqrt(ex * ex + ey * ey) / SQRT_2).coerceIn(0f, 1f)
    val maxDark = Constants.VIGNETTE_MAX_DARK_BASE + Constants.VIGNETTE_MAX_DARK_RANGE * p
    return maxDark * t * t * (3f - 2f * t)
}
