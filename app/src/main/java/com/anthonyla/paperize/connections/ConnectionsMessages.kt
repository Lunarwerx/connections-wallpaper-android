package com.anthonyla.paperize.connections

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import com.anthonyla.paperize.R

/** Says what the gallery's actions did, in [snackbarHostState], wherever the gallery or a collection is open. */
@Composable
fun ConnectionsMessageEffect(
    viewModel: ConnectionsViewModel,
    snackbarHostState: SnackbarHostState,
    onSignInNeeded: () -> Unit
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
