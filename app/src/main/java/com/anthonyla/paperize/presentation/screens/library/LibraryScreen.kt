package com.anthonyla.paperize.presentation.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.anthonyla.paperize.R
import com.anthonyla.paperize.domain.model.AlbumSummary
import com.anthonyla.paperize.presentation.common.theme.AppGrid
import com.anthonyla.paperize.presentation.common.theme.AppIconSizes
import com.anthonyla.paperize.presentation.common.theme.AppSpacing
import com.anthonyla.paperize.presentation.screens.library.components.AlbumItem

/** Albums are made from what they hold: a folder (named after it) or picked photos, never a name first. */
@Composable
fun LibraryScreen(
    albums: List<AlbumSummary>,
    onViewAlbum: (String) -> Unit,
    onAddFolder: () -> Unit,
    onAddPhotos: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    var showAddMenu by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            Box {
                LargeFloatingActionButton(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    onClick = { showAddMenu = true },
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.add_album),
                        modifier = Modifier.size(AppIconSizes.medium)
                    )
                }
                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_folder)) },
                        leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            onAddFolder()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_photos)) },
                        leadingIcon = { Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            onAddPhotos()
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        if (albums.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(AppIconSizes.huge),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    )
                    Text(
                        text = stringResource(R.string.no_albums_yet),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Button(onClick = onAddFolder) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.add_folder))
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                columns = GridCells.Adaptive(AppGrid.itemMinSize),
                contentPadding = PaddingValues(AppSpacing.gridPadding),
                horizontalArrangement = Arrangement.Start
            ) {
                items(albums, key = { album -> album.id }) { album ->
                    AlbumItem(
                        album = album,
                        onAlbumViewClick = { onViewAlbum(album.id) },
                        modifier = Modifier.padding(AppSpacing.gridPadding)
                    )
                }
            }
        }
    }
}
