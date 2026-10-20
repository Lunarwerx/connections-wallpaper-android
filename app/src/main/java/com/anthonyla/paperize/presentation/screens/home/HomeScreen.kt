package com.anthonyla.paperize.presentation.screens.home

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anthonyla.paperize.R
import com.anthonyla.paperize.connections.AccountSheet
import com.anthonyla.paperize.connections.AccountState
import com.anthonyla.paperize.connections.AccountViewModel
import com.anthonyla.paperize.connections.ConnectionsMessage
import com.anthonyla.paperize.connections.ConnectionsScreen
import com.anthonyla.paperize.connections.ConnectionsViewModel
import com.anthonyla.paperize.connections.ConnectionsViewer
import com.anthonyla.paperize.connections.SignInStatus
import com.anthonyla.paperize.connections.rememberSignIn
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.presentation.screens.home.components.HomeTopBar
import com.anthonyla.paperize.presentation.screens.home.components.getTabItems
import com.anthonyla.paperize.presentation.screens.library.LibraryScreen
import com.anthonyla.paperize.presentation.screens.wallpaper.WallpaperScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
    connectionsViewModel: ConnectionsViewModel = hiltViewModel(),
    accountViewModel: AccountViewModel = hiltViewModel()
) {
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val scheduleSettings by viewModel.scheduleSettings.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val wallpaperMode by viewModel.wallpaperMode.collectAsStateWithLifecycle()
    val showLiveWallpaperPrompt by viewModel.showLiveWallpaperPrompt.collectAsStateWithLifecycle()
    val currentHomeWallpaperUri by viewModel.currentHomeWallpaperUri.collectAsStateWithLifecycle()
    val currentLockWallpaperUri by viewModel.currentLockWallpaperUri.collectAsStateWithLifecycle()
    val accountState by accountViewModel.state.collectAsStateWithLifecycle()
    val signInStatus by accountViewModel.signIn.collectAsStateWithLifecycle()
    val signIn = rememberSignIn(accountViewModel)
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    var viewerOpen by rememberSaveable { mutableStateOf(false) }
    var viewerStart by rememberSaveable { mutableIntStateOf(0) }
    var showAccount by rememberSaveable { mutableStateOf(false) }

    val tabItems = getTabItems(
        wallpaperTitle = stringResource(R.string.wallpaper),
        libraryTitle = stringResource(R.string.library),
        exploreTitle = stringResource(R.string.explore)
    )

    val pagerState = rememberPagerState { tabItems.size }
    val viewerVisibility = remember { MutableTransitionState(false) }
    viewerVisibility.targetState = viewerOpen

    // The browser hands sign-in back while this screen may show anything: a failure opens the
    // account sheet with its reason, a success says who is signed in.
    var finishingSignIn by remember { mutableStateOf(false) }
    LaunchedEffect(signInStatus, accountState) {
        val signedIn = accountState as? AccountState.SignedIn
        when (signInStatus) {
            SignInStatus.Finishing -> finishingSignIn = true
            is SignInStatus.Failed -> {
                finishingSignIn = false
                showAccount = true
            }
            SignInStatus.Idle -> if (finishingSignIn && signedIn != null) {
                finishingSignIn = false
                showAccount = false
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                val who = signedIn.session.label ?: context.getString(R.string.connections_account)
                coroutineScope.launch { snackbarHostState.showSnackbar(context.getString(R.string.connections_signed_in, who)) }
            }
        }
    }

    LaunchedEffect(connectionsViewModel) {
        connectionsViewModel.messages.collect { message ->
            when (message) {
                is ConnectionsMessage.WallpaperSet -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    snackbarHostState.showSnackbar(context.getString(R.string.connections_set_done))
                }
                is ConnectionsMessage.Saved -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    snackbarHostState.showSnackbar(context.getString(R.string.connections_saved, message.albumName))
                }
                ConnectionsMessage.AlreadyInAlbum ->
                    snackbarHostState.showSnackbar(context.getString(R.string.connections_already_in_album))
                ConnectionsMessage.SignInNeeded -> {
                    val result = snackbarHostState.showSnackbar(
                        message = context.getString(R.string.connections_sign_in_needed),
                        actionLabel = context.getString(R.string.connections_sign_in_action)
                    )
                    if (result == SnackbarResult.ActionPerformed) showAccount = true
                }
                is ConnectionsMessage.Failed -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.connections_failed, message.reason ?: context.getString(R.string.connections_unknown_error))
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            HomeTopBar(
                title = tabItems[pagerState.currentPage].title,
                accountState = accountState,
                onAccountClick = { showAccount = true },
                onSettingsClick = onNavigateToSettings
            )
        },
        bottomBar = {
            NavigationBar {
                tabItems.forEachIndexed { index, item ->
                    val selected = index == pagerState.currentPage
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            coroutineScope.launch { pagerState.scrollToPage(index) }
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) item.filledIcon else item.unfilledIcon,
                                contentDescription = null
                            )
                        },
                        label = { Text(item.title) }
                    )
                }
            }
        },
        // One host at a time: the viewer draws its own until its exit animation has finished.
        snackbarHost = { if (!viewerVisibility.currentState && !viewerVisibility.targetState) SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,
            beyondViewportPageCount = tabItems.size - 1,
            modifier = modifier
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
        ) { page ->
            when (page) {
                0 -> {
                    val mode = wallpaperMode
                    if (mode == null) {
                        Box(modifier = Modifier.fillMaxSize())
                    } else {
                        WallpaperScreen(
                            albums = albums,
                            persistedScheduleSettings = scheduleSettings,
                            appSettings = appSettings,
                            wallpaperMode = mode,
                            onToggleChanger = viewModel::setChangerEnabled,
                            onSelectAlbum = viewModel::selectAlbum,
                            onUpdateScheduleSettings = viewModel::updateScheduleSettings,
                            onUpdateScheduleSettingsDebounced = viewModel::updateScheduleSettingsDebounced,
                            onChangeWallpaperNow = viewModel::changeWallpaperNow,
                            homeWallpaperUri = currentHomeWallpaperUri,
                            lockWallpaperUri = currentLockWallpaperUri
                        )
                    }
                }
                1 -> LibraryScreen(
                    albums = albums,
                    onViewAlbum = onNavigateToAlbum,
                    onCreateAlbum = { name -> viewModel.createAlbum(name) }
                )
                else -> ConnectionsScreen(
                    onOpen = { index ->
                        viewerStart = index
                        viewerOpen = true
                    },
                    viewModel = connectionsViewModel
                )
            }
        }
    }

    AnimatedVisibility(
        visibleState = viewerVisibility,
        enter = fadeIn(tween(VIEWER_MS)) + scaleIn(tween(VIEWER_MS), initialScale = 0.94f),
        exit = fadeOut(tween(VIEWER_MS))
    ) {
        ConnectionsViewer(
            viewModel = connectionsViewModel,
            startIndex = viewerStart,
            signedIn = accountState is AccountState.SignedIn,
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
                accountViewModel.dismissSignInError()
            }
        )
    }

    if (showLiveWallpaperPrompt && wallpaperMode == WallpaperMode.LIVE) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissLiveWallpaperPrompt() },
            title = {
                Text(text = stringResource(R.string.select_live_wallpaper))
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.select_live_wallpaper_instruction),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.open_wallpaper_picker_instruction_newline),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.dismissLiveWallpaperPrompt()
                        try {
                            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                                putExtra(
                                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                    ComponentName(
                                        context.packageName,
                                        "com.anthonyla.paperize.service.livewallpaper.PaperizeLiveWallpaperService"
                                    )
                                )
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            try {
                                val fallbackIntent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                                context.startActivity(fallbackIntent)
                            } catch (_: Exception) {
                                val settingsIntent = Intent(android.provider.Settings.ACTION_SETTINGS)
                                context.startActivity(settingsIntent)
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.open_wallpaper_picker))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissLiveWallpaperPrompt() }) {
                    Text(stringResource(R.string.later))
                }
            }
        )
    }
}

private const val VIEWER_MS = 250
