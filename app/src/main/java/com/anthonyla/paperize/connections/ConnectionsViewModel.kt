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

sealed interface ConnectionsMessage {
    data class Saved(val albumName: String) : ConnectionsMessage
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
    private val createAlbumUseCase: CreateAlbumUseCase,
    private val preferences: ExplorePreferences,
    albumRepository: AlbumRepository,
    private val collectionStore: ConnectionsCollections
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectionsUiState())
    val state: StateFlow<ConnectionsUiState> = _state.asStateFlow()

    val albums: StateFlow<List<AlbumSummary>> =
        albumRepository.getAlbumSummaries().stateWhileSubscribed(viewModelScope, emptyList())

    /** The phone's collections: the member's own, and gallery tags saved offline. */
    val collections: StateFlow<List<WallpaperCollection>> =
        collectionStore.all.stateWhileSubscribed(viewModelScope, emptyList())

    /** The cards for Explore's Collections shelf and the collections manager. */
    val cards: StateFlow<List<CollectionCard>> =
        combine(collectionStore.all, _state.map { it.tags }.distinctUntilChanged()) { saved, tags ->
            collectionCards(saved, tags, originals::savedPreview)
        }.flowOn(Dispatchers.IO).stateWhileSubscribed(viewModelScope, emptyList())

    private val _messages = Channel<ConnectionsMessage>(Channel.BUFFERED)
    val messages: Flow<ConnectionsMessage> = _messages.receiveAsFlow()

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

    fun addToAlbum(wallpaper: ConnectionsWallpaper, album: AlbumSummary) = addToAlbum(wallpaper, album.id, album.name)

    fun addToNewAlbum(wallpaper: ConnectionsWallpaper, name: String) {
        viewModelScope.launch {
            runCatchingCancellable { createAlbumUseCase(name) }
                .onSuccess { album -> addToAlbum(wallpaper, album.id, album.name) }
                .onFailure { _messages.send(ConnectionsMessage.Failed(it.message)) }
        }
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

    private fun addToAlbum(wallpaper: ConnectionsWallpaper, albumId: String, albumName: String) {
        if (wallpaper.id in _state.value.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = it.saving + wallpaper.id) }
            val message = runCatchingCancellable { originals.addToAlbum(wallpaper, albumId) }.fold(
                onSuccess = { added -> if (added) ConnectionsMessage.Saved(albumName) else ConnectionsMessage.AlreadyInAlbum },
                onFailure = ::failure
            )
            _state.update { it.copy(saving = it.saving - wallpaper.id) }
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

    /** A new collection of the member's own, starting with [wallpaper]. */
    fun addToNewCollection(wallpaper: ConnectionsWallpaper, name: String) {
        viewModelScope.launch {
            runCatchingCancellable { collectionStore.create(name, wallpaper) }
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
