/*
 *   Copyright 2020–2026 Leon Latsch
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
                albums
                    .filter { it.parentAlbumUuid == null }
                    .map { album ->
                        val photos = albumDao.getPhotosForAlbum(
                            album.uuid,
                            sorts[album.uuid] ?: SortConfig.Album.default,
                        )
                        album.toDomain().copy(files = photos)
                    }
            }
        }
    }

    override suspend fun getAlbums(): List<Album> = albumDao.getAllAlbums()
        .map { album -> album.toDomain() }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAlbumWithPhotos(uuid: String, sort: Sort, favoritesOnly: Boolean): Flow<Album> {
        if (!favoritesOnly) {
            return albumDao.observeAlbumWithPhotos(uuid, sort, false, emptyList())
                .map { it.toDomain() }
        }
        return combine(
            albumDao.observeAlbum(uuid),
            albumDao.observeAllAlbums(),
        ) { album, allAlbums ->
            Pair(album, allAlbums)
        }.flatMapLatest { (album, allAlbums) ->
            if (album == null) {
                flowOf(null)
            } else {
                val subtree = buildSubtreeUuids(album.uuid, allAlbums)
                albumDao.observeAlbumWithPhotos(
                    album.uuid,
                    sort,
                    true,
                    subtree,
                )
            }
        }.map { it?.toDomain() ?: Album.Placeholder }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeChildAlbumsWithPhotos(parentUuid: String): Flow<List<Album>> {
        return sortRepository.observeSortsForAlbums().flatMapLatest { sorts ->
            albumDao.observeChildAlbums(parentUuid).map { tables ->
                tables.map { table ->
                    val photos = albumDao.getPhotosForAlbum(
                        table.uuid,
                        sorts[table.uuid] ?: SortConfig.Album.default,
                    )
                    table.toDomain().copy(files = photos)
                }
            }
        }
    }

    override suspend fun getPhotosForAlbum(uuid: String, favoritesOnly: Boolean): List<Photo> =
        withContext(IO) {
            val sort = sortRepository.getSortForAlbum(uuid) ?: SortConfig.Album.default
            if (!favoritesOnly) {
                albumDao.getPhotosForAlbum(uuid, sort)
            } else {
                val all = albumDao.getAllAlbums()
                val subtree = buildSubtreeUuids(uuid, all)
                albumDao.getPhotosForAlbum(uuid, sort, true, subtree)
            }
        }

    override suspend fun createAlbum(album: Album): Result<Album> =
        when (albumDao.insert(album.toData())) {
            -1L -> Result.failure(IOException())
            else -> Result.success(album.copy())
        }

    override suspend fun deleteAlbum(album: Album, permanentlyDeleteFiles: Boolean): Result<Unit> =
        withContext(IO) {
            try {
                val all = albumDao.getAllAlbums()
                val removeOrder = postOrderAlbumUuids(album.uuid, all)
                val photoUuids = if (removeOrder.isNotEmpty()) {
                    albumDao.getPhotoUuidsLinkedToAlbums(removeOrder)
                } else {
                    emptyList()
                }
                for (albumUuid in removeOrder) {
                    val table = albumDao.getAlbum(albumUuid) ?: continue
                    albumDao.removeAllPhotosFromAlbum(albumUuid)
                    albumDao.delete(table)
                }
                if (permanentlyDeleteFiles) {
                    for (p in photoUuids.distinct()) {
                        if (albumDao.countAlbumRefsForPhoto(p) == 0) {
                            val photo = runCatching { photoRepository.get(p) }.getOrNull() ?: continue
                            photoRepository.safeDeletePhoto(photo)
                        }
                    }
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun moveAlbum(albumUuid: String, newParentUuid: String?): Result<Unit> =
        withContext(IO) {
            if (newParentUuid != null) {
                val all = albumDao.getAllAlbums()
                if (wouldCreateCycle(movingUuid = albumUuid, targetParent = newParentUuid, all = all)) {
                    return@withContext Result.failure(IllegalStateException("Cannot move album: would create a cycle"))
                }
            }
            albumDao.updateParentAlbum(albumUuid, newParentUuid)
            Result.success(Unit)
        }

    override suspend fun deleteAll() {
        albumDao.deleteAll()
    }

    override suspend fun link(photoUUIDs: List<String>, albumUUID: String) {
        albumDao.link(photoUUIDs, albumUUID)
    }

    override suspend fun link(ref: AlbumPhotoRef) {
        with(ref) {
            albumDao.link(
                photoId = photoUUID,
                albumId = albumUUID,
                linkedAt = linkedAt,
            )
        }
    }

    override suspend fun unlink(photoUUIDs: List<String>, uuid: String) {
        albumDao.unlink(photoUUIDs, uuid)
    }

    override suspend fun unlinkAll() {
        albumDao.unlinkAll()
    }

    override suspend fun rename(albumUUID: String, newName: String) {
        albumDao.renameAlbum(albumUUID = albumUUID, newName = newName)
    }

    override suspend fun updateAlbumThumbnail(albumUUID: String, customThumbnailUri: String) {
        albumDao.updateAlbumThumbnailUri(albumUUID = albumUUID, uri = customThumbnailUri)
    }

    override suspend fun updateAlbumPriorities(priorities: Map<String, Int>) {
        albumDao.updateAlbumPriorities(priorities)
    }

    override suspend fun getAllAlbumPhotoLinks(): List<AlbumPhotoRef> =
        albumDao.getAllAlbumPhotoRefs().map { ref ->
            ref.toDomain()
        }
}

private fun buildSubtreeUuids(rootUuid: String, allAlbums: List<AlbumTable>): List<String> {
    val byParent = allAlbums.groupBy { it.parentAlbumUuid }
    val out = mutableListOf<String>()
    val queue = ArrayDeque<String>()
    queue.add(rootUuid)
    while (queue.isNotEmpty()) {
        val u = queue.removeFirst()
        out.add(u)
        byParent[u]?.sortedByDescending { it.modifiedAt }?.forEach { queue.add(it.uuid) }
    }
    return out
}

private fun postOrderAlbumUuids(rootUuid: String, allAlbums: List<AlbumTable>): List<String> {
    val childrenByParent = allAlbums.groupBy { it.parentAlbumUuid }
    val out = mutableListOf<String>()
    fun dfs(u: String) {
        childrenByParent[u]?.forEach { dfs(it.uuid) }
        out.add(u)
    }
    dfs(rootUuid)
    return out
}

private fun wouldCreateCycle(movingUuid: String, targetParent: String, all: List<AlbumTable>): Boolean {
    var current: String? = targetParent
    val visited = mutableSetOf<String>()
    while (current != null) {
        if (current == movingUuid) return true
        if (!visited.add(current)) return true
        current = all.find { it.uuid == current }?.parentAlbumUuid
    }
    return false
}
