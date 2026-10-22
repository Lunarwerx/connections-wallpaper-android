package com.anthonyla.paperize.connections

import com.anthonyla.paperize.core.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

/** What one add to the Library did: the album it went to, and which pictures were kept there. */
sealed interface LibraryAdd {
    /** Signed out: nothing was fetched, and the member is asked for the free account first. */
    data object SignInNeeded : LibraryAdd

    /** [kept] of [wanted] originals are on the phone; [added] of them were new to the album [albumId]. */
    data class Added(
        val albumId: String,
        val wanted: List<ConnectionsWallpaper>,
        val kept: List<ConnectionsWallpaper>,
        val added: Int
    ) : LibraryAdd

    data class Failed(val reason: String?) : LibraryAdd
}

/**
 * The one path a gallery picture takes into the Library: its original and its preview are kept in the app's own
 * storage, then it joins an album. A signed-out member gets nothing: no fetch, no album. Adding one picture from
 * the viewer, adding a selection, and saving a whole collection offline all come through here.
 */
@Singleton
class ConnectionsLibrary @Inject constructor(
    private val account: ConnectionsAccount,
    private val originals: ConnectionsOriginals
) {
    /**
     * Keeps every picture [pick] returns and adds the ones kept to the album [albumFor] names. [pick] runs only once
     * the account is there, and [albumFor] only when something was kept, so an album is never made for nothing.
     * [onProgress] hears (done, total) as each picture is kept.
     */
    suspend fun addToAlbum(
        pick: suspend () -> List<ConnectionsWallpaper>,
        albumFor: suspend () -> String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): LibraryAdd {
        account.accessToken() ?: return LibraryAdd.SignInNeeded
        val wanted = pick()
        val kept = mutableListOf<ConnectionsWallpaper>()
        var lastFailure: Throwable? = null
        onProgress(0, wanted.size)
        wanted.forEachIndexed { index, wallpaper ->
            runCatchingCancellable { originals.keepOffline(wallpaper) }
                .onSuccess { kept += wallpaper }
                .onFailure { lastFailure = it }
            onProgress(index + 1, wanted.size)
        }
        if (kept.isEmpty() && wanted.isNotEmpty()) return LibraryAdd.Failed(lastFailure?.message)
        val albumId = albumFor()
        val added = originals.addAllToAlbum(kept, albumId)
        return LibraryAdd.Added(albumId, wanted, kept, added)
    }
}
