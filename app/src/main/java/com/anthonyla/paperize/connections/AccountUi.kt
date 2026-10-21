package com.anthonyla.paperize.connections

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.anthonyla.paperize.R
import kotlinx.coroutines.launch

/** Opens Connections sign-in in the phone's browser; a phone with no browser gets the reason instead. */
@Composable
fun rememberSignIn(viewModel: AccountViewModel): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noBrowser = stringResource(R.string.connections_no_browser)
    return remember(viewModel, context, scope, noBrowser) {
        {
            scope.launch {
                val intent = viewModel.signInIntent()
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    viewModel.noBrowser(noBrowser)
                }
            }
        }
    }
}

/** The top bar's account button: the member's avatar, or an outline when nobody is signed in. */
@Composable
fun AccountButton(state: AccountState, onClick: () -> Unit) {
    val label = stringResource(R.string.connections_account)
    IconButton(onClick = onClick) {
        if (state is AccountState.SignedIn) {
            AccountAvatar(state.session, size = 32.dp, modifier = Modifier.semantics { contentDescription = label })
        } else {
            Icon(Icons.Outlined.AccountCircle, contentDescription = label)
        }
    }
}

@Composable
fun AccountAvatar(session: ConnectionsSession, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = session.label?.firstOrNull()?.uppercase().orEmpty(),
            style = if (size >= 48.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        session.picture?.let { picture ->
            AsyncImage(
                model = picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

/**
 * Who is signed in (and Sign out), or what signing in does here and when it becomes required. A sheet opened by
 * something that takes the account (saving a collection offline) says so in its own [title] and [body].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(
    state: AccountState,
    status: SignInStatus,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
    title: String? = null,
    body: String? = null
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (state is AccountState.SignedIn) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AccountAvatar(state.session, size = 48.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.session.name ?: stringResource(R.string.connections_account),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        state.session.email?.let { email ->
                            Text(
                                text = email,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.connections_sign_out))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AppMark(size = 56.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title ?: stringResource(R.string.connections_free_title), style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = body ?: when (state) {
                                is AccountState.Trial -> pluralStringResource(R.plurals.connections_trial_days_left, state.daysLeft, state.daysLeft)
                                else -> stringResource(R.string.connections_trial_over)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                SignInButton(status = status, onClick = onSignIn)
                SignInError(status)
            }
        }
    }
}

/**
 * Said once, after the first album is added while signed out: the app is free, and all it asks is a
 * free Connections account before the free week ends. Later is a fine answer.
 */
@Composable
fun FreeAppNotice(daysLeft: Int, onSignIn: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        icon = { AppMark(size = 40.dp) },
        title = { Text(stringResource(R.string.free_notice_title)) },
        text = { Text(pluralStringResource(R.plurals.free_notice_body, daysLeft, daysLeft)) },
        confirmButton = { Button(onClick = onSignIn) { Text(stringResource(R.string.connections_sign_in_action)) } },
        dismissButton = { TextButton(onClick = onLater) { Text(stringResource(R.string.free_notice_later)) } }
    )
}

/**
 * After the free week: the app asks for a free Connections sign-in before showing its screens.
 * The wallpapers it rotates keep changing behind this either way.
 */
@Composable
fun SignInWall(viewModel: AccountViewModel) {
    val showcase by viewModel.showcase.collectAsStateWithLifecycle()
    val status by viewModel.signIn.collectAsStateWithLifecycle()
    val signIn = rememberSignIn(viewModel)
    val activity = LocalActivity.current
    LaunchedEffect(viewModel) { viewModel.loadShowcase() }
    BackHandler { activity?.finish() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Showcase(showcase, modifier = Modifier.weight(1f))
            AppMark(size = 56.dp, modifier = Modifier.padding(top = 32.dp))
            Text(
                text = stringResource(R.string.connections_wall_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                text = stringResource(R.string.connections_wall_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            SignInButton(status = status, onClick = signIn, modifier = Modifier.padding(top = 32.dp))
            SignInError(status, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Six gallery pictures in three staggered columns. */
@Composable
private fun Showcase(wallpapers: List<ConnectionsWallpaper>, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(SHOWCASE_COLUMNS) { column ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .offset(y = if (column == 1) 24.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(SHOWCASE_ROWS) { row ->
                    val wallpaper = wallpapers.getOrNull(column * SHOWCASE_ROWS + row)
                    val tile = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                    if (wallpaper == null) {
                        Box(tile.background(MaterialTheme.colorScheme.surfaceContainerHigh))
                    } else {
                        AsyncImage(
                            model = wallpaper.previewUrl,
                            contentDescription = null,
                            placeholder = ColorPainter(wallpaper.swatch()),
                            contentScale = ContentScale.Crop,
                            modifier = tile
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppMark(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(size * LAUNCHER_ZOOM)
        )
    }
}

@Composable
private fun SignInButton(status: SignInStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val finishing = status == SignInStatus.Finishing
    Button(
        onClick = { if (!finishing) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        // The Connections mark sits on a white disc, the way Google's button carries its G.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.White)
        ) {
            if (finishing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            } else {
                Image(painterResource(R.drawable.connections_mark), contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(if (finishing) R.string.connections_signing_in else R.string.connections_sign_in),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun SignInError(status: SignInStatus, modifier: Modifier = Modifier) {
    if (status !is SignInStatus.Failed) return
    Text(
        text = stringResource(R.string.connections_sign_in_failed, status.reason ?: stringResource(R.string.connections_unknown_error)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier
    )
}

private const val SHOWCASE_COLUMNS = 3
private const val SHOWCASE_ROWS = 2
/** The launcher foreground is a 108dp canvas around a 62dp logo; this shows the logo at the mark's size. */
private const val LAUNCHER_ZOOM = 108f / 62f * 0.62f
