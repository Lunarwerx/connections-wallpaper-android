package com.anthonyla.paperize.connections

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import com.anthonyla.paperize.R
import kotlinx.coroutines.flow.Flow

/**
 * Says how a whole collection's Library add went, in [snackbarHostState]: in the Library (with Open album), partly,
 * or failed. [onSignInNeeded] hears the id of a collection whose add found the member signed out.
 */
@Composable
fun OfflineResultsEffect(
    results: Flow<OfflineResult>,
    snackbarHostState: SnackbarHostState,
    onSignInNeeded: (String) -> Unit,
    onOpenAlbum: (String) -> Unit
) {
    val resources = LocalResources.current
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(results) {
        results.collect { result ->
            when (result) {
                is OfflineResult.Added -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    val answer = snackbarHostState.showSnackbar(
                        message = resources.getString(R.string.connections_collection_in_library, result.name),
                        actionLabel = resources.getString(R.string.open_album)
                    )
                    if (answer == SnackbarResult.ActionPerformed) onOpenAlbum(result.albumId)
                }
                is OfflineResult.Downloaded ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.connections_collection_downloaded, result.name))
                is OfflineResult.Partly ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.connections_collection_download_partly, result.saved, result.total))
                is OfflineResult.SignInNeeded -> onSignInNeeded(result.id)
                is OfflineResult.Failed -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    snackbarHostState.showSnackbar(
                        resources.getString(R.string.connections_failed, result.reason ?: resources.getString(R.string.connections_unknown_error))
                    )
                }
            }
        }
    }
}

/** Says what the gallery's actions did, in [snackbarHostState], wherever the gallery or a collection is open. */
@Composable
fun ConnectionsMessageEffect(
    viewModel: ConnectionsViewModel,
    snackbarHostState: SnackbarHostState,
    onSignInNeeded: () -> Unit,
    onOpenAlbum: (String) -> Unit
) {
    val resources = LocalResources.current
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                is ConnectionsMessage.WallpaperSet -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    snackbarHostState.showSnackbar(resources.getString(R.string.connections_set_done))
                }
                is ConnectionsMessage.Saved -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    snackbarHostState.showSnackbar(resources.getString(R.string.connections_saved, message.albumName))
                }
                is ConnectionsMessage.AddedToLibrary -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    val answer = snackbarHostState.showSnackbar(
                        message = resources.getQuantityString(R.plurals.connections_library_added, message.count, message.count, message.albumName),
                        actionLabel = resources.getString(R.string.open_album)
                    )
                    if (answer == SnackbarResult.ActionPerformed) onOpenAlbum(message.albumId)
                }
                ConnectionsMessage.SavedToPhone -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    snackbarHostState.showSnackbar(resources.getString(R.string.connections_saved_to_phone))
                }
                is ConnectionsMessage.AddedToCollection -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    snackbarHostState.showSnackbar(resources.getString(R.string.connections_saved, message.name))
                }
                ConnectionsMessage.AlreadyInAlbum ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.connections_already_in_album))
                ConnectionsMessage.SignInNeeded -> {
                    val result = snackbarHostState.showSnackbar(
                        message = resources.getString(R.string.connections_sign_in_needed),
                        actionLabel = resources.getString(R.string.connections_sign_in_action)
                    )
                    if (result == SnackbarResult.ActionPerformed) onSignInNeeded()
                }
                is ConnectionsMessage.Failed -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    snackbarHostState.showSnackbar(
                        resources.getString(R.string.connections_failed, message.reason ?: resources.getString(R.string.connections_unknown_error))
                    )
                }
            }
        }
    }
}
