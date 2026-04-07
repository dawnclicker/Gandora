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

package dev.leonlatsch.photok.gallery.albums.detail.ui.compose

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.leonlatsch.photok.R
import dev.leonlatsch.photok.gallery.albums.detail.ui.AlbumDetailUiEvent
import dev.leonlatsch.photok.gallery.albums.detail.ui.AlbumDetailViewModel
import dev.leonlatsch.photok.gallery.albums.ui.AlbumsFragmentDirections
import dev.leonlatsch.photok.gallery.albums.ui.compose.RenameAlbumDialog
import dev.leonlatsch.photok.sort.domain.SortConfig
import dev.leonlatsch.photok.sort.ui.SortingMenu
import dev.leonlatsch.photok.sort.ui.SortingMenuIconButton
import dev.leonlatsch.photok.ui.components.FolderDeleteConfirmDialog
import dev.leonlatsch.photok.ui.components.RoundedDropdownMenu
import dev.leonlatsch.photok.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(viewModel: AlbumDetailViewModel, navController: NavController) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showConfirmDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showCreateSubfolderDialog by remember { mutableStateOf(false) }
    var showChangeThumbnailDialog by remember { mutableStateOf(false) }

    AppTheme {
        Scaffold(
            topBar = {
                var showMore by remember { mutableStateOf(false) }

                LargeTopAppBar(
                    title = { Text(uiState.albumName) },
                    navigationIcon = {
                        IconButton(
                            onClick = { navController.navigateUp() }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_back),
                                contentDescription = stringResource(R.string.process_close)
                            )
                        }
                    },
                    windowInsets = WindowInsets.statusBars,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        var showSortMenu by remember { mutableStateOf(false) }

                        SortingMenuIconButton(
                            config = SortConfig.Album,
                            sort = uiState.sort,
                            onClick = { showSortMenu = true }
                        )

                        SortingMenu(
                            config = SortConfig.Album,
                            expanded = showSortMenu,
                            onDismissRequest = {showSortMenu = false },
                            sort = uiState.sort,
                            onSortChanged = { viewModel.handleUiEvent(AlbumDetailUiEvent.SortChanged(it)) },
                        )

                        IconButton(
                            onClick = { viewModel.handleUiEvent(AlbumDetailUiEvent.ToggleFavoritesFilter) },
                        ) {
                            Icon(
                                imageVector = if (uiState.showFavoritesOnly) Icons.Filled.Star else Icons.Outlined.Star,
                                contentDescription = stringResource(R.string.gallery_favorites_toggle),
                            )
                        }

                        IconButton(onClick = { showMore = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_more),
                                contentDescription = stringResource(R.string.common_more)
                            )
                        }

                        RoundedDropdownMenu(
                            expanded = showMore,
                            onDismissRequest = { showMore = false },
                            modifier = Modifier.animateContentSize()
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.album_new_subfolder)) },
                                onClick = {
                                    showMore = false
                                    showCreateSubfolderDialog = true
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_folder),
                                        contentDescription = null,
                                    )
                                },
                            )

                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.album_change_thumbnail)) },
                                onClick = {
                                    showMore = false
                                    showChangeThumbnailDialog = true
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_gallery_thumbnail),
                                        contentDescription = stringResource(R.string.album_change_thumbnail)
                                    )
                                },
                            )

                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_delete)) },
                                onClick = {
                                    showMore = false
                                    showConfirmDeleteDialog = true
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete),
                                        contentDescription = stringResource(R.string.common_delete)
                                    )
                                }
                            )

                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_rename)) },
                                onClick = {
                                    showMore = false
                                    showRenameDialog = true
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_edit),
                                        contentDescription = stringResource(R.string.common_rename),
                                    )
                                }
                            )
                        }
                    }
                )
            }
        ) { contentPadding ->
            AlbumDetailContent(
                uiState = uiState,
                handleUiEvent = { viewModel.handleUiEvent(it) },
                onOpenChildAlbum = { uuid ->
                    navController.navigate(
                        AlbumsFragmentDirections.actionGlobalAlbumDetailFragment(albumUuid = uuid),
                    )
                },
                onLoadAlbumPhotos = { viewModel.loadAlbumPhotos(it) },
                thumbnailSelectionAlbumId = if (showChangeThumbnailDialog) uiState.albumId else null,
                onThumbnailSelectionDismiss = { showChangeThumbnailDialog = false },
                modifier = Modifier
                    .padding(top = contentPadding.calculateTopPadding())
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
            )

            FolderDeleteConfirmDialog(
                show = showConfirmDeleteDialog,
                onDismissRequest = { showConfirmDeleteDialog = false },
                message = stringResource(R.string.album_delete_folder_message),
                permanentDeleteLabel = stringResource(R.string.album_delete_permanent_checkbox),
                onConfirm = { permanent ->
                    viewModel.handleUiEvent(AlbumDetailUiEvent.DeleteAlbum(permanent))
                },
            )

            CreateSubfolderDialog(
                show = showCreateSubfolderDialog,
                onDismissRequest = { showCreateSubfolderDialog = false },
                onCreate = { name ->
                    viewModel.handleUiEvent(AlbumDetailUiEvent.CreateSubfolder(name))
                },
            )

            RenameAlbumDialog(
                show = showRenameDialog,
                onDismiss = { showRenameDialog = false },
                currentName = uiState.albumName,
                onRename = { newName ->
                    viewModel.handleUiEvent(AlbumDetailUiEvent.RenameAlbum(newName))
                }
            )
        }
    }
}
