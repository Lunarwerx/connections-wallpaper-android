package com.anthonyla.paperize.domain.usecase

import com.anthonyla.paperize.core.util.generateId
import com.anthonyla.paperize.domain.model.Album
import com.anthonyla.paperize.domain.model.Folder
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.domain.repository.AlbumRepository
import com.anthonyla.paperize.domain.source.DocumentSource
import com.anthonyla.paperize.domain.source.SourceFolder
import com.anthonyla.paperize.domain.source.SourceImage
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class ImportWallpapersUseCase @Inject constructor(
    private val documents: DocumentSource,
    private val albumRepository: AlbumRepository
) {
    suspend fun addImages(albumId: String, uris: List<String>, onSaving: (Int, Int) -> Unit): Boolean {
        val images = uris.distinct().map { uri ->
            documents.retainReadPermission(uri)
            documents.readImage(uri).toWallpaper(albumId)
        }
        return albumRepository.addWallpapersToAlbum(albumId, images, onSaving) > 0
    }

    suspend fun addFolder(
        albumId: String,
        uri: String,
        onScanning: (Int) -> Unit,
        onSaving: (Int, Int) -> Unit
    ): Boolean {
        documents.retainReadPermission(uri)
        // Avoid rescanning an existing folder; insertion still checks duplicates atomically.
        if (albumRepository.getAlbumById(albumId).first()?.folders.orEmpty().any { it.uri == uri }) return false
        val source = documents.readFolder(uri, onScanning)
        return albumRepository.addFolderToAlbum(albumId, source.toFolder(albumId, uri), onSaving)
    }

    /** A new album named after the folder (numbered when the name is taken), holding that folder. */
    suspend fun createAlbumFromFolder(uri: String, onScanning: (Int) -> Unit, onSaving: (Int, Int) -> Unit): Album {
        documents.retainReadPermission(uri)
        val source = documents.readFolder(uri, onScanning)
        val album = albumRepository.createAlbum(freeAlbumName(source.name))
        albumRepository.addFolderToAlbum(album.id, source.toFolder(album.id, uri), onSaving)
        return album
    }

    /** A new album called [name] (numbered when taken) holding the chosen photos. */
    suspend fun createAlbumFromImages(uris: List<String>, name: String, onSaving: (Int, Int) -> Unit): Album {
        val album = albumRepository.createAlbum(freeAlbumName(name))
        addImages(album.id, uris, onSaving)
        return album
    }

    private suspend fun freeAlbumName(base: String): String {
        val name = base.trim()
        if (albumRepository.getAlbumByName(name) == null) return name
        return generateSequence(2) { it + 1 }.map { "$name $it" }.first { albumRepository.getAlbumByName(it) == null }
    }
}

private fun SourceFolder.toFolder(albumId: String, uri: String): Folder {
    val folderId = generateId()
    return Folder(
        id = folderId, albumId = albumId, uri = uri, name = name,
        coverUri = null, dateModified = System.currentTimeMillis(),
        wallpapers = images.sortedBy { it.uri }.map { it.toWallpaper(albumId, folderId) }
    )
}

internal fun SourceImage.toWallpaper(albumId: String, folderId: String? = null) = Wallpaper(
    id = generateId(), albumId = albumId, folderId = folderId, uri = uri,
    fileName = name, dateModified = modifiedAt
)
