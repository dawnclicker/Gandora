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

package dev.leonlatsch.photok.gallery.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonlatsch.photok.gallery.components.ImportChoice
import dev.leonlatsch.photok.gallery.components.PhotoTile
import dev.leonlatsch.photok.gallery.ui.navigation.GalleryNavigationEvent
import dev.leonlatsch.photok.gallery.ui.navigation.PhotoAction
import dev.leonlatsch.photok.model.repositories.ImportSource
import dev.leonlatsch.photok.model.repositories.PhotoRepository
import dev.leonlatsch.photok.sort.domain.SortConfig
import dev.leonlatsch.photok.gallery.albums.domain.AlbumRepository
import dev.leonlatsch.photok.gallery.albums.toUi
import dev.leonlatsch.photok.sort.domain.SortRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    photoRepository: PhotoRepository,
    private val galleryUiStateFactory: GalleryUiStateFactory,
    private val sortRepository: SortRepository,
    private val albumRepository: AlbumRepository,
) : ViewModel() {

    private val sortFlow = sortRepository.observeSortFor(albumUuid = null, default = SortConfig.Gallery.default)

    private val favoritesOnly = MutableStateFlow(false)
    val showFavoritesOnly: StateFlow<Boolean> = favoritesOnly.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val photosFlow = combine(sortFlow, favoritesOnly) { sort, fav ->
        sort to fav
    }.flatMapLatest { (sort, fav) ->
        photoRepository.observeAll(sort, fav)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), listOf())

    private val albumsFlow = albumRepository.observeAllAlbumsWithPhotos()
        .map { albums -> albums.map { it.toUi() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    private val showAlbumSelectionDialog = MutableStateFlow(false)

    val uiState: StateFlow<GalleryUiState> = combine(
        photosFlow,
        showAlbumSelectionDialog,
        sortFlow,
        favoritesOnly,
        albumsFlow,
    ) { photos, showAlbumSelection, sort, fav, albums ->
        galleryUiStateFactory.create(
            photos,
            showAlbumSelection,
            sort,
            fav,
            folderTiles = albums,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), GalleryUiState.Empty)

    private val eventsChannel = Channel<GalleryNavigationEvent>()
    val eventsFlow = eventsChannel.receiveAsFlow()

    private val photoActionsChannel = Channel<PhotoAction>()
    val photoActions = photoActionsChannel.receiveAsFlow()

    fun handleUiEvent(event: GalleryUiEvent) {
        when (event) {
            is GalleryUiEvent.OpenPhoto -> navigateToPhoto(event.item)
            is GalleryUiEvent.OnDelete -> onDeleteSelectedItems(event.items)
            is GalleryUiEvent.OnExport -> onExportSelectedItems(event.items, event.target)
            is GalleryUiEvent.OnAddToAlbum -> showAlbumSelectionDialog.value = true
            GalleryUiEvent.CancelAlbumSelection -> showAlbumSelectionDialog.value = false
            is GalleryUiEvent.OnImportChoice -> onImportChoice(event.choice)
            is GalleryUiEvent.SortChanged -> viewModelScope.launch {
                sortRepository.updateSortFor(albumUuid = null, sort = event.sort)
            }
            GalleryUiEvent.ToggleFavoritesFilter -> {
                favoritesOnly.update { !it }
            }
            is GalleryUiEvent.ReorderAlbums -> {
                viewModelScope.launch {
                    val currentOrder = albumsFlow.value.map { it.id }.toMutableList()
                    if (event.fromIndex in currentOrder.indices && event.toIndex in currentOrder.indices) {
                        val moved = currentOrder.removeAt(event.fromIndex)
                        currentOrder.add(event.toIndex, moved)
                        val updatedPriorities = currentOrder
                            .mapIndexed { index, albumId -> albumId to index }
                            .toMap()
                        albumRepository.updateAlbumPriorities(updatedPriorities)
                    }
                }
            }
            is GalleryUiEvent.ChangeAlbumThumbnail -> {
                viewModelScope.launch {
                    albumRepository.updateAlbumThumbnail(
                        albumUuid = event.albumUUID,
                        customThumbnailUri = event.thumbnailUri.toString(),
                    )
                }
            }
            is GalleryUiEvent.OpenAlbum -> {
                viewModelScope.launch {
                    eventsChannel.send(GalleryNavigationEvent.OpenAlbum(event.albumId))
                }
            }
        }
    }

    private fun onImportChoice(choice: ImportChoice) {
        val navEvent = when (choice) {
            is ImportChoice.AddNewFiles -> GalleryNavigationEvent.StartImport(
                fileUris = choice.fileUris,
                importSource = ImportSource.InApp,
            )
            is ImportChoice.RestoreBackup -> GalleryNavigationEvent.StartRestoreBackup(choice.backupUri)
        }

        eventsChannel.trySend(navEvent)
    }

    private fun onExportSelectedItems(selectedItems: List<String>, target: Uri?) {
        target ?: return
        photoActionsChannel.trySend(
            PhotoAction.ExportPhotos(
                photosFlow.value.filter { selectedItems.contains(it.uuid) },
                target,
            )
        )
    }

    private fun onDeleteSelectedItems(selectedItems: List<String>) {
        photoActionsChannel.trySend(
            PhotoAction.DeletePhotos(
                photosFlow.value.filter { selectedItems.contains(it.uuid) }
            )
        )
    }

    suspend fun loadAlbumPhotos(albumUuid: String): List<PhotoTile> =
        withContext(Dispatchers.IO) {
            albumRepository.getPhotosForAlbum(albumUuid, false).map { photo ->
                PhotoTile(
                    fileName = photo.fileName,
                    type = photo.type,
                    uuid = photo.uuid,
                    isFavorite = photo.isFavorite,
                )
            }
        }

    private fun navigateToPhoto(item: PhotoTile) {
        photoActionsChannel.trySend(
            PhotoAction.OpenPhoto(
                photoUUID = item.uuid,
                favoritesOnly = favoritesOnly.value,
            ),
        )
    }
}

