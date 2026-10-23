package com.anthonyla.paperize.connections

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
 * 2026-10-23: "it should just instantly add ... I didn't choose download"). A signed-out member gets nothing: no
 * album. Adding one picture from the viewer, adding a selection, and adding a whole collection all come through here.
 */
@Singleton
class ConnectionsLibrary @Inject constructor(
    private val account: ConnectionsAccount,
    private val originals: ConnectionsOriginals
) {
    /** Adds every picture [pick] returns to the album [albumFor] names; both run only once the account is there. */
    suspend fun addToAlbum(pick: suspend () -> List<ConnectionsWallpaper>, albumFor: suspend () -> String): LibraryAdd {
        account.accessToken() ?: return LibraryAdd.SignInNeeded
        val wanted = pick()
        val albumId = albumFor()
        return LibraryAdd.Added(albumId, wanted, originals.addAllToAlbum(wanted, albumId))
    }
}
