/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier


/**
 * Shared scaffold for all screens in the app. It provides a consistent layout structure, including
 */

@Composable
fun AppScreen(
    topBar: @Composable (() -> Unit)? = null,
    floatingActionButton: @Composable (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    AppScaffold(
        topBar = topBar,
        floatingActionButton = floatingActionButton
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            content(paddingValues)
        }
    }
}