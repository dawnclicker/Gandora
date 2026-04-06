package dev.leonlatsch.photok.gallery.components

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.ui.zIndex
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import dev.leonlatsch.photok.R
import dev.leonlatsch.photok.gallery.albums.ui.compose.AlbumItem

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
    onMoveAlbum: (Int, Int) -> Unit = { _, _ -> },
    onSetAlbumCover: (String, Uri?) -> Unit = { _, _ -> },
) {
    Box(modifier = modifier.fillMaxSize()) {
        PhotoGrid(
            folderTiles = folderTiles,
            onOpenFolder = onOpenFolder,
            onMoveAlbum = onMoveAlbum,
            onSetAlbumCover = onSetAlbumCover,
            photos = photos,
            multiSelectionState = multiSelectionState,
            openPhoto = onOpenPhoto,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoGrid(
    folderTiles: List<AlbumItem>,
    onOpenFolder: (String) -> Unit,
    onMoveAlbum: (Int, Int) -> Unit,
    onSetAlbumCover: (String, Uri?) -> Unit,
    photos: List<PhotoTile>,
    multiSelectionState: MultiSelectionState,
    openPhoto: (PhotoTile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val haptic = LocalHapticFeedback.current
    var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
    var albumToUpdateCover by remember { mutableStateOf<String?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        albumToUpdateCover?.let { uuid -> onSetAlbumCover(uuid, uri) }
        albumToUpdateCover = null
    }

    val columnCount = when (LocalConfiguration.current.orientation) {
        Configuration.ORIENTATION_LANDSCAPE -> 6
        else -> 3
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columnCount),
        modifier = modifier.fillMaxWidth(),
        state = gridState
    ) {
        itemsIndexed(folderTiles, key = { _, album -> "album_${album.id}" }) { index, album ->
            var isMenuExpanded by remember { mutableStateOf(false) }
            val scale by animateFloatAsState(if (draggedItemIndex == index) 1.1f else 1f, label = "dragScale")
            val currentZIndex by animateFloatAsState(if (draggedItemIndex == index) 1f else 0f, label = "zIndex")

            Box(
                modifier = Modifier
                    .zIndex(currentZIndex)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .pointerInput(index) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { 
                                draggedItemIndex = index 
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragEnd = { draggedItemIndex = null },
                            onDragCancel = { draggedItemIndex = null },
                            onDrag = { change, _ -> change.consume() }
                        )
                    }
            ) {
                AlbumTile(
                    album = album,
                    onAlbumClicked = { id -> 
                        if (!multiSelectionState.isActive.value) onOpenFolder(id) 
                    },
                    modifier = Modifier
                        .animateItem()
                        .combinedClickable(
                            onClick = { 
                                if (!multiSelectionState.isActive.value) onOpenFolder(album.id) 
                            },
                            onLongClick = {
                                isMenuExpanded = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        )
                )

                DropdownMenu(
                    expanded = isMenuExpanded,
                    onDismissRequest = { isMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Set Folder Cover") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_image), null) },
                        onClick = {
                            isMenuExpanded = false
                            albumToUpdateCover = album.id
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    )
                }
            }
        }
        // Photos logic follows here...
    }
}
