/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.core.net.toUri
import cz.vut.oneshotdetector.model.data.gallery.GalleryImage
import cz.vut.oneshotdetector.view.layout.AppBackButton
import cz.vut.oneshotdetector.view.layout.AppScreen
import cz.vut.oneshotdetector.view.layout.AppTopBar
import cz.vut.oneshotdetector.view.layout.ScreenColumn
import cz.vut.oneshotdetector.view.screen.actions.components.PhotoInfoSection
import cz.vut.oneshotdetector.view.theme.LocalSpacing

@Composable
fun PhotoPreviewScreen(
    image: GalleryImage,
    label: String,
    description: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var showDeleteDialog by remember { mutableStateOf(false) }

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
                title = { Text("Photo Preview", color = MaterialTheme.colorScheme.onTertiary) },
                navigationIcon = { AppBackButton(onClick = onBack) },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.onTertiary)
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
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
                PhotoInfoSection(
                    photoUri = image.uri.toUri(),
                    detections = emptyList(),
                    label = label,
                    description = description,
                    onEdit = onEdit,
                )
                Spacer(Modifier.height(spacing.md))
            }
        }
    }
}
