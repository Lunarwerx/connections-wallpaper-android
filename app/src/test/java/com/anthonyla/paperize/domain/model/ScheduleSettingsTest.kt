package com.anthonyla.paperize.domain.model

import com.anthonyla.paperize.core.ScalingType
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleSettingsTest {
    @Test
    fun `validation applies distinct static and live minimums and validates all screens`() {
        val effects = WallpaperEffects(darkenPercentage = 150, blurPercentage = -10)
        val settings = ScheduleSettings(
            homeIntervalMinutes = 5,
            lockIntervalMinutes = 120,
            liveIntervalMinutes = 0,
            homeEffects = effects,
            lockEffects = effects,
            liveEffects = effects
        )
        val validEffects = effects.copy(darkenPercentage = 100, blurPercentage = 0)
        assertEquals(
            settings.copy(
                homeIntervalMinutes = 15,
                liveIntervalMinutes = 1,
                homeEffects = validEffects,
                lockEffects = validEffects,
                liveEffects = validEffects
            ),
            settings.validate()
        )
    }

    @Test
    fun `visual effects are drawn only while switched on, and double tap and parallax always`() {
        val home = WallpaperEffects(
            enableBlur = true, blurPercentage = 40, enableDarken = true, darkenPercentage = 30,
            enableVignette = true, vignettePercentage = 50, enableGrayscale = true, grayscalePercentage = 100,
            enableDoubleTap = true, enableParallax = true, parallaxIntensity = 60
        )
        val chosen = mapOf(
            ScreenType.HOME to home, ScreenType.BOTH to home,
            ScreenType.LOCK to home.copy(blurPercentage = 70), ScreenType.LIVE to home.copy(blurPercentage = 90)
        )
        val off = ScheduleSettings(
            homeEffects = home, lockEffects = chosen.getValue(ScreenType.LOCK), liveEffects = chosen.getValue(ScreenType.LIVE),
            adaptiveBrightness = true
        )
        val on = off.copy(visualEffectsEnabled = true)
        ScreenType.entries.forEach { screen ->
            val drawn = off.appliedEffects(screen)
            assertFalse("$screen off", drawn.enableBlur || drawn.enableDarken || drawn.enableVignette || drawn.enableGrayscale)
            assertTrue("$screen interactive", drawn.enableDoubleTap && drawn.enableParallax && drawn.parallaxIntensity == 60)
            assertEquals("$screen on", chosen.getValue(screen), on.appliedEffects(screen))
        }
        assertFalse(off.appliesAdaptiveBrightness)
        assertTrue(on.appliesAdaptiveBrightness)
    }

    @Test
    fun `individual scheduling splits one album only while the timer it splits is running`() {
        val split = ScheduleSettings(
            enableChanger = true, intervalEnabled = true, separateSchedules = true,
            homeEnabled = true, lockEnabled = true, homeAlbumId = "album", lockAlbumId = "album"
        )
        assertEquals(setOf(ScreenType.HOME, ScreenType.LOCK), split.activeScreens(WallpaperMode.STATIC))
        // Its switch is hidden with the timer or the changer off, so the kept choice must not split the picture then.
        assertEquals(setOf(ScreenType.BOTH), split.copy(intervalEnabled = false).activeScreens(WallpaperMode.STATIC))
        assertEquals(setOf(ScreenType.BOTH), split.copy(enableChanger = false).activeScreens(WallpaperMode.STATIC))
    }

    @Test
    fun `visible live timer is used only below WorkManager minimum`() {
        assertFalse(usesVisibleLiveTimer(0))
        assertTrue(usesVisibleLiveTimer(1))
        assertTrue(usesVisibleLiveTimer(14))
        assertFalse(usesVisibleLiveTimer(15))
    }

    @Test
    fun `schedule edits require rescheduling without reapplying display effects`() {
        val current = ScheduleSettings()
        listOf(
            current.copy(enableChanger = true),
            current.copy(homeAlbumId = "home"),
            current.copy(lockAlbumId = "lock"),
            current.copy(liveAlbumId = "live"),
            current.copy(homeEnabled = true),
            current.copy(lockEnabled = true),
            current.copy(homeIntervalMinutes = 120),
            current.copy(lockIntervalMinutes = 120),
            current.copy(liveIntervalMinutes = 120),
            current.copy(separateSchedules = true)
        ).forEach { edited ->
            assertTrue("Scheduling: $edited", current.hasSchedulingChanges(edited))
            assertFalse("Display: $edited", current.hasDisplayChanges(edited))
        }
    }

    @Test
    fun `display edits require reapplication without resetting schedules`() {
        val current = ScheduleSettings()
        val effects = WallpaperEffects(enableBlur = true)
        listOf(
            current.copy(homeScalingType = ScalingType.FIT),
            current.copy(lockScalingType = ScalingType.STRETCH),
            current.copy(liveScalingType = ScalingType.NONE),
            current.copy(homeScrollingEnabled = true),
            current.copy(homeEffects = effects),
            current.copy(lockEffects = effects),
            current.copy(liveEffects = effects),
            current.copy(adaptiveBrightness = true),
            current.copy(visualEffectsEnabled = true)
        ).forEach { edited ->
            assertTrue("Display: $edited", current.hasDisplayChanges(edited))
            assertFalse("Scheduling: $edited", current.hasSchedulingChanges(edited))
        }
        assertFalse(current.hasSchedulingChanges(current))
        assertFalse(current.hasDisplayChanges(current))
    }
}
