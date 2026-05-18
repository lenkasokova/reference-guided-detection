/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.layout

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cz.vut.oneshotdetector.view.theme.LocalDimens
import cz.vut.oneshotdetector.view.theme.LocalSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable (() -> Unit)? = null,
) {
    Column {
        TopAppBar(
            title = title,
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
            expandedHeight = LocalDimens.current.topBarHeight,
            windowInsets = WindowInsets.statusBars,
        )
        if (bottomContent != null) {
            val spacing = LocalSpacing.current
            Column(modifier = Modifier.offset(y = -spacing.xs)) {
                bottomContent()
            }
        }
    }
}
