package com.anthonyla.paperize.connections

import kotlin.math.abs
import kotlin.math.ln

/** The kind of screen Explore picks pictures for; the shape chip names it ("Fits this tablet"). */
enum class DeviceKind { PHONE, FOLDED, UNFOLDED, TABLET, COMPUTER }

/** The gallery's three shapes, as its `?orientation=` names them (wallpaper-contract.ts WALLPAPER_ORIENTATIONS). */
enum class WallpaperShape(val wire: String) {
    PORTRAIT("portrait"),
    LANDSCAPE("landscape"),
    SQUARE("square");

    companion object {
        /** The gallery's own rule (wallpaperOrientation): within 5% of square counts as square. */
        fun of(ratio: Float): WallpaperShape = when {
            ratio > SQUARE_BAND -> LANDSCAPE
            ratio < 1f / SQUARE_BAND -> PORTRAIT
            else -> SQUARE
        }

        private const val SQUARE_BAND = 1.05f
    }
}

/**
 * The screen a wallpaper set from Explore fills: what kind it is and its width over height. It is measured
 * from the display the app's window is on, not the window, because a wallpaper fills that whole screen even
 * while the app sits in a split screen or a laptop window; it is measured again when the device folds,
 * unfolds, turns or moves the window to another display.
 */
data class DeviceFit(val kind: DeviceKind, val ratio: Float) {
    val shape: WallpaperShape get() = WallpaperShape.of(ratio)

    /** How far a picture's shape is from this screen's, 0 being an exact fit; 2:1 and 1:2 are equally far from 1:1. */
    fun distance(width: Int, height: Int): Float =
        if (width > 0 && height > 0) abs(ln(width.toFloat() / height) - ln(ratio)) else Float.MAX_VALUE

    companion object {
        /**
         * [widthDp] by [heightDp] is the display as it is held now. A phone's home screen stands upright, so a
         * phone (or a folded foldable) always wants its upright shape; a tablet, an open foldable and a
         * computer turn their home screen with the device, so they want the shape they have now.
         */
        fun of(widthDp: Float, heightDp: Float, foldable: Boolean, computer: Boolean): DeviceFit {
            val short = minOf(widthDp, heightDp)
            val long = maxOf(widthDp, heightDp)
            val large = short >= LARGE_SCREEN_DP
            val kind = when {
                computer -> DeviceKind.COMPUTER
                foldable -> if (large) DeviceKind.UNFOLDED else DeviceKind.FOLDED
                large -> DeviceKind.TABLET
                else -> DeviceKind.PHONE
            }
            val upright = kind == DeviceKind.PHONE || kind == DeviceKind.FOLDED
            return DeviceFit(kind, if (upright) short / long else widthDp / heightDp)
        }

        /** Android's own line between phone and tablet layouts: 600dp of smallest width. */
        private const val LARGE_SCREEN_DP = 600f
    }
}

/** What Explore shows, remembered like every other setting: what fits this screen (the default), every shape, or one. */
enum class ExploreShape(val saved: String) {
    FIT("fit"),
    ALL("all"),
    PORTRAIT("portrait"),
    LANDSCAPE("landscape"),
    SQUARE("square");

    /** The shape to ask the gallery for; null asks for every shape (or, fitting, means the screen is not measured yet). */
    fun requested(device: DeviceFit?): WallpaperShape? = when (this) {
        FIT -> device?.shape
        ALL -> null
        PORTRAIT -> WallpaperShape.PORTRAIT
        LANDSCAPE -> WallpaperShape.LANDSCAPE
        SQUARE -> WallpaperShape.SQUARE
    }

    companion object {
        fun fromSaved(value: String?): ExploreShape = entries.firstOrNull { it.saved == value } ?: FIT
    }
}

/** The pictures closest to [device]'s shape first; equally close ones keep the gallery's order. */
fun List<ConnectionsWallpaper>.closestFirst(device: DeviceFit): List<ConnectionsWallpaper> =
    sortedBy { device.distance(it.width, it.height) }
