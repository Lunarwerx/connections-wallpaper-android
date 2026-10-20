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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

private enum class ViewerSheet { Target, Album }

/**
 * The full-screen viewer: swipe through the gallery, tap the picture to hide everything else, set it
 * on the home or lock screen, or add it to an album. Dark in either theme.
 */
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
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val wallpapers = state.wallpapers
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
            if (page >= wallpapers.size - LOAD_AHEAD) viewModel.loadMore()
        }
    }

    ViewerTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            HorizontalPager(
                state = pagerState,
                key = { wallpapers[it].id },
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                ViewerPage(wallpaper = wallpapers[page], onTap = { chrome = !chrome })
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
                            onSet = { if (signedIn) sheet = ViewerSheet.Target else onSignInNeeded() },
                            onAdd = { if (signedIn) sheet = ViewerSheet.Album else onSignInNeeded() }
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
                    viewModel.addToAlbum(current, album)
                },
                onNewAlbum = { name ->
                    sheet = null
                    viewModel.addToNewAlbum(current, name)
                },
                onDismiss = { sheet = null }
            )
            null -> Unit
        }
    }
}

@Composable
private fun ViewerPage(wallpaper: ConnectionsWallpaper, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = null, indication = null, onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(wallpaper.previewUrl)
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
    onSet: () -> Unit,
    onAdd: () -> Unit
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
            Text(
                text = wallpaper.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
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
                FilledTonalIconButton(
                    onClick = { if (!saving) onAdd() },
                    modifier = Modifier.size(48.dp)
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    } else {
                        Icon(Icons.Outlined.LibraryAdd, contentDescription = stringResource(R.string.connections_add_to_album))
                    }
                }
                wallpaper.sourceUrl?.let { source ->
                    IconButton(onClick = { uriHandler.openUri(source) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.connections_view_source))
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
private fun AlbumSheet(
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
            SheetTitle(stringResource(R.string.connections_add_to_album))
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
