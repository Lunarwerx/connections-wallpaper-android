package com.anthonyla.paperize.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anthonyla.paperize.core.runCatchingCancellable
import com.anthonyla.paperize.domain.model.AlbumSummary
import com.anthonyla.paperize.domain.repository.AlbumRepository
import com.anthonyla.paperize.domain.usecase.CreateAlbumUseCase
import com.anthonyla.paperize.presentation.common.stateWhileSubscribed
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class ConnectionsUiState(
    val query: String = "",
    val tag: String? = null,
    val tags: List<ConnectionsTag> = emptyList(),
    val wallpapers: List<ConnectionsWallpaper> = emptyList(),
    val nextCursor: String? = null,
    /** True from the start: the first page is asked for as soon as the saved shape and the screen are known. */
    val loading: Boolean = true,
    val failed: Boolean = false,
    /** Wallpapers being added to an album, being set on the screen, and being saved to the phone, by id. */
    val saving: Set<String> = emptySet(),
    val setting: Set<String> = emptySet(),
    val downloading: Set<String> = emptySet(),
    /** The shape chip's choice, and the screen it fits ([device] is null until the screen is measured). */
    val shape: ExploreShape = ExploreShape.FIT,
    val device: DeviceFit? = null
)

/** Where a Library add goes: an album that exists, or a new album with this name. */
sealed interface LibraryTarget {
    data class Existing(val album: AlbumSummary) : LibraryTarget
    data class New(val name: String) : LibraryTarget
}

/** A Library add and its pictures; a plain class, so each waiting request is its own, even when two are alike. */
class LibraryRequest(val wallpapers: List<ConnectionsWallpaper>, val target: LibraryTarget)

sealed interface ConnectionsMessage {
    data class Saved(val albumName: String) : ConnectionsMessage
    /** [count] pictures were kept and added to the album [albumId], which is called [albumName]. */
    data class AddedToLibrary(val albumId: String, val albumName: String, val count: Int) : ConnectionsMessage
    data object AlreadyInAlbum : ConnectionsMessage
    data class WallpaperSet(val target: WallpaperTarget) : ConnectionsMessage
    data object SavedToPhone : ConnectionsMessage
    data class AddedToCollection(val name: String) : ConnectionsMessage
    data object SignInNeeded : ConnectionsMessage
    data class Failed(val reason: String?) : ConnectionsMessage
}

/** What one list of pages asks the gallery for; [order] puts each page closest to that screen's shape first. */
private data class PageRequest(val query: String, val tag: String?, val orientation: WallpaperShape?, val order: DeviceFit?)

private class SignInNeededException : IOException("Connections asked for a sign-in to save this wallpaper")

@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    private val api: ConnectionsApi,
    private val account: ConnectionsAccount,
    private val originals: ConnectionsOriginals,
    private val library: ConnectionsLibrary,
    private val createAlbumUseCase: CreateAlbumUseCase,
    private val preferences: ExplorePreferences,
    albumRepository: AlbumRepository,
    private val collectionStore: ConnectionsCollections,
    private val offline: OfflineCollections
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectionsUiState())
    val state: StateFlow<ConnectionsUiState> = _state.asStateFlow()

    val albums: StateFlow<List<AlbumSummary>> =
        albumRepository.getAlbumSummaries().stateWhileSubscribed(viewModelScope, emptyList())

    /** The phone's collections: the member's own, and gallery tags saved offline. */
    val collections: StateFlow<List<WallpaperCollection>> =
        collectionStore.all.stateWhileSubscribed(viewModelScope, emptyList())

    /** The cards for Explore's Collections shelf and the collections manager, each with how far its Library add has got. */
    val cards: StateFlow<List<CollectionCard>> =
        combine(
            // Covers are looked up on the phone once per change of the collections, not on every picture an add keeps.
            combine(collectionStore.all, _state.map { it.tags }.distinctUntilChanged()) { saved, tags ->
                collectionCards(saved, tags, originals::savedPreview)
            }.flowOn(Dispatchers.IO),
            offline.progress
        ) { cards, progress ->
            if (progress.isEmpty()) cards else cards.map { card -> progress[card.id]?.let { card.copy(adding = it) } ?: card }
        }.stateWhileSubscribed(viewModelScope, emptyList())

    /** How each collection's Library add went, wherever it was started. */
    val offlineResults: Flow<OfflineResult> = offline.results

    private val _messages = Channel<ConnectionsMessage>(Channel.BUFFERED)
    val messages: Flow<ConnectionsMessage> = _messages.receiveAsFlow()

    private val _libraryWaiting = MutableStateFlow<LibraryRequest?>(null)
    /**
     * A Library add that asked for the account. The screen opens the account sheet for it (titled for this action)
     * and calls [resumeLibrary] once the member is signed in, so the add finishes by itself.
     */
    val libraryWaiting: StateFlow<LibraryRequest?> = _libraryWaiting.asStateFlow()

    private val _collectionsWaiting = MutableStateFlow<List<String>?>(null)
    /** Whole collections whose Library add asked for the account; [resumeLibrary] starts them once the member is in. */
    val collectionsWaiting: StateFlow<List<String>?> = _collectionsWaiting.asStateFlow()

    private var pageJob: Job? = null
    private var request: PageRequest? = null
    private var shapeKnown = false

    init {
        loadTags()
        viewModelScope.launch {
            val saved = preferences.shape()
            // A shape picked while the saved one was still being read wins.
            if (!shapeKnown) {
                shapeKnown = true
                _state.update { it.copy(shape = saved) }
            }
            refresh()
        }
    }

    /** The screen Explore is shown on, measured again on every fold, unfold, turn or move to another display. */
    fun setDevice(device: DeviceFit) {
        if (device == _state.value.device) return
        _state.update { it.copy(device = device) }
        refresh()
    }

    fun selectShape(shape: ExploreShape) {
        shapeKnown = true
        if (shape == _state.value.shape) return
        _state.update { it.copy(shape = shape) }
        viewModelScope.launch { preferences.setShape(shape) }
        refresh()
    }

    fun search(query: String) {
        if (query == _state.value.query) return
        _state.update { it.copy(query = query) }
        refresh()
    }

    fun selectTag(tag: String?) {
        if (tag == _state.value.tag) return
        _state.update { it.copy(tag = tag) }
        refresh()
    }

    fun retry() {
        if (_state.value.tags.isEmpty()) loadTags()
        if (_state.value.wallpapers.isEmpty()) {
            request = null
            refresh()
        } else {
            loadMore()
        }
    }

    fun loadMore() {
        val current = _state.value
        val request = request ?: return
        if (current.loading || current.nextCursor == null) return
        load(request, current.nextCursor)
    }

    /** Adds [wallpapers] to the Library: to an album that exists, or to one made now, named [target]. */
    fun addToLibrary(wallpapers: List<ConnectionsWallpaper>, target: LibraryTarget) {
        if (wallpapers.isEmpty()) return
        viewModelScope.launch { runLibrary(LibraryRequest(wallpapers, target)) }
    }

    /**
     * Adds whole collections to the Library, each as its own album, as Add to library on a collection's page does.
     * Signed out, they wait for the account (the screen opens the account sheet) and start once the member is in.
     */
    fun addCollectionsToLibrary(ids: List<String>, signedIn: Boolean) {
        if (ids.isEmpty()) return
        if (signedIn) ids.forEach { offline.start(it) } else _collectionsWaiting.update { (it.orEmpty() + ids).distinct() }
    }

    /** Deletes the member's own collections among [ids], stopping any add of theirs; a gallery collection is never deleted. */
    fun deleteCollections(ids: List<String>) {
        val own = ids.filterNot { it.startsWith(TAG_COLLECTION) }
        if (own.isEmpty()) return
        own.forEach { offline.stop(it) }
        viewModelScope.launch {
            runCatchingCancellable { collectionStore.delete(*own.toTypedArray()) }
                .onFailure { _messages.send(ConnectionsMessage.Failed(it.message)) }
        }
    }

    /** The account sheet was closed without signing in: the waiting Library adds are dropped, as a collection page's is. */
    fun cancelLibraryAdd() {
        _libraryWaiting.value = null
        _collectionsWaiting.value = null
    }

    /** Runs the Library adds that waited for the account, if any wait; the screen calls this once the member is signed in. */
    fun resumeLibrary() {
        _collectionsWaiting.getAndUpdate { null }?.forEach { offline.start(it) }
        val request = _libraryWaiting.value ?: return
        viewModelScope.launch { runLibrary(request) }
    }

    private suspend fun runLibrary(request: LibraryRequest) {
        val ids = request.wallpapers.map { it.id }.toSet()
        _state.update { it.copy(saving = it.saving + ids) }
        try {
            var albumName = ""
            val result = runCatchingCancellable {
                library.addToAlbum(
                    pick = { request.wallpapers },
                    albumFor = { albumOf(request.target).let { (id, name) -> albumName = name; id } }
                )
            }.getOrElse {
                _libraryWaiting.value = null
                _messages.send(ConnectionsMessage.Failed(it.message))
                return
            }
            when (result) {
                LibraryAdd.SignInNeeded -> _libraryWaiting.value = request
                is LibraryAdd.Failed -> {
                    _libraryWaiting.value = null
                    _messages.send(ConnectionsMessage.Failed(result.reason))
                }
                is LibraryAdd.Added -> {
                    _libraryWaiting.value = null
                    _messages.send(
                        if (result.kept.isNotEmpty() && result.added == 0) ConnectionsMessage.AlreadyInAlbum
                        else ConnectionsMessage.AddedToLibrary(result.albumId, albumName, result.kept.size)
                    )
                }
            }
        } finally {
            _state.update { it.copy(saving = it.saving - ids) }
        }
    }

    /** The album a Library add goes to: one that exists, or a new one (whose name the gallery may still change). */
    private suspend fun albumOf(target: LibraryTarget): Pair<String, String> = when (target) {
        is LibraryTarget.Existing -> target.album.id to target.album.name
        is LibraryTarget.New -> createAlbumUseCase(target.name).let { it.id to it.name }
    }

    fun setWallpaper(wallpaper: ConnectionsWallpaper, target: WallpaperTarget) {
        if (wallpaper.id in _state.value.setting) return
        viewModelScope.launch {
            _state.update { it.copy(setting = it.setting + wallpaper.id) }
            val message = runCatchingCancellable { originals.setAsWallpaper(wallpaper, target) }
                .fold(onSuccess = { ConnectionsMessage.WallpaperSet(target) }, onFailure = ::failure)
            _state.update { it.copy(setting = it.setting - wallpaper.id) }
            _messages.send(message)
        }
    }

    /** Saving the original to the phone is the one thing that takes an account (Michael, 2026-10-20: encouragement, not a lock). */
    fun saveToPhone(wallpaper: ConnectionsWallpaper) {
        if (wallpaper.id in _state.value.downloading) return
        viewModelScope.launch {
            _state.update { it.copy(downloading = it.downloading + wallpaper.id) }
            val message = runCatchingCancellable { withToken { originals.saveToPhone(wallpaper, it) } }
                .fold(onSuccess = { ConnectionsMessage.SavedToPhone }, onFailure = ::failure)
            _state.update { it.copy(downloading = it.downloading - wallpaper.id) }
            _messages.send(message)
        }
    }

    /** Puts [wallpaper] in one of the member's own collections, or takes it out when it is there. */
    fun toggleInCollection(wallpaper: ConnectionsWallpaper, collectionId: String) {
        viewModelScope.launch {
            runCatchingCancellable { collectionStore.toggle(collectionId, wallpaper) }
                .onFailure { _messages.send(ConnectionsMessage.Failed(it.message)) }
        }
    }

    /** Adds [wallpapers] to one of the member's own collections; the ones it holds already stay as they are. */
    fun addAllToCollection(wallpapers: List<ConnectionsWallpaper>, collectionId: String) {
        viewModelScope.launch {
            val name = collections.value.firstOrNull { it.id == collectionId }?.name.orEmpty()
            runCatchingCancellable { collectionStore.addAll(collectionId, wallpapers) }
                .onSuccess { _messages.send(ConnectionsMessage.AddedToCollection(name)) }
                .onFailure { _messages.send(ConnectionsMessage.Failed(it.message)) }
        }
    }

    /** A new collection of the member's own, starting with [wallpapers]. */
    fun addToNewCollection(wallpapers: List<ConnectionsWallpaper>, name: String) {
        viewModelScope.launch {
            runCatchingCancellable { collectionStore.create(name, wallpapers) }
                .onSuccess { _messages.send(ConnectionsMessage.AddedToCollection(it.name)) }
                .onFailure { _messages.send(ConnectionsMessage.Failed(it.message)) }
        }
    }

    /** A new, empty collection; [onCreated] gets its id to open it. */
    fun createCollection(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            runCatchingCancellable { collectionStore.create(name) }
                .onSuccess { onCreated(it.id) }
                .onFailure { _messages.send(ConnectionsMessage.Failed(it.message)) }
        }
    }

    /** Runs [block] with a live access token; one 401 earns one forced refresh and one retry. */
    private suspend fun <T> withToken(block: suspend (String) -> T): T {
        val token = account.accessToken() ?: throw SignInNeededException()
        return try {
            block(token)
        } catch (e: ConnectionsApiException) {
            if (e.status != 401) throw e
            block(account.accessToken(forceRefresh = true) ?: throw SignInNeededException())
        }
    }

    private fun failure(e: Throwable): ConnectionsMessage =
        if (e is SignInNeededException) ConnectionsMessage.SignInNeeded else ConnectionsMessage.Failed(e.message)

    /** Starts the list over when what Explore asks the gallery for has changed; a fit waits for the screen. */
    private fun refresh() {
        val current = _state.value
        if (!shapeKnown) return
        val fitting = current.shape == ExploreShape.FIT
        if (fitting && current.device == null) return
        val next = PageRequest(
            query = current.query,
            tag = current.tag,
            orientation = current.shape.requested(current.device),
            order = current.device.takeIf { fitting }
        )
        if (next == request) return
        request = next
        pageJob?.cancel()
        _state.update { it.copy(wallpapers = emptyList(), nextCursor = null) }
        load(next, cursor = null)
    }

    private fun load(request: PageRequest, cursor: String?) {
        _state.update { it.copy(loading = true, failed = false) }
        pageJob = viewModelScope.launch {
            runCatchingCancellable { api.wallpapers(request.query, request.tag, request.orientation?.wire, cursor) }
                .onSuccess { page ->
                    val items = request.order?.let { page.items.closestFirst(it) } ?: page.items
                    _state.update {
                        it.copy(
                            wallpapers = (it.wallpapers + items).distinctBy { wallpaper -> wallpaper.id },
                            nextCursor = page.nextCursor,
                            loading = false
                        )
                    }
                }
                .onFailure { _state.update { it.copy(loading = false, failed = true) } }
        }
    }

    private fun loadTags() {
        viewModelScope.launch {
            runCatchingCancellable { api.tags() }.onSuccess { tags ->
                _state.update { it.copy(tags = tags.distinctBy { tag -> tag.tag }) }
            }
        }
    }
}
