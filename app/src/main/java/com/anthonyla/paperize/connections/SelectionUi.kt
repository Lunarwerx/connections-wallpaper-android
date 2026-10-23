package com.anthonyla.paperize.connections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anthonyla.paperize.R

private enum class SelectionSheet { Album, Collection }

/**
 * The bar a wall shows in selection mode, in place of its search: how many are picked, Select all, and what the
 * picked pictures do: Add to library (one album, one pick) and Add to collection (an own collection or a new one).
 * The back key leaves selection mode first. Explore and a collection's page both show it.
 */
@Composable
internal fun SelectionBar(
    selection: WallSelection,
    wallpapers: List<ConnectionsWallpaper>,
    viewModel: ConnectionsViewModel
) {
    BackHandler { selection.clear() }
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<SelectionSheet?>(null) }
    val picked = selection.picked(wallpapers)

    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.barInsets().padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            IconButton(onClick = selection::clear) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.connections_close))
            }
            Text(
                text = stringResource(R.string.connections_selected, picked.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            IconButton(onClick = { selection.selectAll(wallpapers.map { it.id }) }) {
                Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.connections_select_all))
            }
            IconButton(onClick = { sheet = SelectionSheet.Album }, enabled = picked.isNotEmpty()) {
                Icon(Icons.Outlined.LibraryAdd, contentDescription = stringResource(R.string.connections_add_to_library))
            }
            IconButton(onClick = { sheet = SelectionSheet.Collection }, enabled = picked.isNotEmpty()) {
                Icon(Icons.Outlined.BookmarkAdd, contentDescription = stringResource(R.string.connections_add_to_collection))
            }
        }
    }

    when (sheet) {
        SelectionSheet.Album -> AlbumSheet(
            albums = albums,
            onPick = { album ->
                sheet = null
                selection.clear()
                viewModel.addToLibrary(picked, LibraryTarget.Existing(album))
            },
            onNewAlbum = { name ->
                sheet = null
                selection.clear()
                viewModel.addToLibrary(picked, LibraryTarget.New(name))
            },
            onDismiss = { sheet = null }
        )
        SelectionSheet.Collection -> CollectionSheet(
            collections = collections.filter { it.own }.sortedByDescending { it.createdAt },
            wallpapers = picked,
            onToggle = { collection ->
                sheet = null
                selection.clear()
                viewModel.addAllToCollection(picked, collection.id)
            },
            onNew = { name ->
                sheet = null
                selection.clear()
                viewModel.addToNewCollection(picked, name)
            },
            onDismiss = { sheet = null }
        )
        null -> Unit
    }
}

/**
 * The bar the collections show in selection mode, in place of the page's own: how many are picked, Select all,
 * Add to library (each picked collection becomes its own album, as Add to library on its page does), and Delete
 * when every picked one is the member's own. The back key leaves selection mode first. The shelf and the
 * manager both show it.
 */
@Composable
internal fun CollectionSelectionBar(
    selection: WallSelection,
    cards: List<CollectionCard>,
    onAddToLibrary: (List<String>) -> Unit,
    onDelete: (List<String>) -> Unit
) {
    BackHandler { selection.clear() }
    val haptics = LocalHapticFeedback.current
    var deleting by rememberSaveable { mutableStateOf(false) }
    val picked = cards.filter { it.id in selection.ids }
    // Picked cards that are gone (a collection deleted, the gallery's list changed) leave nothing to act on.
    LaunchedEffect(picked.isEmpty()) { if (picked.isEmpty()) selection.clear() }
    // An empty collection has nothing to add, and the gallery's collections are never deleted.
    val addable = picked.filter { it.count > 0 }
    val deletable = picked.isNotEmpty() && picked.all { it.own }

    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.barInsets().padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            IconButton(onClick = selection::clear) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.connections_close))
            }
            Text(
                text = stringResource(R.string.connections_selected, picked.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            IconButton(onClick = { selection.selectAll(cards.map { it.id }) }) {
                Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.connections_select_all))
            }
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onAddToLibrary(addable.map { it.id })
                    selection.clear()
                },
                enabled = addable.isNotEmpty()
            ) {
                Icon(Icons.Outlined.LibraryAdd, contentDescription = stringResource(R.string.connections_add_to_library))
            }
            if (deletable) {
                IconButton(onClick = { deleting = true }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.connections_delete))
                }
            }
        }
    }

    if (deleting) {
        val one = picked.singleOrNull()
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = {
                Text(
                    if (one != null) stringResource(R.string.connections_delete_collection_title, one.name)
                    else pluralStringResource(R.plurals.connections_delete_collections_title, picked.size, picked.size)
                )
            },
            text = {
                Text(stringResource(if (one != null) R.string.connections_delete_collection_body else R.string.connections_delete_collections_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    onDelete(picked.map { it.id })
                    selection.clear()
                }) {
                    Text(stringResource(R.string.connections_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

/**
 * A selection bar takes a page's top app bar's place, so it keeps clear of the status bar as that bar does. Under
 * Home's own top bar (Explore) the status bar is already taken care of, and this adds nothing.
 */
@Composable
private fun Modifier.barInsets(): Modifier = fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets)

/**
 * A Library add that asked for the account waits in the view model: pictures, or whole collections. While one
 * waits, the account sheet opens for it ([onNeedAccount]); once the member is signed in it runs by itself.
 */
@Composable
internal fun ResumeLibraryAdd(viewModel: ConnectionsViewModel, signedIn: Boolean, onNeedAccount: () -> Unit) {
    val waiting by viewModel.libraryWaiting.collectAsStateWithLifecycle()
    val collections by viewModel.collectionsWaiting.collectAsStateWithLifecycle()
    LaunchedEffect(waiting, collections) { if ((waiting != null || collections != null) && !signedIn) onNeedAccount() }
    LaunchedEffect(signedIn) { if (signedIn) viewModel.resumeLibrary() }
}

/** The account sheet's title while a Library add waits for the account, named for what it adds; null while none waits. */
@Composable
internal fun libraryWaitingTitle(viewModel: ConnectionsViewModel): String? {
    val pictures by viewModel.libraryWaiting.collectAsStateWithLifecycle()
    val collections by viewModel.collectionsWaiting.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val ids = collections
    return when {
        ids != null -> {
            val name = ids.singleOrNull()?.let { id -> cards.firstOrNull { it.id == id }?.name }
            if (name != null) stringResource(R.string.connections_collection_library_sign_in, name)
            else pluralStringResource(R.plurals.connections_collections_library_sign_in, ids.size, ids.size)
        }
        pictures != null -> stringResource(R.string.connections_library_sign_in)
        else -> null
    }
}

/** The account sheet's line under the title while whole collections wait for the account: what adding them gives. */
@Composable
internal fun libraryWaitingBody(viewModel: ConnectionsViewModel): String? {
    val collections by viewModel.collectionsWaiting.collectAsStateWithLifecycle()
    return if (collections != null) stringResource(R.string.connections_offline_sign_in_body) else null
}
