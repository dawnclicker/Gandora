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

package dev.leonlatsch.photok.gallery.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonlatsch.photok.R
import dev.leonlatsch.photok.gallery.albums.ui.compose.CreateAlbumDialog
import dev.leonlatsch.photok.uicomponnets.Dialogs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumPickerDialog(
    visible: Boolean,
    selectedItemIds: List<String>,
    onDismissRequest: () -> Unit,
    onAlbumSelected: () -> Unit = {},
) {
    if (visible) {
        val viewModel: AlbumPickerViewModel = hiltViewModel()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        ModalBottomSheet(onDismissRequest = onDismissRequest) {
            AlbumPickerContent(
                selectedItemIds = selectedItemIds,
                uiState = uiState,
                handleUiEvent = viewModel::handleUiEvent,
                onDismissRequest = onDismissRequest,
                onAlbumSelected = onAlbumSelected,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumPickerContent(
    selectedItemIds: List<String>,
    uiState: AlbumPickerUiState,
    handleUiEvent: (AlbumPickerUiEvent) -> Unit,
    onDismissRequest: () -> Unit,
    onAlbumSelected: () -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TopAppBar(
            title = {
                Text(
                    stringResource(R.string.gallery_albums_select_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            actions = {
                IconButton(onClick = { showCreateDialog = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null,
                    )
                }
            }
        )

        val context = LocalContext.current
        val addedMessage = stringResource(R.string.gallery_albums_photos_added, selectedItemIds.size)

        AlbumsGrid(
            albums = uiState.albums,
            onAlbumClicked = { albumId ->
                // This event name might be PickAlbum or AddPhotosToAlbum depending on your ViewModel
                handleUiEvent(AlbumPickerUiEvent.PickAlbum(albumId, selectedItemIds))
                Dialogs.showShortToast(context, addedMessage)
                onAlbumSelected()
                onDismissRequest()
            },
            onMoveAlbum = { _, _ -> },
            onSetAlbumCover = { _, _ -> },
            modifier = Modifier.fillMaxWidth()
        )
    }

    CreateAlbumDialog(
        show = showCreateDialog,
        onDismissRequest = { showCreateDialog = false },
    )
}
