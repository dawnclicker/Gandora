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

package dev.leonlatsch.photok.gallery.components

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenuItem
import dev.leonlatsch.photok.R
import dev.leonlatsch.photok.gallery.albums.ui.compose.AlbumItem
import dev.leonlatsch.photok.model.database.entity.PhotoType
import dev.leonlatsch.photok.other.extensions.launchAndIgnoreTimer
import dev.leonlatsch.photok.settings.ui.compose.LocalConfig
import dev.leonlatsch.photok.transcoding.compose.model.EncryptedImageRequestData
import dev.leonlatsch.photok.transcoding.compose.rememberEncryptedImagePainter
import dev.leonlatsch.photok.ui.components.ConfirmationDialog
import dev.leonlatsch.photok.ui.components.MagicFab
import dev.leonlatsch.photok.ui.components.MultiSelectionMenu
import dev.leonlatsch.photok.ui.theme.AppTheme

private const val PORTRAIT_COLUMN_COUNT = 3
private const val LANDSCAPE_COLUMN_COUNT = 6

@Composable
fun PhotoGallery(
    photos: List<PhotoTile>,
    albumName: String?,
    multiSelectionState: MultiSelectionState,
    onOpenPhoto: (PhotoTile) -> Unit,
    onExport: (Uri?) -> Unit,
    onDelete: () -> Unit,
    onImportChoice: (ImportChoice) -> Unit,
    additionalMultiSelectionActions: @Composable (ColumnScope.() -> Unit),
    modifier: Modifier = Modifier,
    folderTiles: List<AlbumItem> = emptyList(),
    onOpenFolder: (String) -> Unit = {},
    onAlbumReordered: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    onAlbumChangeThumbnail: (String, Uri) -> Unit = { _, _ -> },
    onLoadAlbumPhotos: suspend (String) -> List<PhotoTile> = { emptyList() },
) {
    val activity = LocalActivity.current
    var importMenuBottomSheetVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(multiSelectionState.isActive.value) {
        if (multiSelectionState.isActive.value) {
            importMenuBottomSheetVisible = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        PhotoGrid(
            folderTiles = folderTiles,
            onOpenFolder = onOpenFolder,
            photos = photos,
            multiSelectionState = multiSelectionState,
            openPhoto = onOpenPhoto,
            onAlbumReordered = onAlbumReordered,
            onAlbumChangeThumbnail = onAlbumChangeThumbnail,
            onLoadAlbumPhotos = onLoadAlbumPhotos,
        )

        AnimatedVisibility(
            visible = multiSelectionState.isActive.value.not(),
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomEnd)
        ) {
            MagicFab(
                label = stringResource(R.string.import_menu_fab_label),
                onClick = {
                    importMenuBottomSheetVisible = true
                }
            )
        }

        ImportMenuBottomSheet(
            open = importMenuBottomSheetVisible,
            onDismissRequest = {
                importMenuBottomSheetVisible = false
            },
            onImportChoice = onImportChoice,
            albumName = albumName,
        )

        var showDeleteConfirmationDialog by remember {
            mutableStateOf(false)
        }

        var showExportConfirmationDialog by remember {
            mutableStateOf(false)
        }

        var exportDirectoryUri by remember { mutableStateOf<Uri?>(null) }

        val pickExportTargetLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { exportTarget ->
                exportTarget ?: return@rememberLauncherForActivityResult
                exportDirectoryUri = exportTarget
                showExportConfirmationDialog = true
            }

        ConfirmationDialog(
            show = showDeleteConfirmationDialog,
            onDismissRequest = { showDeleteConfirmationDialog = false },
            text = stringResource(
                R.string.delete_are_you_sure,
                multiSelectionState.selectedItems.value.size
            ),
            onConfirm = {
                onDelete()
                multiSelectionState.cancelSelection()
            }
        )

        ConfirmationDialog(
            show = showExportConfirmationDialog,
            onDismissRequest = { showExportConfirmationDialog = false },
            text = stringResource(
                if (LocalConfig.current?.deleteExportedFiles == true) {
                    R.string.export_and_delete_are_you_sure
                } else {
                    R.string.export_are_you_sure
                },
                multiSelectionState.selectedItems.value.size
            ),
            onConfirm = {
                onExport(exportDirectoryUri)
                multiSelectionState.cancelSelection()
            }
        )

        MultiSelectionMenu(
            modifier = Modifier.align(Alignment.BottomCenter),
            multiSelectionState = multiSelectionState,
        ) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_select_all),
                        contentDescription = null
                    )
                },
                text = { Text(stringResource(R.string.menu_ms_select_all)) },
                onClick = {
                    multiSelectionState.selectAll()
                    multiSelectionState.dismissMore()
                },
            )
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = null
                    )
                },
                text = { Text(stringResource(R.string.common_delete)) },
                onClick = {
                    showDeleteConfirmationDialog = true
                    multiSelectionState.dismissMore()
                },
            )
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_export),
                        contentDescription = null
                    )
                },
                text = { Text(stringResource(R.string.common_export)) },
                onClick = {
                    pickExportTargetLauncher.launchAndIgnoreTimer(
                        input = null,
                        activity = activity,
                    )
                    multiSelectionState.dismissMore()
                },
            )

            additionalMultiSelectionActions()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoGrid(
    folderTiles: List<AlbumItem>,
    onOpenFolder: (String) -> Unit,
    photos: List<PhotoTile>,
    multiSelectionState: MultiSelectionState,
    openPhoto: (PhotoTile) -> Unit,
    modifier: Modifier = Modifier,
    onAlbumReordered: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    onAlbumChangeThumbnail: (String, Uri) -> Unit = { _, _ -> },
    onLoadAlbumPhotos: suspend (String) -> List<PhotoTile> = { emptyList() },
) {
    val gridState: LazyGridState = rememberLazyGridState()
    val density = LocalDensity.current

    var visibleFolderTiles by remember { mutableStateOf(folderTiles) }
    LaunchedEffect(folderTiles) {
        visibleFolderTiles = folderTiles
    }

    var draggingAlbumId by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableStateOf<Int?>(null) }
    var dragDistance by remember { mutableStateOf(0f) }
    var showContextMenuForAlbumId by remember { mutableStateOf<String?>(null) }
    var thumbnailSelectionAlbumId by remember { mutableStateOf<String?>(null) }
    var thumbnailSelectionPhotos by remember { mutableStateOf<List<PhotoTile>>(emptyList()) }
    var thumbnailSelectionLoading by remember { mutableStateOf(false) }
    var thumbnailSelectionError by remember { mutableStateOf(false) }

    LaunchedEffect(thumbnailSelectionAlbumId) {
        val albumId = thumbnailSelectionAlbumId
        if (albumId == null) {
            thumbnailSelectionPhotos = emptyList()
            thumbnailSelectionLoading = false
            thumbnailSelectionError = false
            return@LaunchedEffect
        }

        thumbnailSelectionLoading = true
        thumbnailSelectionError = false
        thumbnailSelectionPhotos = try {
            onLoadAlbumPhotos(albumId)
        } catch (e: Exception) {
            thumbnailSelectionError = true
            emptyList()
        } finally {
            thumbnailSelectionLoading = false
        }
    }

    val dragThreshold = remember { with(density) { 8.dp.toPx() } }
    val haptic = LocalHapticFeedback.current

    LazyVerticalGrid(
        columns = GridCells.Fixed(
            if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT) {
                PORTRAIT_COLUMN_COUNT
            } else {
                LANDSCAPE_COLUMN_COUNT
            }
        ),
        modifier = modifier
            .pointerInput(visibleFolderTiles, gridState) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val touchedItem = gridState.layoutInfo.visibleItemsInfo
                            .firstOrNull { item ->
                                offset.x >= item.offset.x &&
                                    offset.x <= item.offset.x + item.size.width &&
                                    offset.y >= item.offset.y &&
                                    offset.y <= item.offset.y + item.size.height
                            }
                        val albumId = touchedItem?.key?.toString()?.removePrefix("album_")
                        val index = albumId?.let { id -> visibleFolderTiles.indexOfFirst { it.id == id } }
                        if (albumId != null && index != null && index >= 0) {
                            draggingAlbumId = albumId
                            dragStartIndex = index
                            dragDistance = 0f
                        }
                    },
                    onDrag = { change, dragAmount ->
                        if (draggingAlbumId == null) return@detectDragGesturesAfterLongPress
                        // Consume immediately to block parent scroll
                        change.consume()
                        dragDistance += abs(dragAmount.y) + abs(dragAmount.x)

                        val pointerPosition = change.position
                        val targetItem = gridState.layoutInfo.visibleItemsInfo
                            .firstOrNull { item ->
                                pointerPosition.x >= item.offset.x &&
                                    pointerPosition.x <= item.offset.x + item.size.width &&
                                    pointerPosition.y >= item.offset.y &&
                                    pointerPosition.y <= item.offset.y + item.size.height
                            }

                        val targetId = targetItem?.key?.toString()?.removePrefix("album_")
                        if (targetId == null) return@detectDragGesturesAfterLongPress
                        val currentIndex = visibleFolderTiles.indexOfFirst { it.id == draggingAlbumId }
                        val targetIndex = visibleFolderTiles.indexOfFirst { it.id == targetId }
                        if (currentIndex != -1 && targetIndex != -1 && targetIndex != currentIndex) {
                            visibleFolderTiles = visibleFolderTiles.toMutableList().apply {
                                add(targetIndex, removeAt(currentIndex))
                            }
                        }
                    },
                    onDragEnd = {
                        val albumId = draggingAlbumId
                        val startIndex = dragStartIndex
                        val endIndex = albumId?.let { id -> visibleFolderTiles.indexOfFirst { it.id == id } }
                        if (albumId != null && dragDistance < dragThreshold) {
                            showContextMenuForAlbumId = albumId
                        } else if (
                            albumId != null &&
                            startIndex != null &&
                            endIndex != null &&
                            startIndex != endIndex
                        ) {
                            onAlbumReordered(startIndex, endIndex)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        draggingAlbumId = null
                        dragStartIndex = null
                        dragDistance = 0f
                    },
                    onDragCancel = {
                        draggingAlbumId = null
                        dragStartIndex = null
                        dragDistance = 0f
                    }
                )
            },
        state = gridState
    ) {
        items(visibleFolderTiles, key = { "album_${it.id}" }) { album ->
            AlbumTile(
                album = album,
                onAlbumClicked = { id ->
                    if (!multiSelectionState.isActive.value) {
                        onOpenFolder(id)
                    }
                },
                modifier = Modifier.animateItem(),
                isDragging = album.id == draggingAlbumId,
            )
        }
        items(photos, key = { it.uuid }) {
            GalleryPhotoTile(
                photoTile = it,
                multiSelectionActive = multiSelectionState.isActive.value,
                onClicked = {
                    if (multiSelectionState.isActive.value.not()) {
                        openPhoto(it)
                        return@GalleryPhotoTile
                    }

                    if (multiSelectionState.selectedItems.value.contains(it.uuid)) {
                        multiSelectionState.deselectItem(it.uuid)
                    } else {
                        multiSelectionState.selectItem(it.uuid)
                    }
                    
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                },
                selected = multiSelectionState.selectedItems.value.contains(it.uuid),
                onLongPress = {
                    if (multiSelectionState.isActive.value.not()) {
                        multiSelectionState.selectItem(it.uuid)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                modifier = Modifier.animateItem(),
            )
        }
    }

    if (showContextMenuForAlbumId != null) {
        AlertDialog(
            onDismissRequest = { showContextMenuForAlbumId = null },
            confirmButton = {
                TextButton(onClick = {
                    thumbnailSelectionAlbumId = showContextMenuForAlbumId
                    showContextMenuForAlbumId = null
                }) {
                    Text(stringResource(R.string.album_change_thumbnail))
                }
            },
            dismissButton = {
                TextButton(onClick = { showContextMenuForAlbumId = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            title = {
                Text(stringResource(R.string.album_thumbnail_menu_title))
            },
            text = {
                Text(stringResource(R.string.album_thumbnail_menu_description))
            }
        )
    }

    if (thumbnailSelectionAlbumId != null) {
        Dialog(onDismissRequest = { thumbnailSelectionAlbumId = null }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.album_thumbnail_picker_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (thumbnailSelectionLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else if (thumbnailSelectionError) {
                        Text(stringResource(R.string.album_thumbnail_picker_error))
                    } else if (thumbnailSelectionPhotos.isEmpty()) {
                        Text(stringResource(R.string.album_thumbnail_picker_empty))
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(360.dp),
                        ) {
                            items(thumbnailSelectionPhotos, key = { it.uuid }) { photoTile ->
                                GalleryPhotoTile(
                                    photoTile = photoTile,
                                    multiSelectionActive = false,
                                    selected = false,
                                    onClicked = {
                                        val albumId = thumbnailSelectionAlbumId
                                        if (albumId != null) {
                                            onAlbumChangeThumbnail(albumId, albumThumbnailUri(photoTile))
                                        }
                                        thumbnailSelectionAlbumId = null
                                    },
                                    onLongPress = {},
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = { thumbnailSelectionAlbumId = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            }
        }
    }
}

private const val InternalAlbumThumbnailUriScheme = "photok-internal-thumbnail"
private const val InternalAlbumThumbnailMimeTypeQuery = "mimeType"

fun albumThumbnailUri(photoTile: PhotoTile): Uri =
    Uri.Builder()
        .scheme(InternalAlbumThumbnailUriScheme)
        .authority(photoTile.uuid)
        .appendQueryParameter(InternalAlbumThumbnailMimeTypeQuery, photoTile.type.mimeType)
        .build()

fun parseAlbumThumbnailUri(uriString: String): Pair<String, String>? {
    val uri = Uri.parse(uriString)
    if (uri.scheme != InternalAlbumThumbnailUriScheme) return null
    val uuid = uri.host.orEmpty().ifEmpty { uri.pathSegments.firstOrNull().orEmpty() }
    if (uuid.isEmpty()) return null
    val mimeType = uri.getQueryParameter(InternalAlbumThumbnailMimeTypeQuery).orEmpty()
    return uuid to mimeType
}

private val VideoIconSize = 20.dp
private val SelectedPadding = 15.dp
private val CheckmarkPadding = SelectedPadding - 9.dp

@Composable
fun Modifier.multiSelectionItem(selected: Boolean): Modifier {
    val animatedPadding by animateDpAsState(
        targetValue = if (selected) { SelectedPadding } else { 0.dp }
    )
    val animatedShape by animateDpAsState(
        targetValue = if (selected) { 12.dp } else { 0.dp }
    )

    return this
        .padding(animatedPadding)
        .clip(RoundedCornerShape(animatedShape))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryPhotoTile(
    modifier: Modifier = Modifier,
    photoTile: PhotoTile,
    multiSelectionActive: Boolean,
    selected: Boolean,
    onClicked: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        modifier = modifier
            .padding(.5.dp)
            .combinedClickable(
                role = Role.Image,
                onClick = onClicked,
                onLongClick = onLongPress,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            )
    ) {
        val contentModifier = Modifier
            .multiSelectionItem(selected)
            .fillMaxSize()
            .aspectRatio(1f)

        if (LocalInspectionMode.current) {
            Box(
                modifier = contentModifier.background(Color.DarkGray)
            )
        } else {
            val requestData = remember(photoTile) {
                EncryptedImageRequestData(
                    internalFileName = photoTile.internalThumbnailFileName,
                    mimeType = photoTile.type.mimeType
                )
            }

            Image(
                painter = rememberEncryptedImagePainter(requestData),
                contentDescription = photoTile.fileName,
                modifier = contentModifier
            )
        }

        AnimatedVisibility(
            visible = photoTile.type.isVideo && !selected,
            enter = scaleIn(),
            exit = scaleOut(),
            modifier = Modifier
                .padding(2.dp)
                .size(VideoIconSize)
                .align(Alignment.BottomStart)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_videocam),
                contentDescription = null,
                tint = Color.White
            )
        }

        AnimatedVisibility(
            visible = photoTile.isFavorite && !selected,
            enter = scaleIn(),
            exit = scaleOut(),
            modifier = Modifier
                .padding(4.dp)
                .size(18.dp)
                .align(Alignment.TopEnd)
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }

        AnimatedVisibility(
            visible = multiSelectionActive && selected,
            enter = scaleIn(),
            exit = scaleOut(),
            ) {
            Icon(
                painter = painterResource(R.drawable.ic_check_circle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .padding(CheckmarkPadding)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .align(Alignment.TopStart)
            )
        }
    }
}