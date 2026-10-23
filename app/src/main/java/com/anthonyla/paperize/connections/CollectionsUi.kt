package com.anthonyla.paperize.connections

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.LibraryAddCheck
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.anthonyla.paperize.R

/** The gallery's tags with at least this many pictures make the Explore shelf; the manager shows every tag. */
private const val SHELF_MIN_COUNT = 3

/** The shelf's cards: every one of the member's own, then the gallery's tags big enough to browse. */
internal fun shelfCards(cards: List<CollectionCard>): List<CollectionCard> =
    cards.filter { it.own || it.offline || it.count >= SHELF_MIN_COUNT }

/**
 * Explore's Collections shelf, above the wall of pictures: a card per collection (the member's own first), and
 * See all for the manager. A long press picks cards into [selection]. It runs edge to edge inside a grid that pads
 * its items by [bleed].
 */
@Composable
internal fun CollectionsShelf(
    cards: List<CollectionCard>,
    selection: WallSelection,
    onOpen: (String) -> Unit,
    onSeeAll: () -> Unit,
    bleed: Dp = 0.dp
) {
    Column(modifier = Modifier.fullBleed(bleed).padding(bottom = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp)
        ) {
            Icon(
                Icons.Outlined.CollectionsBookmark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.connections_collections),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onSeeAll) {
                Text(stringResource(R.string.connections_see_all))
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        val row = rememberLazyListState()
        val lead = cards.firstOrNull()?.id
        var shownLead by rememberSaveable { mutableStateOf(lead) }
        // A list keeps the card it was showing in place, so a collection just made would land out of sight to its left.
        LaunchedEffect(lead) {
            if (lead != shownLead) {
                shownLead = lead
                row.scrollToItem(0)
            }
        }
        LazyRow(
            state = row,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(cards, key = { it.id }) { card ->
                SelectableCard(card = card, selection = selection, onOpen = onOpen, modifier = Modifier.width(SHELF_CARD_WIDTH))
            }
        }
    }
}

/** A card that opens its collection, or in selection mode is picked and put back by a tap; a long press picks it. */
@Composable
private fun SelectableCard(card: CollectionCard, selection: WallSelection, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    CollectionCardTile(
        card = card,
        onClick = { if (selection.active) selection.toggle(card.id) else onOpen(card.id) },
        selected = card.id in selection.ids,
        picking = selection.active,
        onLongClick = { selection.enter(card.id) },
        modifier = modifier
    )
}

/**
 * A collection's card: its cover, its name, and its count, marked when it is in the Library, or how far its add has
 * got while one runs. With [onLongClick], a long press picks it; in selection mode every card shows its circle,
 * filled when picked, as the pictures on a wall do.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CollectionCardTile(
    card: CollectionCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    picking: Boolean = false,
    onLongClick: (() -> Unit)? = null
) {
    val haptics = LocalHapticFeedback.current
    val ring = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .clip(CARD_SHAPE)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                // The phone ticks once to say the card is picked.
                onLongClick = onLongClick?.let { pick ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        pick()
                    }
                }
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CARD_SHAPE)
                .background(cardSwatch(card.color))
                .then(if (selected) Modifier.border(SELECTED_RING, ring, CARD_SHAPE) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (card.cover != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(card.cover).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            } else {
                Icon(
                    Icons.Outlined.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
            }
            if (picking) {
                Icon(
                    imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) ring else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                )
            }
        }
        Text(
            text = card.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
        ) {
            val adding = card.adding
            if (adding != null) {
                CircularProgressIndicator(
                    progress = { if (adding.total > 0) adding.done.toFloat() / adding.total else 0f },
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp
                )
                Text(
                    text = stringResource(R.string.connections_adding_to_library, adding.done, adding.total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (card.offline) {
                    Icon(
                        Icons.Outlined.LibraryAddCheck,
                        contentDescription = stringResource(R.string.connections_in_library),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Text(
                    text = pluralStringResource(R.plurals.wallpaper_count, card.count, card.count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The collections manager: the member's own collections (and a card to start one), then the gallery's tags.
 * A card opens that collection's page; a long press picks cards to add to the Library together (or delete, when
 * all are the member's own). Signed out, the add asks for the free account first and starts once they are in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    viewModel: ConnectionsViewModel,
    accountViewModel: AccountViewModel = hiltViewModel()
) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val accountState by accountViewModel.state.collectAsStateWithLifecycle()
    val signInStatus by accountViewModel.signIn.collectAsStateWithLifecycle()
    val signIn = rememberSignIn(accountViewModel)
    val signedIn = accountState is AccountState.SignedIn
    val snackbarHostState = remember { SnackbarHostState() }
    var naming by rememberSaveable { mutableStateOf(false) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    val selection = remember { WallSelection() }
    ConnectionsMessageEffect(viewModel, snackbarHostState, onSignInNeeded = { showAccount = true }, onOpenAlbum = onOpenAlbum)
    OfflineResultsEffect(
        results = viewModel.offlineResults,
        snackbarHostState = snackbarHostState,
        // Queued for the account, and the sheet opens now: the account state may still read signed in.
        onSignInNeeded = { id ->
            viewModel.addCollectionsToLibrary(listOf(id), signedIn = false)
            showAccount = true
        },
        onOpenAlbum = onOpenAlbum
    )
    ResumeLibraryAdd(viewModel, signedIn = signedIn, onNeedAccount = { showAccount = true })
    // Signing in from the sheet starts what waited (ResumeLibraryAdd), so the sheet has nothing left to ask.
    LaunchedEffect(signedIn) { if (signedIn) showAccount = false }
    val waitingTitle = libraryWaitingTitle(viewModel)
    val waitingBody = libraryWaitingBody(viewModel)
    val own = cards.filter { it.own }
    val gallery = cards.filterNot { it.own }

    Scaffold(
        topBar = {
            if (selection.active) {
                CollectionSelectionBar(
                    selection = selection,
                    cards = cards,
                    onAddToLibrary = { ids -> viewModel.addCollectionsToLibrary(ids, signedIn) },
                    onDelete = viewModel::deleteCollections
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.connections_collections)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(MANAGER_CARD_MIN_WIDTH),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item(key = YOURS_KEY, span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(Icons.Outlined.Bookmarks, stringResource(R.string.connections_collections_yours))
            }
            item(key = NEW_KEY) { NewCollectionCard(onClick = { naming = true }) }
            items(own, key = { it.id }) { card -> SelectableCard(card = card, selection = selection, onOpen = onOpen) }
            if (gallery.isNotEmpty()) {
                item(key = GALLERY_KEY, span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(Icons.Outlined.Explore, stringResource(R.string.connections_collections_gallery))
                }
                items(gallery, key = { it.id }) { card -> SelectableCard(card = card, selection = selection, onOpen = onOpen) }
            }
        }
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
                viewModel.cancelLibraryAdd()
                accountViewModel.dismissSignInError()
            },
            title = waitingTitle,
            body = waitingBody
        )
    }

    if (naming) {
        CollectionNameDialog(
            title = stringResource(R.string.connections_new_collection),
            initial = "",
            taken = { name -> own.any { it.name.equals(name, ignoreCase = true) } },
            onConfirm = { name ->
                naming = false
                viewModel.createCollection(name, onOpen)
            },
            onDismiss = { naming = false }
        )
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun NewCollectionCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(CARD_SHAPE)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CARD_SHAPE)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        }
        Text(
            text = stringResource(R.string.connections_new_collection),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)
        )
    }
}

/** Names a new collection, or renames one; a name another of the member's collections has is refused. */
@Composable
internal fun CollectionNameDialog(
    title: String,
    initial: String,
    taken: (String) -> Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    // The field's keyboard otherwise outlives the dialog and takes the next back press meant for the page.
    val keyboard = LocalSoftwareKeyboardController.current
    val trimmed = name.trim()
    val clash = trimmed.isNotEmpty() && !trimmed.equals(initial.trim(), ignoreCase = true) && taken(trimmed)
    val dismiss = {
        keyboard?.hide()
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.connections_collection_name)) },
                singleLine = true,
                isError = clash,
                supportingText = if (clash) {
                    { Text(stringResource(R.string.connections_collection_name_taken)) }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    keyboard?.hide()
                    onConfirm(trimmed)
                },
                enabled = trimmed.isNotEmpty() && !clash
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
    LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
}

@Composable
private fun cardSwatch(color: String?): Color =
    color?.let { runCatching { Color(it.toColorInt()) }.getOrNull() } ?: MaterialTheme.colorScheme.surfaceContainerHigh

/** Widens a lazy-grid item past the grid's side padding, so a row inside it scrolls to the screen's edges. */
private fun Modifier.fullBleed(bleed: Dp): Modifier = if (bleed == 0.dp) this else layout { measurable, constraints ->
    val extra = bleed.roundToPx() * 2
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

private val CARD_SHAPE = RoundedCornerShape(16.dp)
private val SELECTED_RING = 3.dp
private val SHELF_CARD_WIDTH = 136.dp
private val MANAGER_CARD_MIN_WIDTH = 150.dp
private const val YOURS_KEY = "__yours__"
private const val NEW_KEY = "__new__"
private const val GALLERY_KEY = "__gallery__"
