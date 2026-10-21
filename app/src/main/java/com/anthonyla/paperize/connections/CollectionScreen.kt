package com.anthonyla.paperize.connections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anthonyla.paperize.R

/**
 * One collection's page: its pictures as a wall that opens the viewer, how many it holds, and Save offline. A
 * signed-out visitor who taps Save offline is asked for the free account right then, and the save starts once
 * they are signed in. The member's own collections rename and delete from the menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    explore: ConnectionsViewModel,
    viewModel: CollectionViewModel = hiltViewModel(),
    accountViewModel: AccountViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val collections by explore.collections.collectAsStateWithLifecycle()
    val accountState by accountViewModel.state.collectAsStateWithLifecycle()
    val signInStatus by accountViewModel.signIn.collectAsStateWithLifecycle()
    val signIn = rememberSignIn(accountViewModel)
    val signedIn = accountState is AccountState.SignedIn
    val resources = LocalResources.current
    val haptics = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    var viewerOpen by rememberSaveable { mutableStateOf(false) }
    var viewerStart by rememberSaveable { mutableIntStateOf(0) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    // Save offline asked for the account: once it is there, the save starts by itself.
    var pendingSave by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var left by remember { mutableStateOf(false) }
    val viewerVisibility = remember { MutableTransitionState(false) }
    viewerVisibility.targetState = viewerOpen

    // A collection deleted from its own menu or another screen leaves nothing to show; [left] keeps it to one back.
    LaunchedEffect(state.missing) {
        if (state.missing && !left) {
            left = true
            onBack()
        }
    }

    ConnectionsMessageEffect(explore, snackbarHostState, onSignInNeeded = { showAccount = true })

    LaunchedEffect(accountState, pendingSave) {
        if (pendingSave && accountState is AccountState.SignedIn) {
            pendingSave = false
            showAccount = false
            viewModel.saveOffline()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.results.collect { result ->
            when (result) {
                is OfflineResult.Saved -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    val answer = snackbarHostState.showSnackbar(
                        message = resources.getString(R.string.connections_offline_done, result.name),
                        actionLabel = resources.getString(R.string.open_album)
                    )
                    if (answer == SnackbarResult.ActionPerformed) onOpenAlbum(result.albumId)
                }
                is OfflineResult.Partly ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.connections_offline_partly, result.saved, result.total))
                is OfflineResult.SignInNeeded -> {
                    pendingSave = true
                    showAccount = true
                }
                is OfflineResult.Failed -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    snackbarHostState.showSnackbar(
                        resources.getString(R.string.connections_failed, result.reason ?: resources.getString(R.string.connections_unknown_error))
                    )
                }
            }
        }
    }

    val saveOffline = {
        if (signedIn) {
            viewModel.saveOffline()
        } else {
            pendingSave = true
            showAccount = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = state.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (state.own) {
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.connections_more_options))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.connections_rename)) },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                    onClick = {
                                        menu = false
                                        renaming = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.connections_delete), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menu = false
                                        deleting = true
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        // One host at a time: the viewer draws its own until its exit animation has finished.
        snackbarHost = { if (!viewerVisibility.currentState && !viewerVisibility.targetState) SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            ) {
                Text(
                    text = pluralStringResource(R.plurals.wallpaper_count, state.count, state.count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                OfflineControl(
                    offline = state.offline,
                    enabled = state.count > 0,
                    onSave = saveOffline,
                    onStop = viewModel::stopSaving
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.wallpapers.isEmpty() && state.failed -> EmptyState(
                        icon = Icons.Outlined.CloudOff,
                        text = stringResource(R.string.connections_load_failed),
                        action = stringResource(R.string.connections_retry),
                        onAction = viewModel::retry
                    )
                    state.wallpapers.isEmpty() && state.loading -> SkeletonGrid()
                    state.wallpapers.isEmpty() && state.own -> EmptyState(
                        icon = Icons.Outlined.BookmarkAdd,
                        text = stringResource(R.string.connections_collection_empty)
                    )
                    state.wallpapers.isEmpty() -> SkeletonGrid()
                    else -> WallpaperGrid(
                        wallpapers = state.wallpapers,
                        loading = state.loading,
                        failed = state.failed,
                        onRetry = viewModel::retry,
                        onLoadMore = viewModel::loadMore,
                        onOpen = { index ->
                            viewerStart = index
                            viewerOpen = true
                        },
                        images = state.images
                    )
                }
            }
        }
    }

    AnimatedVisibility(
        visibleState = viewerVisibility,
        enter = fadeIn(tween(VIEWER_MS)) + scaleIn(tween(VIEWER_MS), initialScale = 0.94f),
        exit = fadeOut(tween(VIEWER_MS))
    ) {
        ConnectionsViewer(
            viewModel = explore,
            wallpapers = state.wallpapers,
            startIndex = viewerStart,
            onLoadMore = viewModel::loadMore,
            images = state.images,
            signedIn = signedIn,
            snackbarHostState = snackbarHostState,
            onSignInNeeded = { showAccount = true },
            onClose = { viewerOpen = false }
        )
    }

    if (showAccount) {
        AccountSheet(
            state = accountState,
            status = signInStatus,
            onSignIn = signIn,
            onSignOut = {
                accountViewModel.signOut()
                showAccount = false
            },
            onDismiss = {
                showAccount = false
                pendingSave = false
                accountViewModel.dismissSignInError()
            },
            title = if (pendingSave) stringResource(R.string.connections_offline_sign_in_title, state.name) else null,
            body = if (pendingSave) stringResource(R.string.connections_offline_sign_in_body) else null
        )
    }

    if (renaming) {
        CollectionNameDialog(
            title = stringResource(R.string.connections_rename),
            initial = state.name,
            taken = { name -> collections.any { it.own && it.id != viewModel.id && it.name.equals(name, ignoreCase = true) } },
            onConfirm = { name ->
                renaming = false
                viewModel.rename(name)
            },
            onDismiss = { renaming = false }
        )
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.connections_delete_collection_title, state.name)) },
            text = { Text(stringResource(R.string.connections_delete_collection_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    // The page goes back when the collection is gone.
                    viewModel.delete()
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

/** Save offline, how far a save has got (and Stop), Saved offline, or Update offline when it has grown since. */
@Composable
private fun OfflineControl(offline: OfflineState, enabled: Boolean, onSave: () -> Unit, onStop: () -> Unit) {
    when (offline) {
        OfflineState.NotSaved -> FilledTonalButton(onClick = onSave, enabled = enabled) {
            Icon(Icons.Outlined.DownloadForOffline, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.connections_save_offline))
        }
        OfflineState.Stale -> FilledTonalButton(onClick = onSave, enabled = enabled) {
            Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.connections_update_offline))
        }
        is OfflineState.Saving -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                progress = { if (offline.total > 0) offline.done.toFloat() / offline.total else 0f },
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
            Text(
                text = stringResource(R.string.connections_saving_offline, offline.done, offline.total),
                style = MaterialTheme.typography.labelLarge
            )
            IconButton(onClick = onStop) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.connections_stop_saving))
            }
        }
        OfflineState.Saved -> Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    Icons.Outlined.OfflinePin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(stringResource(R.string.connections_saved_offline), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private const val VIEWER_MS = 250
