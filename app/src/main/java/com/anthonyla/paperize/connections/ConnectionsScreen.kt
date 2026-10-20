package com.anthonyla.paperize.connections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.anthonyla.paperize.R
import com.anthonyla.paperize.domain.model.AlbumSummary
import com.anthonyla.paperize.presentation.common.components.AddAlbumDialog
import com.anthonyla.paperize.presentation.common.theme.AppGrid
import com.anthonyla.paperize.presentation.common.theme.AppSpacing
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** The Connections tab: browse and search the public gallery, then add a wallpaper to an album. */
@Composable
fun ConnectionsScreen(
    modifier: Modifier = Modifier,
    viewModel: ConnectionsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val hasKey by viewModel.hasKey.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showKeyDialog by rememberSaveable { mutableStateOf(false) }
    val selected = state.wallpapers.firstOrNull { it.id == selectedId }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                ConnectionsMessage.KeyNeeded -> {
                    showKeyDialog = true
                    null
                }
                is ConnectionsMessage.Saved -> context.getString(R.string.connections_saved, message.albumName)
                ConnectionsMessage.AlreadyInAlbum -> context.getString(R.string.connections_already_in_album)
                is ConnectionsMessage.KeyRefused -> context.getString(R.string.connections_key_refused, message.reason)
                is ConnectionsMessage.Failed ->
                    context.getString(R.string.connections_save_failed, message.reason ?: context.getString(R.string.connections_unknown_error))
            }
            if (text != null) {
                if (message is ConnectionsMessage.Saved) selectedId = null
                snackbarHostState.showSnackbar(text)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            SearchRow(
                query = state.query,
                hasKey = hasKey,
                onSearch = viewModel::search,
                onKeyClick = { showKeyDialog = true }
            )
            TagRow(
                tags = state.tags,
                selectedTag = state.tag,
                onSelect = viewModel::selectTag
            )
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.wallpapers.isEmpty() && state.loading ->
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    state.wallpapers.isEmpty() && state.failed ->
                        CenteredMessage(
                            text = stringResource(R.string.connections_load_failed),
                            action = stringResource(R.string.connections_retry),
                            onAction = viewModel::retry,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    state.wallpapers.isEmpty() ->
                        CenteredMessage(
                            text = stringResource(R.string.connections_empty),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    else -> WallpaperGrid(
                        wallpapers = state.wallpapers,
                        loading = state.loading,
                        failed = state.failed,
                        onRetry = viewModel::retry,
                        onLoadMore = viewModel::loadMore,
                        onOpen = { selectedId = it.id }
                    )
                }
            }
        }
    }

    if (selected != null) {
        WallpaperSheet(
            wallpaper = selected,
            albums = albums,
            hasKey = hasKey,
            saving = state.savingId == selected.id,
            onSave = { album -> viewModel.save(selected, album) },
            onSaveToNewAlbum = { name -> viewModel.saveToNewAlbum(selected, name) },
            onAddKey = { showKeyDialog = true },
            onDismiss = { selectedId = null }
        )
    }

    if (showKeyDialog) {
        ConnectionsKeyDialog(
            hasKey = hasKey,
            onSave = { key ->
                viewModel.saveKey(key)
                showKeyDialog = false
            },
            onRemove = {
                viewModel.clearKey()
                showKeyDialog = false
            },
            onDismiss = { showKeyDialog = false }
        )
    }
}

@Composable
private fun SearchRow(
    query: String,
    hasKey: Boolean,
    onSearch: (String) -> Unit,
    onKeyClick: () -> Unit
) {
    var text by rememberSaveable { mutableStateOf(query) }
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.medium, end = AppSpacing.extraSmall, top = AppSpacing.small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                text = value
                if (value.isEmpty()) onSearch("")
            },
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.connections_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                onSearch(text.trim())
                focusManager.clearFocus()
            })
        )
        IconButton(onClick = onKeyClick) {
            Icon(
                imageVector = if (hasKey) Icons.Filled.Key else Icons.Outlined.Key,
                contentDescription = stringResource(R.string.connections_key_title),
                tint = if (hasKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TagRow(
    tags: List<ConnectionsTag>,
    selectedTag: String?,
    onSelect: (String?) -> Unit
) {
    if (tags.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = AppSpacing.medium, vertical = AppSpacing.extraSmall),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)
    ) {
        item(key = "all") {
            FilterChip(
                selected = selectedTag == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.connections_all)) }
            )
        }
        items(tags, key = { it.tag }) { tag ->
            FilterChip(
                selected = selectedTag == tag.tag,
                onClick = { onSelect(if (selectedTag == tag.tag) null else tag.tag) },
                label = { Text(tag.tag) }
            )
        }
    }
}

@Composable
private fun WallpaperGrid(
    wallpapers: List<ConnectionsWallpaper>,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (ConnectionsWallpaper) -> Unit
) {
    val gridState = rememberLazyStaggeredGridState()
    LaunchedEffect(gridState) {
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }.distinctUntilChanged().filter { it }.collect { onLoadMore() }
    }
    LazyVerticalStaggeredGrid(
        state = gridState,
        columns = StaggeredGridCells.Adaptive(AppGrid.itemMinSize),
        contentPadding = PaddingValues(AppSpacing.gridPadding),
        verticalItemSpacing = AppSpacing.gridSpacing,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.gridSpacing),
        modifier = Modifier.fillMaxSize()
    ) {
        items(wallpapers, key = { it.id }) { wallpaper ->
            WallpaperCard(wallpaper = wallpaper, onClick = { onOpen(wallpaper) })
        }
        if (loading || failed) {
            item(key = "footer", span = StaggeredGridItemSpan.FullLine) {
                Box(modifier = Modifier.fillMaxWidth().padding(AppSpacing.medium), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator() else TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.connections_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun WallpaperCard(wallpaper: ConnectionsWallpaper, onClick: () -> Unit) {
    Card(onClick = onClick, shape = MaterialTheme.shapes.medium) {
        Preview(wallpaper = wallpaper, modifier = Modifier.fillMaxWidth())
        Text(
            text = wallpaper.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = AppSpacing.small, end = AppSpacing.small, top = AppSpacing.small)
        )
        Text(
            text = wallpaper.credit.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = AppSpacing.small, end = AppSpacing.small, bottom = AppSpacing.small)
        )
    }
}

@Composable
private fun Preview(wallpaper: ConnectionsWallpaper, modifier: Modifier = Modifier) {
    val placeholder = wallpaper.color
        ?.let { runCatching { Color(it.toColorInt()) }.getOrNull() }
        ?: MaterialTheme.colorScheme.surfaceVariant
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(wallpaper.previewUrl)
            .crossfade(true)
            .build(),
        contentDescription = wallpaper.title,
        placeholder = ColorPainter(placeholder),
        contentScale = ContentScale.Crop,
        modifier = modifier.aspectRatio(wallpaper.previewWidth.toFloat() / wallpaper.previewHeight.coerceAtLeast(1))
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WallpaperSheet(
    wallpaper: ConnectionsWallpaper,
    albums: List<AlbumSummary>,
    hasKey: Boolean,
    saving: Boolean,
    onSave: (AlbumSummary) -> Unit,
    onSaveToNewAlbum: (String) -> Unit,
    onAddKey: () -> Unit,
    onDismiss: () -> Unit
) {
    var showNewAlbum by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.extraLarge)
                .padding(bottom = AppSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
        ) {
            Preview(
                wallpaper = wallpaper,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
            )
            Text(text = wallpaper.title, style = MaterialTheme.typography.titleLarge)
            Text(
                text = listOfNotNull(wallpaper.credit, licenseLabel(wallpaper.license), "${wallpaper.width} × ${wallpaper.height}")
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            wallpaper.sourceUrl?.let { source ->
                TextButton(onClick = { uriHandler.openUri(source) }, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.connections_view_source))
                }
            }
            if (!hasKey) {
                Text(
                    text = stringResource(R.string.connections_key_needed),
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = onAddKey) { Text(stringResource(R.string.connections_add_key)) }
            } else {
                Text(
                    text = stringResource(R.string.connections_add_to_album),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = AppSpacing.small)
                )
                if (saving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                albums.forEach { album ->
                    ListItem(
                        headlineContent = { Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(pluralStringResource(R.plurals.wallpaper_count, album.wallpaperCount, album.wallpaperCount))
                        },
                        leadingContent = { Icon(Icons.Filled.PhotoAlbum, contentDescription = null) },
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(enabled = !saving) { onSave(album) }
                    )
                }
                TextButton(onClick = { showNewAlbum = true }, enabled = !saving) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(
                        text = stringResource(R.string.connections_new_album),
                        modifier = Modifier.padding(start = AppSpacing.extraSmall)
                    )
                }
            }
        }
    }

    if (showNewAlbum) {
        val taken = stringResource(R.string.album_name_exists_error)
        var error by remember { mutableStateOf<String?>(null) }
        AddAlbumDialog(
            onDismiss = { showNewAlbum = false },
            onConfirm = { name ->
                if (albums.any { it.name.equals(name, ignoreCase = true) }) {
                    error = taken
                } else {
                    showNewAlbum = false
                    onSaveToNewAlbum(name)
                }
            },
            errorMessage = error,
            isSaving = false
        )
    }
}

@Composable
private fun ConnectionsKeyDialog(
    hasKey: Boolean,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by rememberSaveable { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connections_key_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text(stringResource(if (hasKey) R.string.connections_key_saved_body else R.string.connections_key_body))
                TextButton(onClick = { uriHandler.openUri(ConnectionsApi.KEYS_URL) }, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.connections_key_get))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(if (hasKey) R.string.connections_key_replace else R.string.connections_key_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }, enabled = value.isNotBlank()) {
                Text(stringResource(R.string.connections_key_save))
            }
        },
        dismissButton = {
            if (hasKey) {
                TextButton(onClick = onRemove) { Text(stringResource(R.string.connections_key_remove)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.connections_key_cancel)) }
            }
        }
    )
}

@Composable
private fun CenteredMessage(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {}
) {
    Column(
        modifier = modifier.padding(AppSpacing.extraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun licenseLabel(license: String): String = when (license) {
    "cc0" -> stringResource(R.string.connections_license_cc0)
    "public_domain" -> stringResource(R.string.connections_license_public_domain)
    else -> stringResource(R.string.connections_license_original)
}

private const val LOAD_MORE_THRESHOLD = 6
