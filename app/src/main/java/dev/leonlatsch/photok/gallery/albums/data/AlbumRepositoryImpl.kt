/*
 * Copyright 2020–2026 Leon Latsch
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.leonlatsch.photok.gallery.albums.data

import dev.leonlatsch.photok.gallery.albums.domain.AlbumRepository
import dev.leonlatsch.photok.gallery.albums.domain.model.Album
import dev.leonlatsch.photok.gallery.albums.domain.model.AlbumPhotoRef
import dev.leonlatsch.photok.gallery.albums.toData
import dev.leonlatsch.photok.gallery.albums.toDomain
import dev.leonlatsch.photok.model.database.dao.AlbumDao
import dev.leonlatsch.photok.model.database.entity.AlbumTable
import dev.leonlatsch.photok.model.database.entity.Photo
import dev.leonlatsch.photok.model.repositories.PhotoRepository
import dev.leonlatsch.photok.sort.domain.Sort
import dev.leonlatsch.photok.sort.domain.SortConfig
import dev.leonlatsch.photok.sort.domain.SortRepository
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

class AlbumRepositoryImpl @Inject constructor(
    private val albumDao: AlbumDao,
    private val sortRepository: SortRepository,
    private val photoRepository: PhotoRepository,
) : AlbumRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAllAlbumsWithPhotos(): Flow<List<Album>> {
        return sortRepository.observeSortsForAlbums().flatMapLatest { sorts ->
            albumDao.observeAllAlbums().map { albums ->
                albums.filter { it.parentAlbumUuid == null }.map { album ->
                    val photos = albumDao.getPhotosForAlbum(
                        album.uuid,
                        sorts[album.uuid] ?: SortConfig.Album.default,
                    )
                    album.toDomain().copy(files = photos)
                }
            }
        }
    }

    override suspend fun getAlbums(): List<Album> = albumDao.getAllAlbums().map { it.toDomain() }

    override fun observeAllAlbums(): Flow<List<AlbumTable>> = albumDao.observeAllAlbums()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAlbumWithPhotos(uuid: String, sort: Sort, favoritesOnly: Boolean): Flow<Album> {
        if (!favoritesOnly) {
            return albumDao.observeAlbumWithPhotos(uuid, sort, false, emptyList())
                .map { it?.toDomain() ?: Album.Placeholder }
        }
        return combine(albumDao.observeAlbum(uuid), albumDao.observeAllAlbums()) { album, all -> album to all }
            .flatMapLatest { (album, all) ->
                if (album == null) flowOf(null)
                else {
                    val subtree = buildSubtreeUuids(album.uuid, all)
                    albumDao.observeAlbumWithPhotos(album.uuid, sort, true, subtree)
                }
            }.map { it?.toDomain() ?: Album.Placeholder }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeChildAlbumsWithPhotos(parentUuid: String): Flow<List<Album>> {
        return sortRepository.observeSortsForAlbums().flatMapLatest { sorts ->
            albumDao.observeChildAlbums(parentUuid).map { tables ->
                tables.map { table ->
                    val photos = albumDao.getPhotosForAlbum(table.uuid, sorts[table.uuid] ?: SortConfig.Album.default)
                    table.toDomain().copy(files = photos)
                }
            }
        }
    }

    override suspend fun getPhotosForAlbum(uuid: String, favoritesOnly: Boolean): List<Photo> = withContext(IO) {
        val sort = sortRepository.getSortForAlbum(uuid) ?: SortConfig.Album.default
        if (!favoritesOnly) albumDao.getPhotosForAlbum(uuid, sort)
        else albumDao.getPhotosForAlbum(uuid, sort, true, buildSubtreeUuids(uuid, albumDao.getAllAlbums()))
    }

    override suspend fun createAlbum(album: Album): Result<Album> =
        if (albumDao.insert(album.toData()) == -1L) Result.failure(IOException()) else Result.success(album.copy())

    override suspend fun updateAlbums(albums: List<AlbumTable>) { albumDao.updateAlbums(albums) }

    override suspend fun setCustomThumbnail(albumUuid: String, uri: String?) { albumDao.setCustomThumbnail(albumUuid, uri) }

    override suspend fun deleteAlbum(album: Album, permanentlyDeleteFiles: Boolean): Result<Unit> = withContext(IO) {
        try {
            val all = albumDao.getAllAlbums()
            val removeOrder = postOrderAlbumUuids(album.uuid, all)
            val photoUuids = albumDao.getPhotoUuidsLinkedToAlbums(removeOrder)
            removeOrder.forEach { uuid ->
                albumDao.removeAllPhotosFromAlbum(uuid)
                albumDao.getAlbum(uuid)?.let { albumDao.delete(it) }
            }
            if (permanentlyDeleteFiles) {
                photoUuids.distinct().forEach { p ->
                    if (albumDao.countAlbumRefsForPhoto(p) == 0) {
                        runCatching { photoRepository.get(p) }.getOrNull()?.let { photoRepository.safeDeletePhoto(it) }
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun moveAlbum(albumUuid: String, newParentUuid: String?): Result<Unit> = withContext(IO) {
        if (newParentUuid != null && wouldCreateCycle(albumUuid, newParentUuid, albumDao.getAllAlbums())) {
            Result.failure(IllegalStateException("Cycle detected"))
        } else {
            albumDao.updateParentAlbum(albumUuid, newParentUuid)
            Result.success(Unit)
        }
    }

    override suspend fun deleteAll() = albumDao.deleteAll()
    override suspend fun link(photoUUIDs: List<String>, albumUUID: String) = albumDao.link(photoUUIDs, albumUUID)
    override suspend fun link(ref: AlbumPhotoRef) = albumDao.link(ref.photoUUID, ref.albumUUID, ref.linkedAt)
    override suspend fun unlink(photoUUIDs: List<String>, uuid: String) = albumDao.unlink(photoUUIDs, uuid)
    override suspend fun unlinkAll() = albumDao.unlinkAll()
    override suspend fun rename(albumUUID: String, newName: String) = albumDao.renameAlbum(albumUUID, newName)
    override suspend fun getAllAlbumPhotoLinks(): List<AlbumPhotoRef> = albumDao.getAllAlbumPhotoRefs().map { it.toDomain() }
}

// HELPER FUNCTIONS (Must be outside the class)
private fun buildSubtreeUuids(rootUuid: String, all: List<AlbumTable>): List<String> {
    val byParent = all.groupBy { it.parentAlbumUuid }
    val out = mutableListOf<String>()
    val queue = ArrayDeque<String>().apply { add(rootUuid) }
    while (queue.isNotEmpty()) {
        val u = queue.removeFirst()
        out.add(u)
        byParent[u]?.forEach { queue.add(it.uuid) }
    }
    return out
}

private fun postOrderAlbumUuids(rootUuid: String, all: List<AlbumTable>): List<String> {
    val childrenByParent = all.groupBy { it.parentAlbumUuid }
    val out = mutableListOf<String>()
    fun dfs(u: String) { childrenByParent[u]?.forEach { dfs(it.uuid) }; out.add(u) }
    dfs(rootUuid)
    return out
}

private fun wouldCreateCycle(movingUuid: String, targetParent: String, all: List<AlbumTable>): Boolean {
    var curr: String? = targetParent
    while (curr != null) {
        if (curr == movingUuid) return true
        curr = all.find { it.uuid == curr }?.parentAlbumUuid
    }
    return false
}

