package com.anthonyla.paperize.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
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
 * See all for the manager. It runs edge to edge inside a grid that pads its items by [bleed].
 */
@Composable
internal fun CollectionsShelf(
    cards: List<CollectionCard>,
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
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(cards, key = { it.id }) { card ->
                CollectionCardTile(card = card, onClick = { onOpen(card.id) }, modifier = Modifier.width(SHELF_CARD_WIDTH))
            }
        }
    }
}

/** A collection's card: its cover, its name, and its count, marked when it is saved offline. */
@Composable
internal fun CollectionCardTile(card: CollectionCard, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(CARD_SHAPE)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CARD_SHAPE)
                .background(cardSwatch(card.color)),
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
            if (card.offline) {
                Icon(
                    Icons.Outlined.OfflinePin,
                    contentDescription = stringResource(R.string.connections_saved_offline),
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

/**
 * The collections manager: the member's own collections (and a card to start one), then the gallery's tags.
 * A card opens that collection's page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: ConnectionsViewModel
) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var naming by rememberSaveable { mutableStateOf(false) }
    ConnectionsMessageEffect(viewModel, snackbarHostState, onSignInNeeded = {})
    val own = cards.filter { it.own }
    val gallery = cards.filterNot { it.own }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connections_collections)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
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
            items(own, key = { it.id }) { card -> CollectionCardTile(card = card, onClick = { onOpen(card.id) }) }
            if (gallery.isNotEmpty()) {
                item(key = GALLERY_KEY, span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(Icons.Outlined.Explore, stringResource(R.string.connections_collections_gallery))
                }
                items(gallery, key = { it.id }) { card -> CollectionCardTile(card = card, onClick = { onOpen(card.id) }) }
            }
        }
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
    val trimmed = name.trim()
    val clash = trimmed.isNotEmpty() && !trimmed.equals(initial.trim(), ignoreCase = true) && taken(trimmed)
    AlertDialog(
        onDismissRequest = onDismiss,
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
            TextButton(onClick = { onConfirm(trimmed) }, enabled = trimmed.isNotEmpty() && !clash) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
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
private val SHELF_CARD_WIDTH = 136.dp
private val MANAGER_CARD_MIN_WIDTH = 150.dp
private const val YOURS_KEY = "__yours__"
private const val NEW_KEY = "__new__"
private const val GALLERY_KEY = "__gallery__"
