package com.anthonyla.paperize.service.worker

import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.domain.model.ScheduleSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperSchedulingPolicyTest {
    @Test
    fun `manual synchronized change resets the shared job`() {
        val settings = ScheduleSettings(intervalEnabled = true,
            enableChanger = true,
            homeEnabled = true,
            lockEnabled = true,
            homeAlbumIds = listOf("album"),
            lockAlbumIds = listOf("album")
        )

        assertEquals(
            setOf(ScreenType.BOTH),
            scheduledScreensToReset(ScreenType.HOME, settings, WallpaperMode.STATIC)
        )
    }

    @Test
    fun `manual both change resets each independent job`() {
        val settings = ScheduleSettings(intervalEnabled = true,
            enableChanger = true,
            separateSchedules = true,
            homeEnabled = true,
            lockEnabled = true,
            homeAlbumIds = listOf("home"),
            lockAlbumIds = listOf("lock")
        )

        assertEquals(
            setOf(ScreenType.HOME, ScreenType.LOCK),
            scheduledScreensToReset(ScreenType.BOTH, settings, WallpaperMode.STATIC)
        )
    }

    @Test
    fun `manual change does not create a disabled schedule`() {
        assertEquals(
            emptySet<ScreenType>(),
            scheduledScreensToReset(
                ScreenType.BOTH,
                ScheduleSettings(intervalEnabled = true, enableChanger = false),
                WallpaperMode.STATIC
            )
        )
    }

    @Test
    fun `timer off resets no schedule after a manual change`() {
        val settings = ScheduleSettings(
            intervalEnabled = false,
            enableChanger = true,
            homeEnabled = true,
            lockEnabled = true,
            homeAlbumIds = listOf("album"),
            lockAlbumIds = listOf("album")
        )

        assertEquals(
            emptySet<ScreenType>(),
            scheduledScreensToReset(ScreenType.HOME, settings, WallpaperMode.STATIC)
        )
    }

    @Test
    fun `short live interval belongs to visible engine instead of WorkManager`() {
        val settings = ScheduleSettings(intervalEnabled = true,
            enableChanger = true,
            liveAlbumIds = listOf("live"),
            liveIntervalMinutes = 14
        )

        assertEquals(
            emptySet<ScreenType>(),
            scheduledScreensToReset(ScreenType.LIVE, settings, WallpaperMode.LIVE)
        )
        assertEquals(
            setOf(ScreenType.LIVE),
            scheduledScreensToReset(
                ScreenType.LIVE,
                settings.copy(liveIntervalMinutes = 15),
                WallpaperMode.LIVE
            )
        )
    }
}
