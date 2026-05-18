/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.actions

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cz.vut.oneshotdetector.view.layout.AppBackButton
import cz.vut.oneshotdetector.view.layout.AppScreen
import cz.vut.oneshotdetector.view.layout.AppTopBar
import cz.vut.oneshotdetector.view.layout.ScreenColumn
import cz.vut.oneshotdetector.view.screen.actions.components.PhotoEditSection
import cz.vut.oneshotdetector.view.theme.LocalSpacing

@Composable
fun PhotoActionsScreen(
    photoUri: Uri,
    label: String,
    description: String,
    onLabelChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onRetake: () -> Unit,
    onCropPhoto: (() -> Unit)? = null,
    onRemoveBackground: (() -> Unit)? = null,
    onSave: () -> Unit,
    onBack: () -> Unit,
    existingLabels: List<String> = emptyList()
) {
    val spacing = LocalSpacing.current
    var showToolsMenu by remember { mutableStateOf(false) }

    AppScreen(
        topBar = {
            AppTopBar(
                title = { Text("New photo", color = MaterialTheme.colorScheme.onTertiary) },
                navigationIcon = { AppBackButton(onClick = onBack) },
                actions = {
                    IconButton(onClick = onRetake) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Retake", tint = MaterialTheme.colorScheme.onTertiary)
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Button(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md, vertical = spacing.md),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Save")
            }
        }
    }
}