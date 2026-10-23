package com.anthonyla.paperize.domain.source

import com.anthonyla.paperize.domain.model.Wallpaper

/** Brings a wallpaper's picture to the phone ahead of the change that shows it, when it comes over the network. */
interface UpcomingPictures {
    /** Starts fetching [wallpaper]'s picture if the phone does not hold it; returns at once and never throws. */
    fun warm(wallpaper: Wallpaper)
}
