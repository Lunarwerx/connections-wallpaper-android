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
    val loading: Boolean = false,
    val failed: Boolean = false,
    /** Wallpapers being added to an album, and being set on the screen, by id. */
    val saving: Set<String> = emptySet(),
    val setting: Set<String> = emptySet()
)

sealed interface ConnectionsMessage {
    data class Saved(val albumName: String) : ConnectionsMessage
    data object AlreadyInAlbum : ConnectionsMessage
    data class WallpaperSet(val target: WallpaperTarget) : ConnectionsMessage
    data object SignInNeeded : ConnectionsMessage
    data class Failed(val reason: String?) : ConnectionsMessage
}

private class SignInNeededException : IOException("Connections asked for a sign-in to send this wallpaper")

@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    private val api: ConnectionsApi,
    private val account: ConnectionsAccount,
    private val originals: ConnectionsOriginals,
    private val createAlbumUseCase: CreateAlbumUseCase,
    albumRepository: AlbumRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectionsUiState())
    val state: StateFlow<ConnectionsUiState> = _state.asStateFlow()

    val albums: StateFlow<List<AlbumSummary>> =
        albumRepository.getAlbumSummaries().stateWhileSubscribed(viewModelScope, emptyList())

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
        if (tag == _state.value.tag) return
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
            val message = runCatchingCancellable { withToken { originals.setAsWallpaper(wallpaper, target, it) } }
                .fold(onSuccess = { ConnectionsMessage.WallpaperSet(target) }, onFailure = ::failure)
            _state.update { it.copy(setting = it.setting - wallpaper.id) }
            _messages.send(message)
        }
    }

    private fun addToAlbum(wallpaper: ConnectionsWallpaper, albumId: String, albumName: String) {
        if (wallpaper.id in _state.value.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = it.saving + wallpaper.id) }
            val message = runCatchingCancellable { withToken { originals.addToAlbum(wallpaper, albumId, it) } }.fold(
                onSuccess = { added -> if (added) ConnectionsMessage.Saved(albumName) else ConnectionsMessage.AlreadyInAlbum },
                onFailure = ::failure
            )
            _state.update { it.copy(saving = it.saving - wallpaper.id) }
            _messages.send(message)
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
            runCatchingCancellable { api.tags() }.onSuccess { tags ->
                _state.update { it.copy(tags = tags.distinctBy { tag -> tag.tag }) }
            }
        }
    }
}
