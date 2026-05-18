/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.actions

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cz.vut.oneshotdetector.view.components.ConfirmDialog
import cz.vut.oneshotdetector.view.layout.AppBackButton
import cz.vut.oneshotdetector.view.layout.AppScreen
import cz.vut.oneshotdetector.view.layout.AppTopBar
import cz.vut.oneshotdetector.view.layout.ScreenColumn
import cz.vut.oneshotdetector.view.screen.actions.components.PhotoEditSection
import cz.vut.oneshotdetector.view.theme.LocalSpacing

@Composable
fun PhotoEditScreen(
    photoUri: Uri,
    label: String,
    description: String,
    onLabelChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    statusText: String?,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onCropPhoto: (() -> Unit)? = null,
    onRemoveBackground: (() -> Unit)? = null,
    existingLabels: List<String> = emptyList()
) {
    val spacing = LocalSpacing.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showToolsMenu by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        ConfirmDialog(
            title = "Delete photo",
            message = "Are you sure you want to delete this photo?",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { showDeleteDialog = false; onDelete() },
            onDismiss = { showDeleteDialog = false }
        )
    }

    AppScreen(
        topBar = {
            AppTopBar(
                title = { Text("Edit photo", color = MaterialTheme.colorScheme.onTertiary) },
                navigationIcon = { AppBackButton(onClick = onBack) },
                actions = {
                    IconButton(onClick = onSave) {
                        Icon(Icons.Filled.Save, contentDescription = "Save", tint = MaterialTheme.colorScheme.onTertiary)
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                    if (onCropPhoto != null || onRemoveBackground != null) {
                        IconButton(onClick = { showToolsMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = MaterialTheme.colorScheme.onTertiary)
                        }
                        DropdownMenu(
                            expanded = showToolsMenu,
                            onDismissRequest = { showToolsMenu = false }
                        ) {
                            if (onCropPhoto != null) {
                                DropdownMenuItem(
                                    text = { Text("Crop") },
                                    leadingIcon = { Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize)) },
                                    onClick = { showToolsMenu = false; onCropPhoto() }
                                )
                            }
                            if (onRemoveBackground != null) {
                                DropdownMenuItem(
                                    text = { Text("Remove background") },
                                    leadingIcon = { Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize)) },
                                    onClick = { showToolsMenu = false; onRemoveBackground() }
                                )
                            }
                        }
                    }

                }
            )
        }
    ) { paddingValues ->
        ScreenColumn(
            paddingValues = paddingValues,
            modifier = Modifier.background(MaterialTheme.colorScheme.tertiary)
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Column(
                modifier = Modifier
                    .padding(horizontal = spacing.md)
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(spacing.md))
                statusText?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(spacing.sm))
                }
                PhotoEditSection(
                    photoUri = photoUri,
                    detections = emptyList(),
                    label = label,
                    description = description,
                    onLabelChange = onLabelChange,
                    onDescriptionChange = onDescriptionChange,
                    existingLabels = existingLabels,
                )
                Spacer(Modifier.height(spacing.md))
            }
        }
    }
}
