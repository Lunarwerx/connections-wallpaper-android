package com.anthonyla.paperize.connections

import com.anthonyla.paperize.core.runCatchingCancellable
import com.anthonyla.paperize.domain.repository.AlbumRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** How far a save offline has got: [done] of [total] pictures kept. */
data class OfflineProgress(val done: Int, val total: Int)

sealed interface OfflineResult {
    val id: String
    data class Saved(override val id: String, val name: String, val count: Int, val albumId: String) : OfflineResult
    data class Partly(override val id: String, val name: String, val saved: Int, val total: Int) : OfflineResult
    data class SignInNeeded(override val id: String) : OfflineResult
    data class Failed(override val id: String, val reason: String?) : OfflineResult
}

/**
 * Saves a collection offline for a signed-in member: each picture's full original (the gallery's public full
 * door) and its preview go into the app's own storage, and the collection becomes an album, so it opens, sets
 * and rotates with no internet. Signed out, nothing is fetched and the member is asked for the free account
 * (Michael, 2026-10-21: ask when it makes sense, never earlier). A save runs on past the page that started it;
 * one stopped or cut short keeps what it saved, and the next save picks up from there.
 */
@Singleton
class OfflineCollections @Inject constructor(
    private val api: ConnectionsApi,
    private val account: ConnectionsAccount,
    private val originals: ConnectionsOriginals,
    private val albums: AlbumRepository,
    private val collections: ConnectionsCollections
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()

    private val _progress = MutableStateFlow<Map<String, OfflineProgress>>(emptyMap())
    /** The saves running now, by collection id. */
    val progress: StateFlow<Map<String, OfflineProgress>> = _progress.asStateFlow()

    private val _results = MutableSharedFlow<OfflineResult>(extraBufferCapacity = RESULTS_BUFFER)
    val results: SharedFlow<OfflineResult> = _results.asSharedFlow()

    /** Starts saving [collection] unless it is already saving; the result arrives on [results]. */
    fun start(collection: WallpaperCollection) {
        synchronized(jobs) { startLocked(collection) }
    }

    fun stop(id: String) {
        synchronized(jobs) { jobs[id]?.cancel() }
    }

    private fun startLocked(collection: WallpaperCollection) {
        if (jobs[collection.id]?.isActive == true) return
        jobs[collection.id] = scope.launch {
            val result = runCatchingCancellable { save(collection) }
                .getOrElse { OfflineResult.Failed(collection.id, it.message) }
            _results.emit(result)
        }.also { job ->
            job.invokeOnCompletion {
                _progress.update { it - collection.id }
                synchronized(jobs) { if (jobs[collection.id] === job) jobs.remove(collection.id) }
            }
        }
    }

    /** Saves [collection] and says how it went; the account is asked for before anything is fetched. */
    suspend fun save(collection: WallpaperCollection): OfflineResult {
        account.accessToken() ?: return OfflineResult.SignInNeeded(collection.id)
        val wallpapers = collection.tag?.let { allOf(it) } ?: collection.wallpapers
        _progress.update { it + (collection.id to OfflineProgress(0, wallpapers.size)) }
        val kept = mutableListOf<ConnectionsWallpaper>()
        var lastFailure: Throwable? = null
        wallpapers.forEachIndexed { index, wallpaper ->
            runCatchingCancellable { originals.keepOffline(wallpaper) }
                .onSuccess { kept += wallpaper }
                .onFailure { lastFailure = it }
            _progress.update { it + (collection.id to OfflineProgress(index + 1, wallpapers.size)) }
        }
        if (kept.isEmpty() && wallpapers.isNotEmpty()) return OfflineResult.Failed(collection.id, lastFailure?.message)
        val albumId = albumFor(collection)
        originals.addAllToAlbum(kept, albumId)
        val complete = kept.size == wallpapers.size
        collections.recordSave(collection, wallpapers, albumId, kept.map { it.id }, complete, System.currentTimeMillis())
        return if (complete) OfflineResult.Saved(collection.id, collection.name, kept.size, albumId)
        else OfflineResult.Partly(collection.id, collection.name, kept.size, wallpapers.size)
    }

    /** Every picture a gallery tag holds now, page by page. */
    private suspend fun allOf(tag: String): List<ConnectionsWallpaper> {
        val all = mutableListOf<ConnectionsWallpaper>()
        var cursor: String? = null
        do {
            val page = api.wallpapers("", tag, null, cursor)
            all += page.items
            cursor = page.nextCursor
        } while (cursor != null)
        return all.distinctBy { it.id }
    }

    /** The album an earlier save made, while it still exists; else a new one named for the collection. */
    private suspend fun albumFor(collection: WallpaperCollection): String {
        val recorded = collections.all.first().firstOrNull { it.id == collection.id }?.albumId ?: collection.albumId
        recorded?.let { id -> if (albums.getAlbumById(id).first() != null) return id }
        var name = collection.name
        var n = 2
        while (albums.getAlbumByName(name) != null) name = "${collection.name} ${n++}"
        return albums.createAlbum(name).id
    }

    private companion object {
        const val RESULTS_BUFFER = 8
    }
}
