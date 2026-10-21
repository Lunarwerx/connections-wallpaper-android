package com.anthonyla.paperize.presentation.common.navigation

import kotlinx.serialization.Serializable

@Serializable
object StartupRoute

@Serializable
object NotificationRoute

@Serializable
object WallpaperModeSelectionRoute

@Serializable
object HomeRoute

@Serializable
data class AlbumRoute(val albumId: String)

@Serializable
data class FolderRoute(val folderId: String)

@Serializable
data class WallpaperViewRoute(
    val wallpaperId: String,
    val wallpaperUri: String,
    val wallpaperName: String
)

@Serializable
data class SortRoute(val albumId: String)

@Serializable
object SettingsRoute

@Serializable
object VisualEffectsRoute

@Serializable
object PrivacyRoute

/** Explore's collections manager. */
@Serializable
object CollectionsRoute

/** One collection's page: a member's own collection, or a gallery tag ("tag:<tag>"). */
@Serializable
data class CollectionRoute(val id: String)
