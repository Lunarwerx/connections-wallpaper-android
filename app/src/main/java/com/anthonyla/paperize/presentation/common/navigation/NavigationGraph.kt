package com.anthonyla.paperize.presentation.common.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.anthonyla.paperize.connections.CollectionScreen
import com.anthonyla.paperize.connections.CollectionsScreen
import com.anthonyla.paperize.connections.ConnectionsViewModel
import com.anthonyla.paperize.core.util.PermissionUtil
import com.anthonyla.paperize.presentation.common.navigation.util.enterTransitionBackward
import com.anthonyla.paperize.presentation.common.navigation.util.enterTransitionForward
import com.anthonyla.paperize.presentation.common.navigation.util.exitTransitionBackward
import com.anthonyla.paperize.presentation.common.navigation.util.exitTransitionForward
import com.anthonyla.paperize.presentation.screens.album_view.AlbumViewScreen
import com.anthonyla.paperize.presentation.screens.folder_view.FolderViewScreen
import com.anthonyla.paperize.presentation.screens.home.HomeScreen
import com.anthonyla.paperize.presentation.screens.home.HomeViewModel
import com.anthonyla.paperize.presentation.screens.notification.NotificationPermissionScreen
import com.anthonyla.paperize.presentation.screens.privacy.PrivacyScreen
import com.anthonyla.paperize.presentation.screens.settings.SettingsScreen
import com.anthonyla.paperize.presentation.screens.sort.SortViewScreen
import com.anthonyla.paperize.presentation.screens.startup.StartupScreen
import com.anthonyla.paperize.presentation.screens.wallpaper.VisualEffectsScreen
import com.anthonyla.paperize.presentation.screens.wallpaper_view.WallpaperViewScreen

@Composable
fun NavigationGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: Any = StartupRoute,
    animate: Boolean = true,
    onFirstLaunchComplete: () -> Unit = {},
    /** An install that agreed to an older notice: the startup notice is shown again and Agree goes to Home. */
    reAgree: Boolean = false,
    onPolicyAgreed: () -> Unit = {},
    onScreen: (String) -> Unit = {}
) {
    val enterForward: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
        if (animate) { { enterTransitionForward() } } else { { EnterTransition.None } }
    val exitForward: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
        if (animate) { { exitTransitionForward() } } else { { ExitTransition.None } }
    val enterBackward: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
        if (animate) { { enterTransitionBackward() } } else { { EnterTransition.None } }
    val exitBackward: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
        if (animate) { { exitTransitionBackward() } } else { { ExitTransition.None } }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Route names only, without arguments: analytics gets "HomeRoute", never an album or wallpaper id.
    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            onScreen(destination.route.orEmpty().substringAfterLast('.').substringBefore('/'))
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = enterForward,
        exitTransition = exitForward,
        popEnterTransition = enterBackward,
        popExitTransition = exitBackward,
        // A swipe back plays the back arrow's slide, not the library's default shrink.
        predictivePopEnterTransition = { enterBackward() },
        predictivePopExitTransition = { exitBackward() }
    ) {
        composable<StartupRoute> {
            StartupScreen(
                onAgree = {
                    onPolicyAgreed()
                    if (reAgree) {
                        navController.navigate(HomeRoute) {
                            popUpTo<StartupRoute> { inclusive = true }
                        }
                    } else {
                        navController.navigate(WallpaperModeSelectionRoute) {
                            popUpTo<StartupRoute> { inclusive = true }
                        }
                    }
                },
                onPrivacyClick = {
                    navController.navigate(PrivacyRoute)
                }
            )
        }

        composable<WallpaperModeSelectionRoute> {
            com.anthonyla.paperize.presentation.screens.wallpaper_mode_selection.WallpaperModeSelectionScreen(
                onModeSelected = {
                    if (!PermissionUtil.hasNotificationPermission(context)) {
                        navController.navigate(NotificationRoute) {
                            popUpTo<WallpaperModeSelectionRoute> { inclusive = true }
                        }
                    } else {
                        onFirstLaunchComplete()
                        navController.navigate(HomeRoute) {
                            popUpTo<WallpaperModeSelectionRoute> { inclusive = true }
                        }
                    }
                }
            )
        }

        composable<NotificationRoute> {
            NotificationPermissionScreen(
                onContinue = {
                    onFirstLaunchComplete()
                    navController.navigate(HomeRoute) {
                        popUpTo<NotificationRoute> { inclusive = true }
                    }
                }
            )
        }

        composable<HomeRoute> {
            HomeScreen(
                onNavigateToSettings = {
                    navController.navigate(SettingsRoute)
                },
                onNavigateToAlbum = { albumId ->
                    navController.navigate(AlbumRoute(albumId))
                },
                onNavigateToVisualEffects = {
                    navController.navigate(VisualEffectsRoute) { launchSingleTop = true }
                },
                onNavigateToCollections = {
                    navController.navigate(CollectionsRoute) { launchSingleTop = true }
                },
                onNavigateToCollection = { id ->
                    navController.navigate(CollectionRoute(id))
                }
            )
        }

        // Both share Explore's view model with the Home screen, so the viewer, a collection and Explore agree.
        composable<CollectionsRoute> { entry ->
            val homeEntry = remember(entry) { navController.getBackStackEntry<HomeRoute>() }
            CollectionsScreen(
                onBack = { navController.popBackStack() },
                onOpen = { id -> navController.navigate(CollectionRoute(id)) },
                viewModel = hiltViewModel<ConnectionsViewModel>(homeEntry)
            )
        }

        composable<CollectionRoute> { entry ->
            val homeEntry = remember(entry) { navController.getBackStackEntry<HomeRoute>() }
            CollectionScreen(
                onBack = { navController.popBackStack() },
                onOpenAlbum = { albumId -> navController.navigate(AlbumRoute(albumId)) },
                explore = hiltViewModel<ConnectionsViewModel>(homeEntry)
            )
        }

        composable<VisualEffectsRoute> { entry ->
            // The page edits the Wallpaper tab's settings, so it shares the Home screen's view model.
            val homeEntry = remember(entry) { navController.getBackStackEntry<HomeRoute>() }
            VisualEffectsScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = hiltViewModel<HomeViewModel>(homeEntry)
            )
        }

        composable<AlbumRoute> {
            AlbumViewScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToFolder = { folderId ->
                    navController.navigate(FolderRoute(folderId))
                },
                onNavigateToWallpaperView = { wallpaperId, wallpaperUri, wallpaperName ->
                    navController.navigate(
                        WallpaperViewRoute(wallpaperId, wallpaperUri, wallpaperName)
                    )
                }
            )
        }

        composable<SortRoute> {
            SortViewScreen(
                onSaveClick = { navController.popBackStack() },
                onBackClick = { navController.popBackStack() }
            )
        }

        composable<FolderRoute> {
            FolderViewScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToWallpaperView = { wallpaperId, wallpaperUri, wallpaperName ->
                    navController.navigate(
                        WallpaperViewRoute(wallpaperId, wallpaperUri, wallpaperName)
                    )
                }
            )
        }

        composable<WallpaperViewRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<WallpaperViewRoute>()
            WallpaperViewScreen(
                wallpaperUri = route.wallpaperUri,
                wallpaperName = route.wallpaperName,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable<SettingsRoute> {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPrivacy = { navController.navigate(PrivacyRoute) }
            )
        }

        composable<PrivacyRoute> {
            PrivacyScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
