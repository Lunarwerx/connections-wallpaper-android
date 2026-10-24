package com.anthonyla.paperize.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.LibraryAddCheck
import androidx.compose.material.icons.outlined.Publish
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.anthonyla.paperize.R
import com.anthonyla.paperize.domain.model.AlbumSummary

private enum class RemixSubSheet { Target, Album, Submit }

/**
 * The Remix sheet over the viewer's [wallpaper], for a signed-in member: a plan without Remix is offered the plans,
 * one with it types a style, and the result can be set, added to an album, saved, or submitted to the gallery.
 * [onWallpaperSet] hears the result was set (the viewer closes the sheet and says so).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemixSheet(
    wallpaper: ConnectionsWallpaper,
    albums: List<AlbumSummary>,
    onSignInNeeded: () -> Unit,
    onWallpaperSet: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: RemixViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var sub by rememberSaveable { mutableStateOf<RemixSubSheet?>(null) }
    var credit by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(wallpaper.id) { viewModel.open(wallpaper) }
    LaunchedEffect(viewModel) {
        viewModel.signInNeeded.collect {
            onDismiss()
            onSignInNeeded()
        }
    }
    LaunchedEffect(state.submitted) { if (state.submitted && sub == RemixSubSheet.Submit) sub = null }
    // Back from the plans in the browser: a plan bought there opens Remix where the member left it.
    LifecycleResumeEffect(viewModel) {
        viewModel.recheck()
        onPauseOrDispose { }
    }
    // The first frame, before open() has run, belongs to this wallpaper too.
    val shown = if (state.wallpaper?.id == wallpaper.id) state else RemixUiState(wallpaper = wallpaper)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        RemixContent(
            wallpaper = wallpaper,
            state = shown,
            onRemix = viewModel::remix,
            onSeePlans = { uriHandler.openUri(ConnectionsApi.PLANS_URL) },
            onSet = { if (!shown.setting) sub = RemixSubSheet.Target },
            onAddToLibrary = { if (!shown.adding && !shown.added) sub = RemixSubSheet.Album },
            onSave = { if (!shown.saving && !shown.saved) viewModel.saveToPhone() },
            onSubmit = { sub = RemixSubSheet.Submit }
        )
    }

    when (sub) {
        RemixSubSheet.Target -> TargetSheet(
            onPick = { target ->
                sub = null
                viewModel.setWallpaper(target, onWallpaperSet)
            },
            onDismiss = { sub = null }
        )
        RemixSubSheet.Album -> AlbumSheet(
            albums = albums,
            onPick = { album ->
                sub = null
                viewModel.addToLibrary(LibraryTarget.Existing(album))
            },
            onNewAlbum = { name ->
                sub = null
                viewModel.addToLibrary(LibraryTarget.New(name))
            },
            onDismiss = { sub = null }
        )
        RemixSubSheet.Submit -> {
            LaunchedEffect(Unit) { if (credit == null) credit = viewModel.suggestedCredit() }
            credit?.let { name ->
                SubmitRemixDialog(
                    initialTitle = viewModel.titleFor(shown),
                    initialCredit = name,
                    submitting = shown.submitting,
                    error = shown.error,
                    onSubmit = viewModel::submit,
                    onDismiss = { sub = null }
                )
            }
        }
        null -> Unit
    }
}

/** What the Remix sheet shows for [state]; stateless, so each step can be drawn on its own. */
@Composable
internal fun RemixContent(
    wallpaper: ConnectionsWallpaper,
    state: RemixUiState,
    onRemix: (String) -> Unit,
    onSeePlans: () -> Unit,
    onSet: () -> Unit,
    onAddToLibrary: () -> Unit,
    onSave: () -> Unit,
    onSubmit: () -> Unit
) {
    val step = state.step
    var prompt by rememberSaveable(wallpaper.id) { mutableStateOf(state.prompt) }
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
    ) {
        Text(stringResource(R.string.connections_remix), style = MaterialTheme.typography.titleLarge)
        RemixPicture(wallpaper, step)
        when (step) {
            RemixStep.Checking -> Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            RemixStep.Locked -> {
                Text(stringResource(R.string.connections_remix_paid), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onSeePlans, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.connections_see_plans))
                }
            }
            is RemixStep.Ready, RemixStep.Working -> {
                val working = step == RemixStep.Working
                val failure = (step as? RemixStep.Ready)?.failure
                StyleField(prompt, { prompt = it }, enabled = !working, failure = failure, onGo = { onRemix(prompt) })
                if (failure == RemixFailure.OUT_OF_CREDITS) {
                    OutlinedButton(onClick = onSeePlans, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(stringResource(R.string.connections_see_plans))
                    }
                }
                Button(
                    onClick = { onRemix(prompt) },
                    enabled = !working && prompt.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    if (working) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    } else {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (working) R.string.connections_remixing else R.string.connections_remix))
                }
            }
            is RemixStep.Done -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onSet, modifier = Modifier.weight(1f).height(48.dp)) {
                        if (state.setting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                        } else {
                            Icon(Icons.Outlined.Wallpaper, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (state.setting) R.string.connections_setting else R.string.connections_set_wallpaper))
                    }
                    FilledTonalIconButton(onClick = onAddToLibrary, modifier = Modifier.size(48.dp)) {
                        when {
                            state.adding -> Spinner()
                            state.added -> Icon(Icons.Outlined.LibraryAddCheck, contentDescription = stringResource(R.string.connections_add_to_library))
                            else -> Icon(Icons.Outlined.LibraryAdd, contentDescription = stringResource(R.string.connections_add_to_library))
                        }
                    }
                    FilledTonalIconButton(onClick = onSave, modifier = Modifier.size(48.dp)) {
                        when {
                            state.saving -> Spinner()
                            state.saved -> Icon(Icons.Outlined.DownloadDone, contentDescription = stringResource(R.string.connections_save_to_phone))
                            else -> Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.connections_save_to_phone))
                        }
                    }
                }
                if (state.submitted) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp)) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.connections_submitted), style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    OutlinedButton(onClick = onSubmit, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Icon(Icons.Outlined.Publish, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.connections_submit_to_gallery))
                    }
                }
                StyleField(
                    prompt, { prompt = it }, enabled = true, failure = null, onGo = { onRemix(prompt) },
                    trailing = {
                        IconButton(onClick = { onRemix(prompt) }, enabled = prompt.isNotBlank()) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = stringResource(R.string.connections_remix_again))
                        }
                    }
                )
            }
        }
        state.error?.let {
            Text(
                stringResource(R.string.connections_remix_error, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** The source picture until a remix comes back, then the remix; dimmed under a spinner while the model draws. */
@Composable
private fun RemixPicture(wallpaper: ConnectionsWallpaper, step: RemixStep) {
    val done = step as? RemixStep.Done
    val ratio = done?.let { it.width.toFloat() / it.height } ?: wallpaper.ratio()
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .heightIn(max = 320.dp)
                .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(16.dp))
                .background(wallpaper.swatch())
        ) {
            AsyncImage(
                model = done?.file ?: wallpaper.previewUrl,
                contentDescription = wallpaper.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (step == RemixStep.Working) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun StyleField(
    prompt: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    failure: RemixFailure?,
    onGo: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    OutlinedTextField(
        value = prompt,
        onValueChange = { if (it.length <= RemixViewModel.PROMPT_MAX) onChange(it) },
        label = { Text(stringResource(R.string.connections_remix_style)) },
        placeholder = { Text(stringResource(R.string.connections_remix_style_placeholder)) },
        enabled = enabled,
        isError = failure != null,
        supportingText = if (failure != null) {
            { Text(stringResource(failure.message)) }
        } else null,
        trailingIcon = trailing,
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { if (prompt.isNotBlank()) onGo() }),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Submitting a remix to the gallery: its title, the name to show with it, and the member's word that it is theirs to share. */
@Composable
internal fun SubmitRemixDialog(
    initialTitle: String,
    initialCredit: String,
    submitting: Boolean,
    error: String?,
    onSubmit: (title: String, credit: String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var credit by rememberSaveable { mutableStateOf(initialCredit) }
    var rights by rememberSaveable { mutableStateOf(false) }
    val emailCredit = RemixViewModel.looksLikeEmail(credit)
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.connections_submit_to_gallery)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(TITLE_MAX) },
                    label = { Text(stringResource(R.string.connections_field_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = credit,
                    onValueChange = { credit = it.take(CREDIT_MAX) },
                    label = { Text(stringResource(R.string.connections_your_name)) },
                    isError = emailCredit,
                    supportingText = if (emailCredit) {
                        { Text(stringResource(R.string.connections_credit_not_email)) }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = rights, role = Role.Checkbox, onValueChange = { rights = it })
                ) {
                    Checkbox(checked = rights, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.connections_rights_confirm), style = MaterialTheme.typography.bodyMedium)
                }
                error?.let {
                    Text(stringResource(R.string.connections_remix_error, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(title, credit) },
                enabled = !submitting && title.isNotBlank() && credit.isNotBlank() && !emailCredit && rights
            ) {
                if (submitting) Spinner() else Text(stringResource(R.string.connections_submit_for_review))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun Spinner() {
    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
}

private val RemixFailure.message: Int
    get() = when (this) {
        RemixFailure.OUT_OF_CREDITS -> R.string.connections_remix_out_of_credits
        RemixFailure.DECLINED -> R.string.connections_remix_declined
        RemixFailure.PAUSED -> R.string.connections_remix_paused
        RemixFailure.UNAVAILABLE -> R.string.connections_remix_unavailable
        RemixFailure.FAILED -> R.string.connections_remix_failed
    }

/** WALLPAPER_TITLE_MAX and WALLPAPER_CREDIT_MAX. */
private const val TITLE_MAX = 120
private const val CREDIT_MAX = 200
