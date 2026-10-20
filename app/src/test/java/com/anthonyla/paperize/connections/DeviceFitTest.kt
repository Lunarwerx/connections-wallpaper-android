package com.anthonyla.paperize.connections

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceFitTest {
    private data class Screen(
        val name: String,
        val widthDp: Float,
        val heightDp: Float,
        val foldable: Boolean,
        val computer: Boolean,
        val kind: DeviceKind,
        val shape: WallpaperShape
    )

    @Test fun `each screen gets its own word and the gallery shape that fills it`() {
        val screens = listOf(
            Screen("phone upright", 411f, 914f, foldable = false, computer = false, DeviceKind.PHONE, WallpaperShape.PORTRAIT),
            Screen("phone turned sideways keeps its upright home screen", 914f, 411f, foldable = false, computer = false, DeviceKind.PHONE, WallpaperShape.PORTRAIT),
            Screen("foldable folded", 411f, 797f, foldable = true, computer = false, DeviceKind.FOLDED, WallpaperShape.PORTRAIT),
            Screen("foldable unfolded, wider than tall", 841f, 701f, foldable = true, computer = false, DeviceKind.UNFOLDED, WallpaperShape.LANDSCAPE),
            Screen("foldable unfolded, near square", 700f, 690f, foldable = true, computer = false, DeviceKind.UNFOLDED, WallpaperShape.SQUARE),
            Screen("tablet held upright turns with the device", 800f, 1280f, foldable = false, computer = false, DeviceKind.TABLET, WallpaperShape.PORTRAIT),
            Screen("Googlebook desktop emulator", 1920f, 1200f, foldable = false, computer = true, DeviceKind.COMPUTER, WallpaperShape.LANDSCAPE)
        )
        for (screen in screens) {
            val fit = DeviceFit.of(screen.widthDp, screen.heightDp, screen.foldable, screen.computer)
            assertEquals(screen.name, screen.kind, fit.kind)
            assertEquals(screen.name, screen.shape, fit.shape)
        }
    }
}
