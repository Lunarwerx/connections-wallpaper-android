package com.anthonyla.paperize.connections

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** What one add to the Library did: the album it went to, and which pictures joined it. */
sealed interface LibraryAdd {
    /** Signed out: nothing was read, and the member is asked for the free account first. */
    data object SignInNeeded : LibraryAdd

    /** [wanted] are in the album [albumId]; [added] of them were new to it. */
    data class Added(val albumId: String, val wanted: List<ConnectionsWallpaper>, val added: Int) : LibraryAdd
}

/**
 * The one path a gallery picture takes into the Library: it joins an album at once, by reference, and comes to the
 * phone only when the wallpaper first shows it (ConnectionsPictures); Download is a separate choice (Michael,
 * 2026-10-23: "it should just instantly add ... I didn't choose download"). Pictures join page by page: the album is
 * made with the first page and each page joins it as it arrives, so a collection is in the Library before the gallery
 * has finished listing it (Michael, 2026-10-23, on 1.0.929: "there was still a delay when I clicked Add to Library").
 * A signed-out member gets nothing: no album. Adding one picture from the viewer, adding a selection, and adding a
 * whole collection all come through here.
 */
@Singleton
class ConnectionsLibrary @Inject constructor(
    private val account: ConnectionsAccount,
    private val originals: ConnectionsOriginals
) {
    /**
     * Adds every picture [pages] gives, page by page, to the album [albumFor] names (made once, with the first page);
     * [onPage] hears the album and every picture so far after each page joins it. Nothing is read or made until the
     * account is there.
     */
    suspend fun addToAlbum(
        pages: Flow<List<ConnectionsWallpaper>>,
        albumFor: suspend () -> String,
        onPage: suspend (albumId: String, wanted: List<ConnectionsWallpaper>) -> Unit = { _, _ -> }
    ): LibraryAdd {
        account.accessToken() ?: return LibraryAdd.SignInNeeded
        var albumId: String? = null
        val wanted = mutableListOf<ConnectionsWallpaper>()
        val seen = HashSet<String>()
        var added = 0
        pages.collect { page ->
            val fresh = page.filter { seen.add(it.id) }
            val id = albumId ?: albumFor().also { albumId = it }
            added += originals.addAllToAlbum(fresh, id)
            wanted += fresh
            onPage(id, wanted.toList())
        }
        return LibraryAdd.Added(albumId ?: albumFor(), wanted, added)
    }
}
