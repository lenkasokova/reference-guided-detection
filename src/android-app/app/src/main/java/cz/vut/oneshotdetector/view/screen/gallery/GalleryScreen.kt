/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cz.vut.oneshotdetector.model.data.gallery.GalleryImage
import cz.vut.oneshotdetector.view.layout.AppScreen
import cz.vut.oneshotdetector.view.layout.ScreenColumn
import cz.vut.oneshotdetector.view.screen.gallery.components.GalleryContent
import cz.vut.oneshotdetector.view.screen.gallery.components.GalleryTopBar
import cz.vut.oneshotdetector.view.theme.LocalSpacing
import androidx.compose.foundation.text.input.rememberTextFieldState
import cz.vut.oneshotdetector.viewmodel.gallery.GalleryClass

@Composable
fun GalleryScreen(
    hasImages: Boolean,
    classes: List<GalleryClass>,
    onAddImage: () -> Unit,
    onOpenImage: (GalleryImage) -> Unit,
    onBack: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val filterState = rememberTextFieldState()
    val filterQuery = filterState.text.toString()

    val filteredClasses = remember(classes, filterQuery) {
        if (filterQuery.isBlank()) classes
        else classes.filter { it.label.contains(filterQuery.trim(), ignoreCase = true) }
    }

    AppScreen(
        topBar = {
            GalleryTopBar(
                hasImages = hasImages,
                filterState = filterState,
                spacing = spacing,
                onAddImage = onAddImage,
                onBack = onBack,
            )
        }
    ) { paddingValues ->
        ScreenColumn(
            paddingValues = paddingValues,
            modifier = Modifier.background(MaterialTheme.colorScheme.tertiary)
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(spacing.md))
            GalleryContent(
                hasImages = hasImages,
                classes = filteredClasses,
                filterQuery = filterQuery,
                spacing = spacing,
                onOpenImage = onOpenImage,
            )
        }
    }
}