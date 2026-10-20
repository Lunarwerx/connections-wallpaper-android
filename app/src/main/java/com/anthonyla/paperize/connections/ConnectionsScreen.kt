package com.anthonyla.paperize.connections

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.anthonyla.paperize.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** Explore: the Connections gallery as a wall of pictures, with search and tag filters. Tap one to open the viewer. */
@Composable
fun ConnectionsScreen(
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConnectionsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        SearchPill(query = state.query, onSearch = viewModel::search)
        TagRow(tags = state.tags, selectedTag = state.tag, onSelect = viewModel::selectTag)
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.wallpapers.isEmpty() && state.failed -> EmptyState(
                    icon = Icons.Outlined.CloudOff,
                    text = stringResource(R.string.connections_load_failed),
                    action = stringResource(R.string.connections_retry),
                    onAction = viewModel::retry
                )
                state.wallpapers.isEmpty() && state.loading -> SkeletonGrid()
                state.wallpapers.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    text = stringResource(R.string.connections_empty)
                )
                else -> WallpaperGrid(
                    wallpapers = state.wallpapers,
                    loading = state.loading,
                    failed = state.failed,
                    onRetry = viewModel::retry,
                    onLoadMore = viewModel::loadMore,
                    onOpen = onOpen
                )
            }
        }
    }
}

@Composable
private fun SearchPill(query: String, onSearch: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(query) }
    val focusManager = LocalFocusManager.current
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    TextField(
        value = text,
        onValueChange = { value ->
            text = value
            if (value.isEmpty()) onSearch("")
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.connections_search_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = if (text.isEmpty()) null else {
            {
                IconButton(onClick = {
                    text = ""
                    onSearch("")
                    focusManager.clearFocus()
                }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.connections_clear_search))
                }
            }
        },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            onSearch(text.trim())
            focusManager.clearFocus()
        })
    )
}

@Composable
private fun TagRow(
    tags: List<ConnectionsTag>,
    selectedTag: String?,
    onSelect: (String?) -> Unit
) {
    if (tags.isEmpty()) return
    val haptics = LocalHapticFeedback.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        item(key = ALL_TAGS_KEY) {
            TagChip(label = stringResource(R.string.connections_all), selected = selectedTag == null) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onSelect(null)
            }
        }
        items(tags, key = { it.tag }) { tag ->
            TagChip(label = tag.tag.replaceFirstChar { it.titlecase() }, selected = selectedTag == tag.tag) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onSelect(if (selectedTag == tag.tag) null else tag.tag)
            }
        }
    }
}

@Composable
private fun TagChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = CircleShape,
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Done, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else null
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WallpaperGrid(
    wallpapers: List<ConnectionsWallpaper>,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (Int) -> Unit
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
        columns = StaggeredGridCells.Adaptive(TILE_MIN_WIDTH),
        contentPadding = GRID_PADDING,
        verticalItemSpacing = TILE_GAP,
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(wallpapers, key = { _, wallpaper -> wallpaper.id }) { index, wallpaper ->
            Tile(wallpaper = wallpaper, onClick = { onOpen(index) })
        }
        if (loading || failed) {
            item(key = FOOTER_KEY, span = StaggeredGridItemSpan.FullLine) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (loading) LoadingIndicator() else FilledTonalButton(onClick = onRetry) {
                        Text(stringResource(R.string.connections_retry))
                    }
                }
            }
        }
    }
}

/** The picture and nothing else; it gives a little under the finger. */
@Composable
private fun Tile(wallpaper: ConnectionsWallpaper, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, label = "tile-press")
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(wallpaper.previewUrl)
            .crossfade(true)
            .build(),
        contentDescription = wallpaper.title,
        placeholder = ColorPainter(wallpaper.swatch()),
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(wallpaper.previewRatio())
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(TILE_SHAPE)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    )
}

@Composable
private fun SkeletonGrid() {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
        label = "skeleton-alpha"
    )
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(TILE_MIN_WIDTH),
        contentPadding = GRID_PADDING,
        verticalItemSpacing = TILE_GAP,
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
        userScrollEnabled = false,
        modifier = Modifier.fillMaxSize()
    ) {
        items(SKELETON_RATIOS.size) { index ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(SKELETON_RATIOS[index])
                    .graphicsLayer { alpha = pulse }
                    .clip(TILE_SHAPE)
                    .background(color)
            )
        }
    }
}

@Composable
private fun EmptyState(
    icon: ImageVector,
    text: String,
    action: String? = null,
    onAction: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (action != null) FilledTonalButton(onClick = onAction) { Text(action) }
    }
}

/** The gallery's own dominant colour, shown while the picture loads. */
@Composable
internal fun ConnectionsWallpaper.swatch(): Color =
    color?.let { runCatching { Color(it.toColorInt()) }.getOrNull() } ?: MaterialTheme.colorScheme.surfaceContainerHigh

internal fun ConnectionsWallpaper.previewRatio(): Float = ratioOf(previewWidth, previewHeight)

internal fun ConnectionsWallpaper.ratio(): Float = ratioOf(width, height)

private fun ratioOf(width: Int, height: Int): Float =
    if (width > 0 && height > 0) (width.toFloat() / height).coerceIn(MIN_RATIO, MAX_RATIO) else 1f

private val TILE_MIN_WIDTH = 160.dp
private val TILE_GAP = 8.dp
private val TILE_SHAPE = RoundedCornerShape(16.dp)
private val GRID_PADDING = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)
private val SKELETON_RATIOS = listOf(1.45f, 0.8f, 1.25f, 1.5f, 0.75f, 1.3f, 1f, 1.4f)
private const val PRESSED_SCALE = 0.96f
private const val MIN_RATIO = 0.4f
private const val MAX_RATIO = 2.5f
private const val LOAD_MORE_THRESHOLD = 6
private const val ALL_TAGS_KEY = "__all__"
private const val FOOTER_KEY = "__footer__"
