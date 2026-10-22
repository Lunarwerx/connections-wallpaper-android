package com.anthonyla.paperize.connections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
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
 * A Library add that asked for the account waits in the view model. While one waits, the account sheet opens for
 * it ([onNeedAccount]); once the member is signed in it runs by itself, as Save offline does.
 */
@Composable
internal fun ResumeLibraryAdd(viewModel: ConnectionsViewModel, signedIn: Boolean, onNeedAccount: () -> Unit) {
    val waiting by viewModel.libraryWaiting.collectAsStateWithLifecycle()
    LaunchedEffect(waiting) { if (waiting != null && !signedIn) onNeedAccount() }
    LaunchedEffect(signedIn) { if (signedIn) viewModel.resumeLibrary() }
}
