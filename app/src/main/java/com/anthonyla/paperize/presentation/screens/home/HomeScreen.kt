package com.anthonyla.paperize.presentation.screens.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anthonyla.paperize.R
import com.anthonyla.paperize.presentation.common.components.AppShortcut
import com.anthonyla.paperize.presentation.common.components.OnShortcut
import com.anthonyla.paperize.presentation.common.theme.AppLayout
import com.anthonyla.paperize.connections.AccountSheet
import com.anthonyla.paperize.connections.AccountState
import com.anthonyla.paperize.connections.AccountViewModel
import com.anthonyla.paperize.connections.FreeAppNotice
import com.anthonyla.paperize.connections.ads.AdsViewModel
import com.anthonyla.paperize.connections.ads.ConnectionsAds
import com.anthonyla.paperize.connections.ads.SponsoredBanner
import com.anthonyla.paperize.connections.ads.SponsoredPopup
import com.anthonyla.paperize.connections.ConnectionsMessageEffect
import com.anthonyla.paperize.connections.OfflineResultsEffect
import com.anthonyla.paperize.connections.ResumeLibraryAdd
import com.anthonyla.paperize.connections.libraryWaitingBody
import com.anthonyla.paperize.connections.libraryWaitingTitle
import com.anthonyla.paperize.connections.ConnectionsScreen
import com.anthonyla.paperize.connections.ConnectionsViewModel
import com.anthonyla.paperize.connections.ConnectionsViewer
import com.anthonyla.paperize.connections.SignInStatus
import com.anthonyla.paperize.connections.rememberSignIn
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.util.openLiveWallpaperPicker
import com.anthonyla.paperize.presentation.screens.album_view.ImportProgress
import com.anthonyla.paperize.presentation.screens.album_view.components.ImportProgressDialog
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
    onNavigateToVisualEffects: () -> Unit,
    onNavigateToCollections: () -> Unit,
    onNavigateToCollection: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
    connectionsViewModel: ConnectionsViewModel = hiltViewModel(),
    accountViewModel: AccountViewModel = hiltViewModel(),
    adsViewModel: AdsViewModel = hiltViewModel()
) {
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val scheduleSettings by viewModel.scheduleSettings.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val wallpaperMode by viewModel.wallpaperMode.collectAsStateWithLifecycle()
    val showLiveWallpaperPrompt by viewModel.showLiveWallpaperPrompt.collectAsStateWithLifecycle()
    val applyingLook by viewModel.applyingLook.collectAsStateWithLifecycle()
    val changingNow by viewModel.changingNow.collectAsStateWithLifecycle()
    val looksApplied by viewModel.looksApplied.collectAsStateWithLifecycle()
    val changesApplied by viewModel.changesApplied.collectAsStateWithLifecycle()
    val currentHomeWallpaperUri by viewModel.currentHomeWallpaperUri.collectAsStateWithLifecycle()
    val currentLockWallpaperUri by viewModel.currentLockWallpaperUri.collectAsStateWithLifecycle()
    val currentLiveWallpaperUri by viewModel.currentLiveWallpaperUri.collectAsStateWithLifecycle()
    val albumAdded by viewModel.albumAdded.collectAsStateWithLifecycle()
    val freeNotice by accountViewModel.freeNotice.collectAsStateWithLifecycle()
    val banner by adsViewModel.banner.collectAsStateWithLifecycle()
    val popup by adsViewModel.popup.collectAsStateWithLifecycle()
    val accountState by accountViewModel.state.collectAsStateWithLifecycle()
    val signInStatus by accountViewModel.signIn.collectAsStateWithLifecycle()
    val signIn = rememberSignIn(accountViewModel)
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val haptics = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    var viewerOpen by rememberSaveable { mutableStateOf(false) }
    var viewerStart by rememberSaveable { mutableIntStateOf(0) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val importError by viewModel.importError.collectAsStateWithLifecycle()

    // Adding a folder from a screen's album picker puts the new album on that screen.
    var folderTarget by rememberSaveable { mutableStateOf<ScreenType?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { viewModel.addFolderAlbum(it.toString(), folderTarget) }
        folderTarget = null
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.addPhotosAlbum(uris.map { it.toString() }, resources.getString(R.string.photos_album_name))
    }
    val addFolder: (ScreenType?) -> Unit = { target ->
        folderTarget = target
        folderPicker.launch(null)
    }

    // The first album added while signed out brings the one-time note that the app is free.
    LaunchedEffect(albumAdded) {
        if (albumAdded) {
            viewModel.consumeAlbumAdded()
            accountViewModel.offerFreeNotice()
        }
    }

    LaunchedEffect(importError) {
        importError?.let {
            viewModel.dismissImportError()
            snackbarHostState.showSnackbar(resources.getString(it))
        }
    }

    val tabItems = getTabItems(
        wallpaperTitle = stringResource(R.string.wallpaper),
        libraryTitle = stringResource(R.string.library),
        exploreTitle = stringResource(R.string.explore)
    )

    val pagerState = rememberPagerState { tabItems.size }
    val searchFocus = remember { FocusRequester() }
    OnShortcut(AppShortcut.SEARCH, enabled = !viewerOpen) {
        coroutineScope.launch {
            pagerState.scrollToPage(EXPLORE_PAGE)
            runCatching { searchFocus.requestFocus() }
        }
    }
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
                val who = signedIn.session.label ?: resources.getString(R.string.connections_account)
                coroutineScope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.connections_signed_in, who)) }
            }
        }
    }

    // A picture added to the Library opens its album from Library, so back lands in Library.
    val openAlbum: (String) -> Unit = { albumId ->
        coroutineScope.launch {
            pagerState.scrollToPage(LIBRARY_PAGE)
            onNavigateToAlbum(albumId)
        }
    }
    ConnectionsMessageEffect(connectionsViewModel, snackbarHostState, onSignInNeeded = { showAccount = true }, onOpenAlbum = openAlbum)
    ResumeLibraryAdd(connectionsViewModel, signedIn = accountState is AccountState.SignedIn, onNeedAccount = { showAccount = true })
    // A collection added from the shelf (or one whose page was left mid-add) says how it went here.
    OfflineResultsEffect(
        results = connectionsViewModel.offlineResults,
        snackbarHostState = snackbarHostState,
        // Queued for the account, and the sheet opens now: the account state may still read signed in.
        onSignInNeeded = { id ->
            connectionsViewModel.addCollectionsToLibrary(listOf(id), signedIn = false)
            showAccount = true
        },
        onOpenAlbum = openAlbum
    )
    val waitingTitle = libraryWaitingTitle(connectionsViewModel)
    val waitingBody = libraryWaitingBody(connectionsViewModel)

    // Material's expanded window (a tablet held wide, a laptop window) gets a side rail instead of a bottom bar.
    val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= AppLayout.expandedWidth
    val selectTab = { index: Int ->
        if (index != pagerState.currentPage) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        coroutineScope.launch { pagerState.scrollToPage(index) }
        Unit
    }
    val tabIcon = @Composable { index: Int ->
        val item = tabItems[index]
        Icon(
            imageVector = if (index == pagerState.currentPage) item.filledIcon else item.unfilledIcon,
            contentDescription = null
        )
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
            if (!wide) {
                NavigationBar {
                    tabItems.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = index == pagerState.currentPage,
                            onClick = { selectTab(index) },
                            icon = { tabIcon(index) },
                            label = { Text(item.title) }
                        )
                    }
                }
            }
        },
        // One host at a time: the viewer draws its own until its exit animation has finished.
        snackbarHost = { if (!viewerVisibility.currentState && !viewerVisibility.targetState) SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Row(
            modifier = modifier
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
        ) {
            if (wide) {
                NavigationRail {
                    tabItems.forEachIndexed { index, item ->
                        NavigationRailItem(
                            selected = index == pagerState.currentPage,
                            onClick = { selectTab(index) },
                            icon = { tabIcon(index) },
                            label = { Text(item.title) }
                        )
                    }
                }
            }
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = false,
                beyondViewportPageCount = tabItems.size - 1,
                modifier = Modifier.weight(1f)
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
                                onSetAlbums = viewModel::setAlbums,
                                onUpdateScheduleSettings = viewModel::updateScheduleSettings,
                                onUpdateScheduleSettingsDebounced = viewModel::updateScheduleSettingsDebounced,
                                onChangeWallpaperNow = viewModel::changeWallpaperNow,
                                onAddFolder = { addFolder(it) },
                                onExplore = { selectTab(EXPLORE_PAGE) },
                                onOpenVisualEffects = onNavigateToVisualEffects,
                                // A current picture opens its album from Library, so back lands in Library.
                                onOpenAlbum = openAlbum,
                                homeWallpaperUri = currentHomeWallpaperUri,
                                lockWallpaperUri = currentLockWallpaperUri,
                                liveWallpaperUri = currentLiveWallpaperUri,
                                applyingLook = applyingLook,
                                changingNow = changingNow,
                                looksApplied = looksApplied,
                                changesApplied = changesApplied,
                                sponsored = banner?.let { ad ->
                                    @Composable {
                                        SponsoredBanner(
                                            ad = ad,
                                            onViewed = { adsViewModel.viewed(ad, ConnectionsAds.BANNER_SLOT) },
                                            onTapped = { adsViewModel.tapped(ad, ConnectionsAds.BANNER_SLOT) }
                                        )
                                    }
                                }
                            )
                        }
                    }
                    1 -> LibraryScreen(
                        albums = albums,
                        onViewAlbum = onNavigateToAlbum,
                        onAddFolder = { addFolder(null) },
                        onAddPhotos = { photoPicker.launch(arrayOf("image/*")) },
                        onExplore = { selectTab(EXPLORE_PAGE) }
                    )
                    else -> ConnectionsScreen(
                        onOpen = { index ->
                            viewerStart = index
                            viewerOpen = true
                        },
                        onOpenCollections = onNavigateToCollections,
                        onOpenCollection = onNavigateToCollection,
                        signedIn = accountState is AccountState.SignedIn,
                        searchFocus = searchFocus,
                        viewModel = connectionsViewModel
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
            viewModel = connectionsViewModel,
            startIndex = viewerStart,
            signedIn = accountState is AccountState.SignedIn,
            snackbarHostState = snackbarHostState,
            onSignInNeeded = { showAccount = true },
            onClose = { viewerOpen = false }
        )
    }

    ImportProgressDialog(progress = importProgress, onCancel = viewModel::cancelImport)

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
                connectionsViewModel.cancelLibraryAdd()
                accountViewModel.dismissSignInError()
            },
            title = waitingTitle,
            body = waitingBody
        )
    }

    val livePromptShowing = showLiveWallpaperPrompt && wallpaperMode == WallpaperMode.LIVE
    val importing = importProgress != ImportProgress.Idle
    // Only during the free week: a member who signed in meanwhile, or whose week is over, never sees it.
    val trial = accountState as? AccountState.Trial
    val freeNoticeShowing = freeNotice && trial != null && !livePromptShowing && !importing && !showAccount
    if (freeNoticeShowing && trial != null) {
        FreeAppNotice(
            daysLeft = trial.daysLeft,
            onSignIn = {
                accountViewModel.dismissFreeNotice()
                signIn()
            },
            onLater = accountViewModel::dismissFreeNotice
        )
    }
    popup?.let { ad ->
        if (!freeNoticeShowing && !livePromptShowing && !importing && !showAccount && !viewerOpen) {
            SponsoredPopup(
                ad = ad,
                onViewed = { adsViewModel.viewed(ad, ConnectionsAds.POPUP_SLOT) },
                onTapped = { adsViewModel.tapped(ad, ConnectionsAds.POPUP_SLOT) },
                onDismiss = adsViewModel::dismissPopup
            )
        }
    }

    if (livePromptShowing) {
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
                        openLiveWallpaperPicker(context)
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

/** The pager page Library sits on (Wallpaper, Library, Explore). */
private const val LIBRARY_PAGE = 1

/** The pager page Explore sits on (Wallpaper, Library, Explore). */
private const val EXPLORE_PAGE = 2
