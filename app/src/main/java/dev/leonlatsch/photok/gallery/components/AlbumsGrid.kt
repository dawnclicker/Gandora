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

import android.net.Uri
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.leonlatsch.photok.R
import dev.leonlatsch.photok.gallery.albums.ui.compose.AlbumItem
import kotlin.math.abs

@Composable
fun AlbumsGrid(
    albums: List<AlbumItem>,
    onAlbumClicked: (String) -> Unit,
    onAlbumReordered: (Int, Int) -> Unit = { _, _ -> },
    onAlbumLongClick: (String) -> Unit = {},
    onLoadAlbumPhotos: suspend (String) -> List<PhotoTile> = { emptyList() },
    onAlbumChangeThumbnail: (String, Uri) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val visibleAlbums = remember { mutableStateListOf<AlbumItem>() }
    LaunchedEffect(albums) {
        visibleAlbums.clear()
        visibleAlbums.addAll(albums)
    }

    var draggingAlbumId by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableStateOf<Int?>(null) }
    var dragDistance by remember { mutableStateOf(0f) }
    var thumbnailSelectionAlbumId by remember { mutableStateOf<String?>(null) }
    var thumbnailSelectionPhotos by remember { mutableStateOf<List<PhotoTile>>(emptyList()) }
    var thumbnailSelectionLoading by remember { mutableStateOf(false) }
    var thumbnailSelectionError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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

    val dragThreshold = with(LocalDensity.current) { 8.dp.toPx() }
    val haptic = LocalHapticFeedback.current

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier
            .pointerInput(visibleAlbums, gridState) {
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
                        val index = albumId?.let { id -> visibleAlbums.indexOfFirst { it.id == id } }
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
                        val currentIndex = visibleAlbums.indexOfFirst { it.id == draggingAlbumId }
                        val targetIndex = visibleAlbums.indexOfFirst { it.id == targetId }
                        if (currentIndex != -1 && targetIndex != -1 && targetIndex != currentIndex) {
                            visibleAlbums.apply {
                                add(targetIndex, removeAt(currentIndex))
                            }
                        }
                    },
                    onDragEnd = {
                        val albumId = draggingAlbumId
                        val startIndex = dragStartIndex
                        val endIndex = albumId?.let { id -> visibleAlbums.indexOfFirst { it.id == id } }
                        if (
                            albumId != null &&
                            startIndex != null &&
                            endIndex != null &&
                            startIndex != endIndex
                        ) {
                            onAlbumReordered(startIndex, endIndex)
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
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
            }
            .fillMaxWidth(),
        state = gridState
    ) {
        items(visibleAlbums, key = { "album_${it.id}" }) { album ->
            AlbumTile(
                album = album,
                onAlbumClicked = onAlbumClicked,
                onLongClick = onAlbumLongClick,
                modifier = Modifier.animateItem(),
                isDragging = album.id == draggingAlbumId,
            )
        }
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
                        androidx.compose.foundation.layout.Box(
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
