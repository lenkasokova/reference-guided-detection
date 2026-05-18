/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.layout

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun AppScaffold(
    topBar: @Composable (() -> Unit)? = null,
    floatingActionButton: @Composable (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .consumeWindowInsets(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
            ),
        contentWindowInsets = WindowInsets(0),
        topBar = { topBar?.invoke() },
        floatingActionButton = { floatingActionButton?.invoke() },
        content = content
    )
}
