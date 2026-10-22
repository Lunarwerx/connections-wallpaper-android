package com.anthonyla.paperize.connections

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.anthonyla.paperize.R
import com.anthonyla.paperize.domain.model.AlbumSummary
import com.anthonyla.paperize.presentation.common.components.AddAlbumDialog
import com.anthonyla.paperize.presentation.common.theme.ViewerTheme
import kotlinx.coroutines.launch

private enum class ViewerSheet { Target, Album, Collection }

/** The viewer over Explore's own wall of pictures. */
@Composable
fun ConnectionsViewer(
    viewModel: ConnectionsViewModel,
    startIndex: Int,
    signedIn: Boolean,
    snackbarHostState: SnackbarHostState,
    onSignInNeeded: () -> Unit,
    onClose: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ConnectionsViewer(
        viewModel = viewModel,
        wallpapers = state.wallpapers,
        startIndex = startIndex,
        onLoadMore = viewModel::loadMore,
        images = emptyMap(),
        signedIn = signedIn,
        snackbarHostState = snackbarHostState,
        onSignInNeeded = onSignInNeeded,
        onClose = onClose
    )
}

/**
 * The full-screen viewer over [wallpapers] (Explore's wall, or a collection's): swipe through, tap the picture to
 * hide everything else, set it on the home or lock screen, keep it in a collection, add it to an album, or save it
 * to the phone. Setting, collecting and adding take no account and use the full-quality original; saving asks a
 * signed-out visitor to sign in. [images] shows a picture from the phone when it is saved there. Dark in either theme.
 */
@Composable
fun ConnectionsViewer(
    viewModel: ConnectionsViewModel,
    wallpapers: List<ConnectionsWallpaper>,
    startIndex: Int,
    onLoadMore: () -> Unit,
    images: Map<String, Any>,
    signedIn: Boolean,
    snackbarHostState: SnackbarHostState,
    onSignInNeeded: () -> Unit,
    onClose: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val own = collections.filter { it.own }.sortedByDescending { it.createdAt }
    BackHandler(onBack = onClose)
    DarkSystemBars()
    if (wallpapers.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, wallpapers.lastIndex)) { wallpapers.size }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var sheet by rememberSaveable { mutableStateOf<ViewerSheet?>(null) }
    val current = wallpapers.getOrNull(pagerState.currentPage)

    LaunchedEffect(pagerState, wallpapers.size) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (page >= wallpapers.size - LOAD_AHEAD) onLoadMore()
        }
    }
    // A keyboard pages too (a laptop, a tablet's keyboard case); Escape already closes, as Back.
    val scope = rememberCoroutineScope()
    val keys = remember { FocusRequester() }
    LaunchedEffect(keys) { keys.requestFocus() }

    ViewerTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(keys)
                .focusable()
                .onKeyEvent { event ->
                    val step = when (event.key) {
                        Key.DirectionLeft -> -1
                        Key.DirectionRight -> 1
                        else -> return@onKeyEvent false
                    }
                    if (event.type == KeyEventType.KeyDown) {
                        val page = (pagerState.currentPage + step).coerceIn(0, wallpapers.lastIndex)
                        scope.launch { pagerState.animateScrollToPage(page) }
                    }
                    true
                }
        ) {
            HorizontalPager(
                state = pagerState,
                key = { wallpapers[it].id },
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                ViewerPage(wallpaper = wallpapers[page], image = images[wallpapers[page].id], onTap = { chrome = !chrome })
            }

            AnimatedVisibility(
                visible = chrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(8.dp)
                        .background(SCRIM, CircleShape)
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.connections_close), tint = Color.White)
                }
            }

            Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                SnackbarHost(snackbarHostState)
                AnimatedVisibility(
                    visible = chrome && current != null,
                    enter = fadeIn() + slideInVertically { it / 3 },
                    exit = fadeOut() + slideOutVertically { it / 3 }
                ) {
                    current?.let { wallpaper ->
                        InfoCard(
                            wallpaper = wallpaper,
                            setting = wallpaper.id in state.setting,
                            saving = wallpaper.id in state.saving,
                            downloading = wallpaper.id in state.downloading,
                            collected = own.any { collection -> collection.wallpapers.any { it.id == wallpaper.id } },
                            onSet = { sheet = ViewerSheet.Target },
                            onCollect = { sheet = ViewerSheet.Collection },
                            onAdd = { sheet = ViewerSheet.Album },
                            onSave = { if (signedIn) viewModel.saveToPhone(wallpaper) else onSignInNeeded() }
                        )
                    }
                }
            }
        }

        if (current != null) when (sheet) {
            ViewerSheet.Target -> TargetSheet(
                onPick = { target ->
                    sheet = null
                    viewModel.setWallpaper(current, target)
                },
                onDismiss = { sheet = null }
            )
            ViewerSheet.Album -> AlbumSheet(
                albums = albums,
                onPick = { album ->
                    sheet = null
                    viewModel.addToLibrary(listOf(current), LibraryTarget.Existing(album))
                },
                onNewAlbum = { name ->
                    sheet = null
                    viewModel.addToLibrary(listOf(current), LibraryTarget.New(name))
                },
                onDismiss = { sheet = null }
            )
            ViewerSheet.Collection -> CollectionSheet(
                collections = own,
                wallpapers = listOf(current),
                onToggle = { collection -> viewModel.toggleInCollection(current, collection.id) },
                onNew = { name ->
                    sheet = null
                    viewModel.addToNewCollection(listOf(current), name)
                },
                onDismiss = { sheet = null }
            )
            null -> Unit
        }
    }
}

@Composable
private fun ViewerPage(wallpaper: ConnectionsWallpaper, image: Any?, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = null, indication = null, onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(image ?: wallpaper.previewUrl)
                .crossfade(true)
                .build(),
            contentDescription = wallpaper.title,
            placeholder = ColorPainter(wallpaper.swatch()),
            contentScale = ContentScale.Crop,
            modifier = Modifier.aspectRatio(wallpaper.ratio())
        )
    }
}

@Composable
private fun InfoCard(
    wallpaper: ConnectionsWallpaper,
    setting: Boolean,
    saving: Boolean,
    downloading: Boolean,
    collected: Boolean,
    onSet: () -> Unit,
    onCollect: () -> Unit,
    onAdd: () -> Unit,
    onSave: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(12.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = wallpaper.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // Where the picture comes from sits beside its name, so the actions below fit a narrow phone.
                wallpaper.sourceUrl?.let { source ->
                    IconButton(onClick = { uriHandler.openUri(source) }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = stringResource(R.string.connections_view_source),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Text(
                text = listOfNotNull(wallpaper.credit, licenseLabel(wallpaper.license), "${wallpaper.width} × ${wallpaper.height}")
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Button(
                    onClick = { if (!setting) onSet() },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    if (setting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current
                        )
                    } else {
                        Icon(Icons.Outlined.Wallpaper, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (setting) R.string.connections_setting else R.string.connections_set_wallpaper))
                }
                FilledTonalIconButton(onClick = onCollect, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (collected) Icons.Filled.Bookmark else Icons.Outlined.BookmarkAdd,
                        contentDescription = stringResource(R.string.connections_save_to_collection)
                    )
                }
                FilledTonalIconButton(
                    onClick = { if (!saving) onAdd() },
                    modifier = Modifier.size(48.dp)
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    } else {
                        Icon(Icons.Outlined.LibraryAdd, contentDescription = stringResource(R.string.connections_add_to_library))
                    }
                }
                FilledTonalIconButton(
                    onClick = { if (!downloading) onSave() },
                    modifier = Modifier.size(48.dp)
                ) {
                    if (downloading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    } else {
                        Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.connections_save_to_phone))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetSheet(onPick: (WallpaperTarget) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            SheetTitle(stringResource(R.string.connections_set_wallpaper))
            SheetRow(Icons.Outlined.Smartphone, stringResource(R.string.connections_set_both)) { onPick(WallpaperTarget.BOTH) }
            SheetRow(Icons.Outlined.Home, stringResource(R.string.connections_set_home)) { onPick(WallpaperTarget.HOME) }
            SheetRow(Icons.Outlined.Lock, stringResource(R.string.connections_set_lock)) { onPick(WallpaperTarget.LOCK) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumSheet(
    albums: List<AlbumSummary>,
    onPick: (AlbumSummary) -> Unit,
    onNewAlbum: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var naming by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
        ) {
            SheetTitle(stringResource(R.string.connections_add_to_library))
            albums.forEach { album ->
                SheetRow(
                    icon = Icons.Outlined.PhotoAlbum,
                    text = album.name,
                    meta = pluralStringResource(R.plurals.wallpaper_count, album.wallpaperCount, album.wallpaperCount)
                ) { onPick(album) }
            }
            SheetRow(Icons.Outlined.Add, stringResource(R.string.connections_new_album)) { naming = true }
        }
    }
    if (naming) {
        val taken = stringResource(R.string.album_name_exists_error)
        var error by remember { mutableStateOf<String?>(null) }
        AddAlbumDialog(
            onDismiss = { naming = false },
            onConfirm = { name ->
                if (albums.any { it.name.equals(name, ignoreCase = true) }) {
                    error = taken
                } else {
                    naming = false
                    onNewAlbum(name)
                }
            },
            errorMessage = error,
            isSaving = false
        )
    }
}

/** The member's own collections, each ticked when it holds [wallpaper]; a tap puts it in or takes it out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionSheet(
    collections: List<WallpaperCollection>,
    wallpapers: List<ConnectionsWallpaper>,
    onToggle: (WallpaperCollection) -> Unit,
    onNew: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var naming by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
        ) {
            SheetTitle(stringResource(R.string.connections_save_to_collection))
            collections.forEach { collection ->
                val inIt = wallpapers.all { w -> collection.wallpapers.any { it.id == w.id } }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = inIt, role = Role.Checkbox, onValueChange = { onToggle(collection) })
                        .padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    Icon(Icons.Outlined.CollectionsBookmark, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = collection.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = pluralStringResource(R.plurals.wallpaper_count, collection.wallpapers.size, collection.wallpapers.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Checkbox(checked = inIt, onCheckedChange = null)
                }
            }
            SheetRow(Icons.Outlined.Add, stringResource(R.string.connections_new_collection)) { naming = true }
        }
    }
    if (naming) {
        CollectionNameDialog(
            title = stringResource(R.string.connections_new_collection),
            initial = "",
            taken = { name -> collections.any { it.name.equals(name, ignoreCase = true) } },
            onConfirm = { name ->
                naming = false
                onNew(name)
            },
            onDismiss = { naming = false }
        )
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun SheetRow(icon: ImageVector, text: String, meta: String? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (meta != null) {
            Text(text = meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Light status and navigation icons while the viewer is up; the theme's own come back on close. */
@Composable
private fun DarkSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        val lightStatus = controller.isAppearanceLightStatusBars
        val lightNavigation = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = lightStatus
            controller.isAppearanceLightNavigationBars = lightNavigation
        }
    }
}

@Composable
internal fun licenseLabel(license: String): String = when (license) {
    "cc0" -> stringResource(R.string.connections_license_cc0)
    "public_domain" -> stringResource(R.string.connections_license_public_domain)
    else -> stringResource(R.string.connections_license_original)
}

private val SCRIM = Color.Black.copy(alpha = 0.4f)
private const val LOAD_AHEAD = 3
