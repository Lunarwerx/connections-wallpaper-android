package com.anthonyla.paperize.connections

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.anthonyla.paperize.core.runCatchingCancellable
import com.anthonyla.paperize.presentation.common.navigation.CollectionRoute
import com.anthonyla.paperize.presentation.common.stateWhileSubscribed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where a collection stands offline. */
sealed interface OfflineState {
    data object NotSaved : OfflineState
    data class Saving(val done: Int, val total: Int) : OfflineState
    data object Saved : OfflineState
    /** Saved before, and it now holds pictures that save did not. */
    data object Stale : OfflineState
}

data class CollectionUiState(
    val name: String = "",
    val own: Boolean = false,
    val wallpapers: List<ConnectionsWallpaper> = emptyList(),
    val count: Int = 0,
    val loading: Boolean = true,
    val failed: Boolean = false,
    /** An own collection that is no longer on the phone (deleted). */
    val missing: Boolean = false,
    val offline: OfflineState = OfflineState.NotSaved,
    /** What the phone keeps of its pictures, by id, so a saved collection shows with no internet. */
    val images: Map<String, Any> = emptyMap()
)

/** The gallery's pages of one tag, as far as they have been read. */
private data class TagPages(
    val items: List<ConnectionsWallpaper> = emptyList(),
    val cursor: String? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val loaded: Boolean = false,
    val total: Int? = null
)

/**
 * One collection's page: a member's own collection from the phone, or a gallery tag read page by page (and, once
 * saved offline, shown from what was saved whenever the gallery cannot be reached).
 */
@HiltViewModel
class CollectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: ConnectionsApi,
    private val collections: ConnectionsCollections,
    private val offline: OfflineCollections,
    private val originals: ConnectionsOriginals
) : ViewModel() {

    val id: String = savedStateHandle.toRoute<CollectionRoute>().id
    private val tag: String? = id.takeIf { it.startsWith(TAG_COLLECTION) }?.removePrefix(TAG_COLLECTION)

    private val record = collections.all.map { all -> all.firstOrNull { it.id == id } }.distinctUntilChanged()
    private val pages = MutableStateFlow(TagPages())
    private val images = MutableStateFlow<Map<String, Any>>(emptyMap())

    val state: StateFlow<CollectionUiState> = combine(
        record,
        pages,
        offline.progress.map { it[id] }.distinctUntilChanged(),
        images
    ) { record, pages, progress, images ->
        val list = listFor(record, pages)
        val own = tag == null
        CollectionUiState(
            name = record?.name ?: tagTitle(tag.orEmpty()),
            own = own,
            wallpapers = list,
            count = if (own) list.size else pages.total ?: record?.wallpapers?.size ?: list.size,
            loading = !own && pages.loading,
            failed = !own && pages.failed,
            missing = own && record == null,
            offline = offlineState(record, pages, list, progress),
            images = images
        )
    }.stateWhileSubscribed(viewModelScope, CollectionUiState(own = tag == null))

    /** How a save offline of this collection went, wherever it was started. */
    val results: Flow<OfflineResult> = offline.results.filter { it.id == id }

    init {
        if (tag != null) {
            load(cursor = null)
            loadCount(tag)
        }
        // A saved collection's pictures show from the phone, offline too.
        viewModelScope.launch {
            combine(record, pages) { record, pages -> record to listFor(record, pages) }.collectLatest { (record, list) ->
                images.value = if (record?.savedIds.isNullOrEmpty()) emptyMap() else originals.localImages(list)
            }
        }
    }

    fun loadMore() {
        val current = pages.value
        if (tag == null || current.loading || !current.loaded || current.cursor == null) return
        load(current.cursor)
    }

    fun retry() {
        if (tag == null) return
        if (pages.value.loaded) loadMore() else load(cursor = null)
    }

    /** Saves it offline; the account is asked for first (OfflineCollections). */
    fun saveOffline() {
        viewModelScope.launch {
            val saved = runCatchingCancellable { record.first() }.getOrNull()
            val collection = when (tag) {
                null -> saved ?: return@launch
                else -> saved ?: WallpaperCollection(id = id, name = tagTitle(tag), tag = tag)
            }
            offline.start(collection)
        }
    }

    fun stopSaving() = offline.stop(id)

    fun rename(name: String) {
        viewModelScope.launch { runCatchingCancellable { collections.rename(id, name) } }
    }

    fun delete() {
        viewModelScope.launch { runCatchingCancellable { collections.delete(id) } }
    }

    /** An own collection's list; a tag's pages once the gallery answers, and what was saved until then (or offline). */
    private fun listFor(record: WallpaperCollection?, pages: TagPages): List<ConnectionsWallpaper> = when {
        tag == null -> record?.wallpapers.orEmpty()
        pages.loaded -> pages.items
        else -> record?.wallpapers.orEmpty()
    }

    private fun offlineState(
        record: WallpaperCollection?,
        pages: TagPages,
        list: List<ConnectionsWallpaper>,
        progress: OfflineProgress?
    ): OfflineState {
        if (progress != null) return OfflineState.Saving(progress.done, progress.total)
        if (record?.savedAt == null) return OfflineState.NotSaved
        val saved = record.savedIds.toSet()
        val grown = list.any { it.id !in saved } || (tag != null && (pages.total ?: 0) > saved.size)
        return if (grown) OfflineState.Stale else OfflineState.Saved
    }

    private fun load(cursor: String?) {
        val tag = tag ?: return
        pages.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            runCatchingCancellable { api.wallpapers("", tag, null, cursor) }
                .onSuccess { page ->
                    pages.update {
                        it.copy(
                            items = ((if (cursor == null) emptyList() else it.items) + page.items).distinctBy { wallpaper -> wallpaper.id },
                            cursor = page.nextCursor,
                            loading = false,
                            loaded = true
                        )
                    }
                }
                .onFailure { pages.update { it.copy(loading = false, failed = true) } }
        }
    }

    private fun loadCount(tag: String) {
        viewModelScope.launch {
            runCatchingCancellable { api.tags() }.onSuccess { tags ->
                tags.firstOrNull { it.tag == tag }?.let { found -> pages.update { it.copy(total = found.count) } }
            }
        }
    }
}
