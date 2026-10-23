package com.anthonyla.paperize.connections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What is picked, by id: pictures on a Connections wall, or collections on the shelf and in the manager. A long
 * press starts selection mode with that one picked; while any is picked, taps toggle instead of opening it.
 * Clearing the last one leaves selection mode, and so does the back key.
 */
class WallSelection {
    var ids by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Selection mode: on while at least one picture is picked. */
    val active: Boolean get() = ids.isNotEmpty()

    /** A long press: [id] is picked, and the others already picked stay picked. */
    fun enter(id: String) {
        ids = ids + id
    }

    fun toggle(id: String) {
        ids = if (id in ids) ids - id else ids + id
    }

    /** Picks every one of [all] (the pictures on the wall now). */
    fun selectAll(all: List<String>) {
        ids = ids + all
    }

    fun clear() {
        ids = emptySet()
    }

    /** The pictures of [wallpapers] that are picked, in the wall's order. */
    fun picked(wallpapers: List<ConnectionsWallpaper>): List<ConnectionsWallpaper> = wallpapers.filter { it.id in ids }
}
