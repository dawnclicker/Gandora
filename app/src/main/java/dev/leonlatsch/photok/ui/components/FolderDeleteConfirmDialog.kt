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

package dev.leonlatsch.photok.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonlatsch.photok.R

@Composable
fun FolderDeleteConfirmDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    message: String,
    permanentDeleteLabel: String,
    onConfirm: (permanentlyDeleteFiles: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!show) return

    var permanent by remember(show) { mutableStateOf(false) }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(R.string.common_no))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(permanent)
                    onDismissRequest()
                }
            ) {
                Text(text = stringResource(R.string.common_yes))
            }
        },
        text = {
            Column {
                Text(text = message)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = permanent,
                        onCheckedChange = { permanent = it },
                    )
                    Text(
                        text = permanentDeleteLabel,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    )
}
