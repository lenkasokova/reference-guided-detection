/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cz.vut.oneshotdetector.view.theme.LocalDimens

@Composable
fun OutlinedSelectableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val dimens = LocalDimens.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Card(
        modifier = modifier.clickable(interactionSource = interactionSource, indication = null) {
            onClick()
        },
        shape = RoundedCornerShape(dimens.cardCornerRadius),
        border = BorderStroke(
            dimens.borderWidth,
            if (isPressed) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.outline
        ),
        content = { content() }
    )
}
