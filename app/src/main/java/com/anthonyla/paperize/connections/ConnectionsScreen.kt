package com.anthonyla.paperize.connections

import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.DevicesFold
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.anthonyla.paperize.presentation.common.components.ContextMenuArea
import com.anthonyla.paperize.presentation.common.components.MenuAction
import com.anthonyla.paperize.presentation.common.components.mouseScrollbar
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Explore: the Connections gallery as a wall of pictures, with search, a shape filter that opens on what fits
 * this screen, and tag filters. Tap one to open the viewer; a long press on a picture or a shelf collection picks it.
 */
@Composable
fun ConnectionsScreen(
    onOpen: (Int) -> Unit,
    onOpenCollections: () -> Unit,
    onOpenCollection: (String) -> Unit,
    signedIn: Boolean,
    modifier: Modifier = Modifier,
    searchFocus: FocusRequester? = null,
    viewModel: ConnectionsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val device = rememberDeviceFit()
    LaunchedEffect(device) { viewModel.setDevice(device) }
    val showAllShapes = { viewModel.selectShape(ExploreShape.ALL) }
    // The shelf belongs to the whole gallery: a search or a tag shows just the pictures.
    val shelf = shelfCards(cards).takeIf { it.isNotEmpty() && state.query.isEmpty() && state.tag == null }
    // A new search, tag or shape is a new wall: what was picked on the old one is let go.
    val selection = remember { WallSelection() }
    // Collections picked on the shelf. Picking pictures and picking collections never run at once.
    val shelfSelection = remember { WallSelection() }
    LaunchedEffect(state.query, state.tag, state.shape) {
        selection.clear()
        shelfSelection.clear()
    }
    LaunchedEffect(selection.active) { if (selection.active) shelfSelection.clear() }
    LaunchedEffect(shelfSelection.active) { if (shelfSelection.active) selection.clear() }

    Column(modifier = modifier.fillMaxSize()) {
        when {
            shelfSelection.active -> CollectionSelectionBar(
                selection = shelfSelection,
                cards = shelf.orEmpty(),
                onAddToLibrary = { ids -> viewModel.addCollectionsToLibrary(ids, signedIn) },
                onDelete = viewModel::deleteCollections
            )
            selection.active -> SelectionBar(selection = selection, wallpapers = state.wallpapers, viewModel = viewModel)
            else -> SearchPill(query = state.query, onSearch = viewModel::search, focus = searchFocus)
        }
        FilterRow(
            shape = state.shape,
            device = state.device,
            tags = state.tags,
            selectedTag = state.tag,
            onShape = viewModel::selectShape,
            onTag = viewModel::selectTag
        )
        // With no pictures to show (offline among them), the shelf stays above the reason, so saved collections still open.
        val above: @Composable () -> Unit = {
            if (shelf != null) CollectionsShelf(cards = shelf, selection = shelfSelection, onOpen = onOpenCollection, onSeeAll = onOpenCollections)
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.wallpapers.isEmpty() && state.failed -> Column(Modifier.fillMaxSize()) {
                    above()
                    EmptyState(
                        icon = Icons.Outlined.CloudOff,
                        text = stringResource(R.string.connections_load_failed),
                        action = stringResource(R.string.connections_retry),
                        onAction = viewModel::retry
                    )
                }
                state.wallpapers.isEmpty() && state.loading -> Column(Modifier.fillMaxSize()) {
                    above()
                    SkeletonGrid()
                }
                state.wallpapers.isEmpty() && state.shape != ExploreShape.ALL -> Column(Modifier.fillMaxSize()) {
                    above()
                    EmptyState(
                        icon = Icons.Outlined.SearchOff,
                        text = stringResource(R.string.connections_empty_shape),
                        action = stringResource(R.string.connections_show_all_shapes),
                        onAction = showAllShapes
                    )
                }
                state.wallpapers.isEmpty() -> Column(Modifier.fillMaxSize()) {
                    above()
                    EmptyState(
                        icon = Icons.Outlined.SearchOff,
                        text = stringResource(R.string.connections_empty)
                    )
                }
                else -> WallpaperGrid(
                    wallpapers = state.wallpapers,
                    loading = state.loading,
                    failed = state.failed,
                    onRetry = viewModel::retry,
                    onLoadMore = viewModel::loadMore,
                    onOpen = onOpen,
                    // The end of a list cut down to one shape says so, and offers the rest.
                    onShowAllShapes = showAllShapes.takeIf { state.shape != ExploreShape.ALL && state.nextCursor == null },
                    selection = selection,
                    header = shelf?.let { cards ->
                        @Composable {
                            CollectionsShelf(
                                cards = cards,
                                selection = shelfSelection,
                                onOpen = onOpenCollection,
                                onSeeAll = onOpenCollections,
                                bleed = GRID_SIDE_PADDING
                            )
                        }
                    }
                )
            }
        }
    }
}

/**
 * The screen a wallpaper set from here would fill: the largest window this display allows, which is the
 * whole display as it is held now. Read again on every configuration change, so folding, unfolding,
 * turning, or moving the window to another display picks again.
 */
@Composable
private fun rememberDeviceFit(): DeviceFit {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    return remember(context, configuration, density) {
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val packages = context.packageManager
        DeviceFit.of(
            widthDp = bounds.width() / density,
            heightDp = bounds.height() / density,
            foldable = packages.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE),
            computer = packages.hasSystemFeature(PackageManager.FEATURE_PC)
        )
    }
}

@Composable
private fun SearchPill(query: String, onSearch: (String) -> Unit, focus: FocusRequester?) {
    // Follows the gallery's query, which a restored process starts empty, so field and grid agree.
    var text by remember { mutableStateOf(query) }
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
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
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

/** The shape chip first, then the tags, in one row. */
@Composable
private fun FilterRow(
    shape: ExploreShape,
    device: DeviceFit?,
    tags: List<ConnectionsTag>,
    selectedTag: String?,
    onShape: (ExploreShape) -> Unit,
    onTag: (String?) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        item(key = SHAPE_KEY) {
            ShapeChip(shape = shape, device = device) { choice ->
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onShape(choice)
            }
        }
        if (tags.isEmpty()) return@LazyRow
        item(key = DIVIDER_KEY) {
            VerticalDivider(modifier = Modifier.height(FilterChipDefaults.Height))
        }
        item(key = ALL_TAGS_KEY) {
            TagChip(label = stringResource(R.string.connections_all), selected = selectedTag == null) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onTag(null)
            }
        }
        items(tags, key = { it.tag }) { tag ->
            TagChip(label = tag.tag.replaceFirstChar { it.titlecase() }, selected = selectedTag == tag.tag) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onTag(if (selectedTag == tag.tag) null else tag.tag)
            }
        }
    }
}

/** Which shapes show: what fits this screen (named by its kind), every shape, or one. Opens a menu of them. */
@Composable
private fun ShapeChip(shape: ExploreShape, device: DeviceFit?, onSelect: (ExploreShape) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = shape != ExploreShape.ALL,
            onClick = { open = true },
            label = { Text(shapeLabel(shape, device)) },
            shape = CircleShape,
            leadingIcon = if (shape == ExploreShape.FIT) {
                { Icon(deviceIcon(device), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
            } else null,
            trailingIcon = {
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
            }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ExploreShape.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(shapeLabel(option, device)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                    trailingIcon = if (option == shape) {
                        { Icon(Icons.Filled.Done, contentDescription = null) }
                    } else null
                )
            }
        }
    }
}

@Composable
private fun shapeLabel(shape: ExploreShape, device: DeviceFit?): String = stringResource(
    when (shape) {
        ExploreShape.FIT -> when (device?.kind) {
            DeviceKind.PHONE -> R.string.connections_fits_phone
            DeviceKind.FOLDED -> R.string.connections_fits_folded
            DeviceKind.UNFOLDED -> R.string.connections_fits_unfolded
            DeviceKind.TABLET -> R.string.connections_fits_tablet
            DeviceKind.COMPUTER -> R.string.connections_fits_computer
            null -> R.string.connections_fits_screen
        }
        ExploreShape.ALL -> R.string.connections_all_shapes
        ExploreShape.PORTRAIT -> R.string.connections_portrait
        ExploreShape.LANDSCAPE -> R.string.connections_landscape
        ExploreShape.SQUARE -> R.string.connections_square
    }
)

private fun deviceIcon(device: DeviceFit?): ImageVector = when (device?.kind) {
    DeviceKind.PHONE -> Icons.Outlined.PhoneAndroid
    DeviceKind.FOLDED, DeviceKind.UNFOLDED -> Icons.Outlined.DevicesFold
    DeviceKind.TABLET -> Icons.Outlined.TabletAndroid
    DeviceKind.COMPUTER -> Icons.Outlined.Computer
    null -> Icons.Outlined.Devices
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

/**
 * Pictures in a wall that asks for more near its end. [header] spans the grid's whole width above them (Explore's
 * Collections shelf); [images] shows a picture from the phone when it is saved there (a collection saved offline).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WallpaperGrid(
    wallpapers: List<ConnectionsWallpaper>,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (Int) -> Unit,
    onShowAllShapes: (() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    images: Map<String, Any> = emptyMap(),
    selection: WallSelection = remember { WallSelection() }
) {
    val gridState = rememberLazyStaggeredGridState()
    val open = stringResource(R.string.context_open)
    LaunchedEffect(gridState) {
        // Keyed on the item count too, so a page that still leaves the end in view asks for the next.
        snapshotFlow {
            val info = gridState.layoutInfo
            val nearEnd = (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - LOAD_MORE_THRESHOLD
            nearEnd to info.totalItemsCount
        }.distinctUntilChanged().filter { it.first }.collect { onLoadMore() }
    }
    LazyVerticalStaggeredGrid(
        state = gridState,
        columns = StaggeredGridCells.Adaptive(TILE_MIN_WIDTH),
        contentPadding = GRID_PADDING,
        verticalItemSpacing = TILE_GAP,
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
        modifier = Modifier
            .fillMaxSize()
            .mouseScrollbar(gridState.scrollIndicatorState)
    ) {
        if (header != null) item(key = HEADER_KEY, span = StaggeredGridItemSpan.FullLine) { header() }
        itemsIndexed(wallpapers, key = { _, wallpaper -> wallpaper.id }) { index, wallpaper ->
            ContextMenuArea(actions = listOf(MenuAction(open, Icons.AutoMirrored.Outlined.OpenInNew) { onOpen(index) })) {
                Tile(
                    wallpaper = wallpaper,
                    // In selection mode a tap picks the picture instead of opening it.
                    onClick = { if (selection.active) selection.toggle(wallpaper.id) else onOpen(index) },
                    image = images[wallpaper.id],
                    selected = wallpaper.id in selection.ids,
                    picking = selection.active,
                    onLongClick = { selection.enter(wallpaper.id) }
                )
            }
        }
        if (loading || failed || onShowAllShapes != null) {
            item(key = FOOTER_KEY, span = StaggeredGridItemSpan.FullLine) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    when {
                        loading -> LoadingIndicator()
                        failed -> FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.connections_retry)) }
                        onShowAllShapes != null -> EndOfShape(onShowAllShapes)
                    }
                }
            }
        }
    }
}

@Composable
private fun EndOfShape(onShowAllShapes: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.connections_end_of_shape),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        FilledTonalButton(onClick = onShowAllShapes) { Text(stringResource(R.string.connections_show_all_shapes)) }
    }
}

/**
 * The picture and nothing else; it gives a little under the finger and lifts a little under a mouse, and a
 * keyboard's focus rings it (a laptop window, a tablet's keyboard case).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Tile(
    wallpaper: ConnectionsWallpaper,
    onClick: () -> Unit,
    image: Any? = null,
    selected: Boolean = false,
    picking: Boolean = false,
    onLongClick: () -> Unit = {}
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        when {
            pressed -> PRESSED_SCALE
            hovered -> HOVERED_SCALE
            else -> 1f
        },
        label = "tile-press"
    )
    val ring = MaterialTheme.colorScheme.primary
    Box(modifier = Modifier.fillMaxWidth()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(image ?: wallpaper.previewUrl)
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
                .then(if (focused || selected) Modifier.border(FOCUS_RING, ring, TILE_SHAPE) else Modifier)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                    // A long press starts selection mode with this picture picked; the phone ticks once to say so.
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                )
                .pointerHoverIcon(PointerIcon.Hand)
        )
        // In selection mode every picture shows its circle: filled when picked, as the album view does.
        if (picking) {
            Icon(
                imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) ring else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
            )
        }
    }
}

@Composable
internal fun SkeletonGrid() {
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
internal fun EmptyState(
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
private val GRID_SIDE_PADDING = 16.dp
private val GRID_PADDING = PaddingValues(start = GRID_SIDE_PADDING, end = GRID_SIDE_PADDING, top = 4.dp, bottom = 24.dp)
private val SKELETON_RATIOS = listOf(1.45f, 0.8f, 1.25f, 1.5f, 0.75f, 1.3f, 1f, 1.4f)
private const val PRESSED_SCALE = 0.96f
private const val HOVERED_SCALE = 1.02f
private val FOCUS_RING = 3.dp
private const val MIN_RATIO = 0.4f
private const val MAX_RATIO = 2.5f
private const val LOAD_MORE_THRESHOLD = 6
private const val ALL_TAGS_KEY = "__all__"
private const val SHAPE_KEY = "__shape__"
private const val DIVIDER_KEY = "__divider__"
private const val FOOTER_KEY = "__footer__"
private const val HEADER_KEY = "__header__"
