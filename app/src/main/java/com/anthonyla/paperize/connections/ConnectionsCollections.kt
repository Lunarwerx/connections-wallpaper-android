package com.anthonyla.paperize.connections

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.anthonyla.paperize.core.util.generateId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.collectionsStore: DataStore<Preferences> by preferencesDataStore(name = "connections_collections")
private val COLLECTIONS = stringPreferencesKey("collections")

/** A gallery tag's collection id; a member's own collections carry a generated id. */
internal const val TAG_COLLECTION = "tag:"

internal fun tagCollectionId(tag: String): String = TAG_COLLECTION + tag

internal fun tagTitle(tag: String): String = tag.replaceFirstChar { it.titlecase() }

/**
 * A collection kept on the phone: one of the member's own (no account needed), or a gallery tag's once it is
 * saved offline. [wallpapers] is an own collection's pictures, newest first, or the tag's pictures as they were
 * saved, so a saved collection opens with no internet.
 */
@Serializable
data class WallpaperCollection(
    val id: String,
    val name: String,
    /** The gallery tag it follows; null for one of the member's own. */
    val tag: String? = null,
    val wallpapers: List<ConnectionsWallpaper> = emptyList(),
    /** When it was last saved offline whole, the album that holds its originals, and which pictures that save held. */
    val savedAt: Long? = null,
    val albumId: String? = null,
    val savedIds: List<String> = emptyList(),
    val createdAt: Long = 0
) {
    val own: Boolean get() = tag == null
}

/** One card on Explore's Collections shelf and in the collections manager. */
data class CollectionCard(
    val id: String,
    val name: String,
    val count: Int,
    /** A local preview File when the picture is saved on the phone, else its preview URL; null for an empty collection. */
    val cover: Any?,
    val color: String?,
    val own: Boolean,
    /** In the Library: added whole, so its originals are on the phone. */
    val offline: Boolean,
    /** How far an add to the Library has got while one runs; null when none does. */
    val adding: OfflineProgress? = null
)

/**
 * The cards, the member's own collections first (newest first), then the gallery's tags, biggest first. A tag
 * saved offline shows from what was saved when the gallery cannot be reached, so a saved collection is never lost
 * to a dead connection. [local] finds a picture's saved preview.
 */
internal fun collectionCards(
    saved: List<WallpaperCollection>,
    tags: List<ConnectionsTag>,
    local: (ConnectionsWallpaper) -> File?
): List<CollectionCard> {
    val byId = saved.associateBy { it.id }
    val own = saved.filter { it.own }.sortedByDescending { it.createdAt }.map { collection ->
        val first = collection.wallpapers.firstOrNull()
        CollectionCard(
            id = collection.id,
            name = collection.name,
            count = collection.wallpapers.size,
            cover = first?.let { local(it) ?: it.previewUrl },
            color = first?.color,
            own = true,
            offline = collection.savedAt != null
        )
    }
    val gallery = if (tags.isNotEmpty()) {
        tags.sortedByDescending { it.count }.map { tag ->
            val record = byId[tagCollectionId(tag.tag)]
            val savedCover = record?.wallpapers?.firstOrNull { it.id == tag.cover?.id } ?: record?.wallpapers?.firstOrNull()
            CollectionCard(
                id = tagCollectionId(tag.tag),
                name = tagTitle(tag.tag),
                count = tag.count,
                cover = savedCover?.let(local) ?: tag.cover?.previewUrl ?: savedCover?.previewUrl,
                color = tag.cover?.color,
                own = false,
                offline = record?.savedAt != null
            )
        }
    } else {
        saved.filter { !it.own && it.savedAt != null }.sortedByDescending { it.wallpapers.size }.map { record ->
            val first = record.wallpapers.firstOrNull()
            CollectionCard(
                id = record.id,
                name = record.name,
                count = record.wallpapers.size,
                cover = first?.let { local(it) ?: it.previewUrl },
                color = first?.color,
                own = false,
                offline = true
            )
        }
    }
    return own + gallery
}

/**
 * The phone's collections, kept as one JSON list in their own DataStore. A store that cannot be read shows none
 * rather than failing Explore.
 */
@Singleton
class ConnectionsCollections internal constructor(private val store: DataStore<Preferences>) {

    @Inject constructor(@ApplicationContext context: Context) : this(context.collectionsStore)

    private val json = Json { ignoreUnknownKeys = true }

    val all: Flow<List<WallpaperCollection>> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { decode(it) }

    /** A new collection of the member's own, holding [picks] when it is made from pictures. */
    suspend fun create(name: String, picks: List<ConnectionsWallpaper> = emptyList()): WallpaperCollection {
        val collection = WallpaperCollection(
            id = generateId(),
            name = name.trim(),
            wallpapers = picks.distinctBy { it.id },
            createdAt = System.currentTimeMillis()
        )
        update { it + collection }
        return collection
    }

    suspend fun rename(id: String, name: String) = update { list ->
        list.map { if (it.id == id) it.copy(name = name.trim()) else it }
    }

    suspend fun delete(vararg ids: String) = update { list -> list.filterNot { it.id in ids } }

    /** Adds every one of [wallpapers] the collection does not hold yet, newest first; the ones it holds stay as they are. */
    suspend fun addAll(id: String, wallpapers: List<ConnectionsWallpaper>) = update { list ->
        list.map { collection ->
            if (collection.id != id) return@map collection
            val fresh = wallpapers.distinctBy { it.id }.filterNot { w -> collection.wallpapers.any { it.id == w.id } }
            collection.copy(wallpapers = fresh + collection.wallpapers)
        }
    }

    /** Adds [wallpaper] to the collection, newest first, or takes it out when it is there; true when it was added. */
    suspend fun toggle(id: String, wallpaper: ConnectionsWallpaper): Boolean {
        var added = false
        update { list ->
            list.map { collection ->
                if (collection.id != id) return@map collection
                val present = collection.wallpapers.any { it.id == wallpaper.id }
                added = !present
                collection.copy(
                    wallpapers = if (present) collection.wallpapers.filterNot { it.id == wallpaper.id }
                    else listOf(wallpaper) + collection.wallpapers
                )
            }
        }
        return added
    }

    /**
     * Records a save offline: the album that holds the originals and which pictures it held, and when every one
     * of them was saved ([complete]). A gallery tag's collection is kept from here on with the pictures it saved;
     * one of the member's own that was deleted while it saved stays deleted.
     */
    suspend fun recordSave(
        collection: WallpaperCollection,
        wallpapers: List<ConnectionsWallpaper>,
        albumId: String,
        savedIds: List<String>,
        complete: Boolean,
        now: Long
    ) = update { list ->
        val current = list.firstOrNull { it.id == collection.id }
            ?: if (collection.own) return@update list else collection.copy(createdAt = now)
        val recorded = current.copy(
            // An own collection keeps its own list, which may have changed while it saved.
            wallpapers = if (current.own) current.wallpapers else wallpapers,
            albumId = albumId,
            savedIds = (current.savedIds + savedIds).distinct(),
            savedAt = if (complete) now else current.savedAt
        )
        if (list.any { it.id == collection.id }) list.map { if (it.id == collection.id) recorded else it } else list + recorded
    }

    private suspend fun update(change: (List<WallpaperCollection>) -> List<WallpaperCollection>) {
        store.edit { prefs -> prefs[COLLECTIONS] = json.encodeToString(change(decode(prefs))) }
    }

    private fun decode(prefs: Preferences): List<WallpaperCollection> =
        prefs[COLLECTIONS]?.let { runCatching { json.decodeFromString<List<WallpaperCollection>>(it) }.getOrNull() }.orEmpty()
}
