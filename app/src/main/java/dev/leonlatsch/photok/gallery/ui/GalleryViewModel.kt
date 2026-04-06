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

package dev.leonlatsch.photok.gallery.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonlatsch.photok.gallery.components.ImportChoice
import dev.leonlatsch.photok.gallery.components.PhotoTile
import dev.leonlatsch.photok.gallery.ui.navigation.GalleryNavigationEvent
import dev.leonlatsch.photok.gallery.ui.navigation.PhotoAction
import dev.leonlatsch.photok.model.database.entity.AlbumTable
import dev.leonlatsch.photok.gallery.albums.domain.AlbumRepository
import dev.leonlatsch.photok.gallery.albums.ui.compose.AlbumItem
import dev.leonlatsch.photok.model.repositories.ImportSource
import dev.leonlatsch.photok.model.repositories.PhotoRepository
import dev.leonlatsch.photok.sort.domain.SortConfig
import dev.leonlatsch.photok.sort.domain.SortRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val albumRepository: AlbumRepository,
    private val galleryUiStateFactory: GalleryUiStateFactory,
    private val sortRepository: SortRepository,
) : ViewModel() {

    private val sortFlow = sortRepository.observeSortFor(albumUuid = null, default = SortConfig.Gallery.default)
    private val favoritesOnly = MutableStateFlow(false)
    val showFavoritesOnly: StateFlow<Boolean> = favoritesOnly.asStateFlow()

    private val _albums = MutableStateFlow<List<AlbumTable>>(emptyList())
    private var persistReorderJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val photosFlow = combine(sortFlow, favoritesOnly) { sort, fav ->
        sort to fav
    }.flatMapLatest { (sort, fav) ->
        photoRepository.observeAll(sort, fav)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), listOf())

    private val showAlbumSelectionDialog = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            albumRepository.observeAllAlbums().collect { 
                _albums.value = it 
            }
        }
    }

    fun moveAlbum(fromIndex: Int, toIndex: Int) {
        val currentList = _albums.value.toMutableList()
        if (fromIndex !in currentList.indices || toIndex !in currentList.indices) return

        val item = currentList.removeAt(fromIndex)
        currentList.add(toIndex, item)
        _albums.value = currentList

        persistReorderJob?.cancel()
        persistReorderJob = viewModelScope.launch {
            delay(500)
            val updatedPriorities = currentList.mapIndexed { index, album ->
                album.copy(priority = index)
            }
            albumRepository.updateAlbums(updatedPriorities)
        }
    }

    fun setAlbumCover(albumUuid: String, uri: Uri?) {
        viewModelScope.launch {
            albumRepository.setCustomThumbnail(albumUuid, uri?.toString())
        }
    }

    val uiState: StateFlow<GalleryUiState> = combine(
        photosFlow,
        _albums,
        showAlbumSelectionDialog,
        sortFlow,
        favoritesOnly,
    ) { photos, albums, showAlbumSelection, sort, fav ->
        galleryUiStateFactory.create(
            photos,
            showAlbumSelection,
            sort,
            fav,
            folderTiles = albums.map { it.toAlbumItem() },
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

    private fun navigateToPhoto(item: PhotoTile) {
        photoActionsChannel.trySend(
            PhotoAction.OpenPhoto(
                photoUUID = item.uuid,
                favoritesOnly = favoritesOnly.value,
            ),
        )
    }
}

