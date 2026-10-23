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

/** How far a download has got: [done] of [total] originals on the phone. */
data class OfflineProgress(val done: Int, val total: Int)

sealed interface OfflineResult {
    val id: String
    /** In the Library now, as the album [albumId]. */
    data class Added(override val id: String, val name: String, val count: Int, val albumId: String) : OfflineResult
    data class Downloaded(override val id: String, val name: String, val count: Int) : OfflineResult
    /** A download that stopped short: [saved] of [total] originals are on the phone, and stay. */
    data class Partly(override val id: String, val name: String, val saved: Int, val total: Int) : OfflineResult
    data class SignInNeeded(override val id: String) : OfflineResult
    data class Failed(override val id: String, val reason: String?) : OfflineResult
}

/**
 * Whole collections in the Library. Add to library makes one an album at once: only its list is read, and each
 * picture comes to the phone when the wallpaper first shows it (ConnectionsPictures). Signed out, nothing is read and
 * the member is asked for the free account (Michael, 2026-10-21: ask when it makes sense, never earlier). Download is
 * the separate choice to keep every original on the phone so the album rotates with no internet; each original stays
 * once it is kept, so a download stopped or cut short keeps what it got and the next one goes on from there (Michael,
 * 2026-10-23: "when I canceled it, it, like, lost the whole progress"). Both run on past the page that started them.
 */
@Singleton
class OfflineCollections @Inject constructor(
    private val api: ConnectionsApi,
    private val library: ConnectionsLibrary,
    private val originals: ConnectionsOriginals,
    private val albums: AlbumRepository,
    private val collections: ConnectionsCollections
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val adds = mutableMapOf<String, Job>()
    private val downloads = mutableMapOf<String, Job>()

    private val _adding = MutableStateFlow<Set<String>>(emptySet())
    /** The collections being added now, while their list is read. */
    val adding: StateFlow<Set<String>> = _adding.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, OfflineProgress>>(emptyMap())
    /** The downloads running now, by collection id. */
    val progress: StateFlow<Map<String, OfflineProgress>> = _progress.asStateFlow()

    private val _results = MutableSharedFlow<OfflineResult>(extraBufferCapacity = RESULTS_BUFFER)
    val results: SharedFlow<OfflineResult> = _results.asSharedFlow()

    /** Adds [collection] to the Library unless it is being added; the result arrives on [results]. */
    fun start(collection: WallpaperCollection) {
        run(adds, collection.id, onEnd = { _adding.update { it - collection.id } }) {
            _adding.update { it + collection.id }
            add(collection)
        }
    }

    /**
     * Adds the collection [id]: one of the member's own as the phone keeps it, or a gallery tag's (its record once
     * added, else the tag itself). An own collection no longer on the phone adds nothing.
     */
    fun start(id: String) = withCollection(id) { start(it) }

    /** Downloads the collection [id]'s originals unless they are downloading; the result arrives on [results]. */
    fun download(id: String) = withCollection(id) { collection ->
        run(downloads, collection.id, onEnd = { _progress.update { it - collection.id } }) { download(collection) }
    }

    /** Stops the collection [id]'s add or download; what a download kept stays. */
    fun stop(id: String) {
        synchronized(this) {
            adds[id]?.cancel()
            downloads[id]?.cancel()
        }
    }

    /** Adds [collection] and says how it went; the account is asked for before anything is read. */
    suspend fun add(collection: WallpaperCollection): OfflineResult {
        val result = library.addToAlbum(
            pick = { collection.tag?.let { allOf(it) } ?: collection.wallpapers },
            albumFor = { albumFor(collection) }
        )
        return when (result) {
            LibraryAdd.SignInNeeded -> OfflineResult.SignInNeeded(collection.id)
            is LibraryAdd.Added -> {
                collections.recordSave(
                    collection, result.wanted, result.albumId, result.wanted.map { it.id }, complete = true, System.currentTimeMillis()
                )
                OfflineResult.Added(collection.id, collection.name, result.wanted.size, result.albumId)
            }
        }
    }

    /** Keeps every original of [collection] as it was added on the phone, the ones already kept counted as done. */
    suspend fun download(collection: WallpaperCollection): OfflineResult {
        val wallpapers = (collections.all.first().firstOrNull { it.id == collection.id } ?: collection).wallpapers
        val kept = originals.downloaded(wallpapers)
        var done = kept.size
        var lastFailure: Throwable? = null
        _progress.update { it + (collection.id to OfflineProgress(done, wallpapers.size)) }
        for (wallpaper in wallpapers) {
            if (wallpaper.id in kept) continue
            runCatchingCancellable { originals.keepOffline(wallpaper) }
                .onSuccess { done++ }
                .onFailure { lastFailure = it }
            _progress.update { it + (collection.id to OfflineProgress(done, wallpapers.size)) }
        }
        return when {
            done == wallpapers.size -> OfflineResult.Downloaded(collection.id, collection.name, done)
            done == 0 -> OfflineResult.Failed(collection.id, lastFailure?.message)
            else -> OfflineResult.Partly(collection.id, collection.name, done, wallpapers.size)
        }
    }

    private fun withCollection(id: String, then: (WallpaperCollection) -> Unit) {
        scope.launch {
            val collection = runCatchingCancellable { find(id) }.getOrElse {
                _results.emit(OfflineResult.Failed(id, it.message))
                return@launch
            } ?: return@launch
            then(collection)
        }
    }

    private suspend fun find(id: String): WallpaperCollection? {
        val record = collections.all.first().firstOrNull { it.id == id }
        val tag = id.takeIf { it.startsWith(TAG_COLLECTION) }?.removePrefix(TAG_COLLECTION)
        return record ?: tag?.let { WallpaperCollection(id = id, name = tagTitle(it), tag = it) }
    }

    /** Runs [work] for [id] in [jobs] unless one runs there already, and says how it went; [onEnd] runs however it ends. */
    private fun run(jobs: MutableMap<String, Job>, id: String, onEnd: () -> Unit, work: suspend () -> OfflineResult) {
        synchronized(this) {
            if (jobs[id]?.isActive == true) return
            jobs[id] = scope.launch {
                _results.emit(runCatchingCancellable { work() }.getOrElse { OfflineResult.Failed(id, it.message) })
            }.also { job ->
                job.invokeOnCompletion {
                    onEnd()
                    synchronized(this) { if (jobs[id] === job) jobs.remove(id) }
                }
            }
        }
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

    /** The album an earlier add made, while it still exists; else a new one named for the collection. */
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
