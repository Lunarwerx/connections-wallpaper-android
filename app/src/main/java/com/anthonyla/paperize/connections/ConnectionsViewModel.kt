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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectionsUiState(
    val query: String = "",
    val tag: String? = null,
    val tags: List<ConnectionsTag> = emptyList(),
    val wallpapers: List<ConnectionsWallpaper> = emptyList(),
    val nextCursor: String? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val savingId: String? = null
)

sealed interface ConnectionsMessage {
    data class Saved(val albumName: String) : ConnectionsMessage
    data object AlreadyInAlbum : ConnectionsMessage
    data object KeyNeeded : ConnectionsMessage
    data class KeyRefused(val reason: String) : ConnectionsMessage
    data class Failed(val reason: String?) : ConnectionsMessage
}

@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    private val api: ConnectionsApi,
    private val keyStore: ConnectionsKeyStore,
    private val saveWallpaper: SaveConnectionsWallpaperUseCase,
    private val createAlbumUseCase: CreateAlbumUseCase,
    albumRepository: AlbumRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectionsUiState())
    val state: StateFlow<ConnectionsUiState> = _state.asStateFlow()

    val albums: StateFlow<List<AlbumSummary>> =
        albumRepository.getAlbumSummaries().stateWhileSubscribed(viewModelScope, emptyList())

    val hasKey: StateFlow<Boolean> =
        keyStore.key.map { !it.isNullOrBlank() }.stateWhileSubscribed(viewModelScope, false)

    private val _messages = Channel<ConnectionsMessage>(Channel.BUFFERED)
    val messages: Flow<ConnectionsMessage> = _messages.receiveAsFlow()

    private var pageJob: Job? = null

    init {
        reload()
        loadTags()
    }

    fun search(query: String) {
        if (query == _state.value.query) return
        _state.update { it.copy(query = query) }
        reload()
    }

    fun selectTag(tag: String?) {
        _state.update { it.copy(tag = tag) }
        reload()
    }

    fun retry() {
        if (_state.value.tags.isEmpty()) loadTags()
        if (_state.value.wallpapers.isEmpty()) reload() else loadMore()
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.nextCursor == null) return
        load(current.nextCursor)
    }

    fun save(wallpaper: ConnectionsWallpaper, album: AlbumSummary) = save(wallpaper, album.id, album.name)

    fun saveToNewAlbum(wallpaper: ConnectionsWallpaper, name: String) {
        viewModelScope.launch {
            runCatchingCancellable { createAlbumUseCase(name) }
                .onSuccess { album -> save(wallpaper, album.id, album.name) }
                .onFailure { _messages.send(ConnectionsMessage.Failed(it.message)) }
        }
    }

    fun saveKey(key: String) {
        viewModelScope.launch { keyStore.save(key) }
    }

    fun clearKey() {
        viewModelScope.launch { keyStore.clear() }
    }

    private fun save(wallpaper: ConnectionsWallpaper, albumId: String, albumName: String) {
        viewModelScope.launch {
            val key = keyStore.current()
            if (key.isNullOrBlank()) {
                _messages.send(ConnectionsMessage.KeyNeeded)
                return@launch
            }
            _state.update { it.copy(savingId = wallpaper.id) }
            val message = runCatchingCancellable { saveWallpaper(wallpaper, albumId, key) }.fold(
                onSuccess = { added -> if (added) ConnectionsMessage.Saved(albumName) else ConnectionsMessage.AlreadyInAlbum },
                onFailure = { e ->
                    if (e is ConnectionsApiException && e.keyRefused) ConnectionsMessage.KeyRefused(e.message)
                    else ConnectionsMessage.Failed(e.message)
                }
            )
            _state.update { it.copy(savingId = null) }
            _messages.send(message)
        }
    }

    private fun reload() {
        pageJob?.cancel()
        _state.update { it.copy(wallpapers = emptyList(), nextCursor = null, loading = false) }
        load(cursor = null)
    }

    private fun load(cursor: String?) {
        val request = _state.value
        _state.update { it.copy(loading = true, failed = false) }
        pageJob = viewModelScope.launch {
            runCatchingCancellable { api.wallpapers(request.query, request.tag, cursor) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(
                            wallpapers = (it.wallpapers + page.items).distinctBy { wallpaper -> wallpaper.id },
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
            runCatchingCancellable { api.tags() }.onSuccess { tags -> _state.update { it.copy(tags = tags) } }
        }
    }
}
