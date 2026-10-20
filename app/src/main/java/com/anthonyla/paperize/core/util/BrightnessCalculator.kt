package com.anthonyla.paperize.core.util

import android.graphics.Bitmap
import com.anthonyla.paperize.core.constants.Constants
import kotlin.math.max

object BrightnessCalculator {

    /** Estimates luminance with BT.709 coefficients; channels remain in their encoded color space. */
    private fun calculateLuminance(pixel: Int): Double {
        val r = ((pixel shr 16) and 0xFF) / 255.0
        val g = ((pixel shr 8) and 0xFF) / 255.0
        val b = (pixel and 0xFF) / 255.0

        return Constants.LUMINANCE_RED * r + Constants.LUMINANCE_GREEN * g + Constants.LUMINANCE_BLUE * b
    }

    /** Samples rows in bulk to avoid a JNI call for every pixel. Returns brightness in 0..1. */
    fun calculateBitmapBrightness(bitmap: Bitmap): Float {
        val sampleSize = Constants.BRIGHTNESS_SAMPLE_SIZE
        val w = bitmap.width
        val h = bitmap.height

        val rowBuffer = IntArray(w)
        var totalLuminance = 0.0
        var pixelCount = 0

        for (y in 0 until h step sampleSize) {
            bitmap.getPixels(rowBuffer, 0, w, 0, y, w, 1)
            for (x in 0 until w step sampleSize) {
                totalLuminance += calculateLuminance(rowBuffer[x])
                pixelCount++
            }
        }

        return (totalLuminance / pixelCount).toFloat()
    }

    /**
     * Brightness multiplier for a picture of average luminance [averageLuminance] (0..1). In dark mode
     * the picture is dimmed to a target luminance, by at least 25%; light mode leaves it as it is.
     */
    fun getAdaptiveMultiplier(isNight: Boolean, averageLuminance: Float): Float {
        if (!isNight) return 1f
        // Floor on the luminance so a black picture divides safely; the clamp keeps it from brightening.
        val multiplier = Constants.DARK_MODE_TARGET_LUMINANCE / max(averageLuminance, 0.01f)
        return multiplier.coerceIn(Constants.DARK_MODE_MIN_MULTIPLIER, Constants.DARK_MODE_MAX_MULTIPLIER)
    }
}
