package com.anthonyla.paperize.presentation.screens.wallpaper.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Size
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.domain.model.AlbumSummary
import com.anthonyla.paperize.presentation.common.theme.AppIconSizes
import com.anthonyla.paperize.presentation.common.theme.AppSpacing

/**
 * The picker's edits. Taps change [selected] only; [close] hands the result to [onApply] the first time the
 * sheet closes, and a later close applies nothing more. Checked albums keep the order they were checked in.
 */
class AlbumPickerDraft(initial: List<String>, private val onApply: (List<String>) -> Unit) {
    var selected by mutableStateOf(initial)
        private set
    private var closed = false

    fun toggle(albumId: String) {
        selected = if (albumId in selected) selected - albumId else selected + albumId
    }

    fun close() {
        if (closed) return
        closed = true
        onApply(selected)
    }
}

/**
 * Multi-select of a screen's albums. Each row toggles its checkbox; the draft is applied once when the sheet
 * closes (Done, a swipe down, or back). [onAddFolder] applies the draft first, then goes to add a folder.
 * An album with no pictures is not checked: [onEmptyAlbum] reports it instead (one already checked can be unchecked).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumSelectionBottomSheet(
    albums: List<AlbumSummary>,
    selectedAlbumIds: List<String>,
    onApply: (List<String>) -> Unit,
    onAddFolder: () -> Unit,
    onEmptyAlbum: (AlbumSummary) -> Unit,
    onDismiss: () -> Unit
) {
    val draft = remember { AlbumPickerDraft(selectedAlbumIds, onApply) }
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    ModalBottomSheet(
        onDismissRequest = {
            draft.close()
            onDismiss()
        },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = AppSpacing.large)
        ) {
            Text(
                text = stringResource(R.string.select_album),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = AppSpacing.extraLarge, vertical = AppSpacing.large),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            LazyColumn {
                items(albums, key = { it.id }) { album ->
                    AlbumSelectionItem(
                        album = album,
                        isSelected = album.id in draft.selected,
                        onClick = {
                            // An empty album can still be unchecked; it only cannot be checked.
                            if (album.wallpaperCount == 0 && album.id !in draft.selected) onEmptyAlbum(album) else draft.toggle(album.id)
                        }
                    )
                }
                if (albums.isNotEmpty()) item(key = "add_folder") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                draft.close()
                                onAddFolder()
                            }
                            .padding(horizontal = AppSpacing.extraLarge, vertical = AppSpacing.medium),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(AppIconSizes.extraLarge), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.width(AppSpacing.large))
                        Text(
                            text = stringResource(R.string.add_folder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (albums.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AppSpacing.extraLarge),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.large)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(AppIconSizes.extraLarge),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.no_albums_yet),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Button(onClick = {
                        draft.close()
                        onAddFolder()
                    }) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.add_folder))
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.extraLarge, vertical = AppSpacing.medium),
                horizontalArrangement = Arrangement.End
            ) {
                Button(onClick = {
                    draft.close()
                    onDismiss()
                }) {
                    Text(stringResource(R.string.done))
                }
            }
        }
    }
}

@Composable
private fun AlbumSelectionItem(
    album: AlbumSummary,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cover = rememberAsyncImagePainter(
        ImageRequest.Builder(context).data(album.coverUri).size(Size(Constants.LIST_THUMBNAIL_SIZE, Constants.LIST_THUMBNAIL_SIZE)).build(),
        contentScale = ContentScale.Crop
    )
    val coverState by cover.state.collectAsState()
    val selectedDescription = stringResource(R.string.currently_selected_album)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.extraLarge, vertical = AppSpacing.medium),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(AppIconSizes.extraLarge)
                .aspectRatio(1f)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f),
                    shape = MaterialTheme.shapes.small
                )
                .clip(MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center
        ) {
            if (coverState is AsyncImagePainter.State.Success) {
                Image(
                        painter = cover,
                        contentDescription = album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(AppIconSizes.extraLarge)
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.PhotoAlbum,
                    contentDescription = album.name,
                    modifier = Modifier.size(AppIconSizes.medium),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
            }
        }

        Spacer(modifier = Modifier.width(AppSpacing.large))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = pluralStringResource(R.plurals.wallpaper_count, album.wallpaperCount, album.wallpaperCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Checkbox(
            checked = isSelected,
            onCheckedChange = null,
            modifier = Modifier.semantics { if (isSelected) stateDescription = selectedDescription }
        )
    }
}
