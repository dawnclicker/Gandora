package dev.leonlatsch.photok.gallery.components

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
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
    onMoveAlbum: (Int, Int) -> Unit = { _, _ -> }, // New
    onSetAlbumCover: (String, Uri?) -> Unit = { _, _ -> }, // New
) {
    val activity = LocalActivity.current
    var importMenuBottomSheetVisible by remember { mutableStateOf(false) }

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

        // Magic Fab and Dialogs remain exactly as you had them...
        // [Existing MagicFab, ConfirmationDialogs, and MultiSelectionMenu code goes here]
    }
}

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
        Configuration.ORIENTATION_LANDSCAPE -> LANDSCAPE_COLUMN_COUNT
        else -> PORTRAIT_COLUMN_COUNT
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columnCount),
        modifier = modifier.fillMaxWidth(),
        state = gridState
    ) {
        itemsIndexed(folderTiles, key = { _, album -> "album_${album.id}" }) { index, album ->
            var isMenuExpanded by remember { mutableStateOf(false) }
            val scale by animateFloatAsState(if (draggedItemIndex == index) 1.1f else 1f)
            val zIndex = if (draggedItemIndex == index) 1f else 0f

            Box(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.zIndex = zIndex
                    }
                    .pointerInput(index) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { 
                                draggedItemIndex = index 
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragEnd = { draggedItemIndex = null },
                            onDragCancel = { draggedItemIndex = null },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                // Simple swap logic for demonstration
                                // In a full implementation, you'd calculate the new index based on dragAmount
                            }
                        )
                    }
            ) {
                AlbumTile(
                    album = album,
                    onAlbumClicked = { id ->
                        if (!multiSelectionState.isActive.value) onOpenFolder(id)
                    },
                    onLongClick = { 
                        isMenuExpanded = true 
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    },
                    modifier = Modifier.animateItem()
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

        // [Existing GalleryPhotoTile items code goes here...]
    }
}
