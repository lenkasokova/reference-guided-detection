/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.gallery.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import cz.vut.oneshotdetector.view.layout.AppBackButton
import cz.vut.oneshotdetector.view.layout.AppTopBar
import cz.vut.oneshotdetector.view.theme.Spacing

@Composable
fun GalleryTopBar(
    hasImages: Boolean,
    filterState: TextFieldState,
    spacing: Spacing,
    onAddImage: () -> Unit,
    onBack: () -> Unit,
) {
    AppTopBar(
        title = { Text("My Plants", color = MaterialTheme.colorScheme.onTertiary) },
        navigationIcon = { AppBackButton(onClick = onBack) },
        actions = {
            IconButton(onClick = onAddImage) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Add image",
                    tint = MaterialTheme.colorScheme.onTertiary
                )
            }
        },
        bottomContent = if (hasImages) {
            { GalleryFilterField(filterState = filterState, spacing = spacing) }
        } else null
    )
}
