/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import cz.vut.oneshotdetector.view.theme.LocalDimens
import cz.vut.oneshotdetector.view.theme.LocalSpacing

@Composable
fun OutlinedBox(
    modifier: Modifier = Modifier,
    innerPadding: Dp = LocalSpacing.current.md,
    content: @Composable BoxScope.() -> Unit,
) {
    val dimens = LocalDimens.current
    val shape = RoundedCornerShape(dimens.cardCornerRadius)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .background(color = MaterialTheme.colorScheme.background, shape = shape)
            .border(width = dimens.borderWidth, color = MaterialTheme.colorScheme.outline, shape = shape)
            .padding(innerPadding),
        content = content
    )
}
