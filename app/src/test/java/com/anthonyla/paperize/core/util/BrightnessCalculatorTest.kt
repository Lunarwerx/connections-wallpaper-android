package com.anthonyla.paperize.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrightnessCalculatorTest {
    @Test
    fun `dark mode dims a bright picture to the target luminance`() {
        // 0.38 / 0.9, inside the clamp, so the picture lands on the target.
        assertEquals(0.38f / 0.9f, BrightnessCalculator.getAdaptiveMultiplier(true, 0.9f), 0.001f)
    }

    @Test
    fun `dark mode dims a mid-grey picture`() {
        assertEquals(0.75f, BrightnessCalculator.getAdaptiveMultiplier(true, 0.5f), 0.001f)
        assertTrue(BrightnessCalculator.getAdaptiveMultiplier(true, 0.5f) < 1f)
    }

    @Test
    fun `light mode leaves every picture as it is`() {
        assertEquals(1f, BrightnessCalculator.getAdaptiveMultiplier(false, 0.9f), 0f)
        assertEquals(1f, BrightnessCalculator.getAdaptiveMultiplier(false, 0.2f), 0f)
    }

    @Test
    fun `near black pictures are not amplified or divided by zero`() {
        listOf(0f, 0.005f).forEach { brightness ->
            val multiplier = BrightnessCalculator.getAdaptiveMultiplier(true, brightness)
            assertEquals(0.75f, multiplier, 0.001f)
        }
    }
}
