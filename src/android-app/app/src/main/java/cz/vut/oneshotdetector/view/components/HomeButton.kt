/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cz.vut.oneshotdetector.view.theme.LocalDimens
import cz.vut.oneshotdetector.view.theme.LocalSpacing

enum class HomeButtonStyle { Filled, Outlined }

@Composable
fun HomeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: HomeButtonStyle = HomeButtonStyle.Filled
) {
    val spacing = LocalSpacing.current
    val dimens = LocalDimens.current
    val shape = RoundedCornerShape(dimens.buttonCornerRadius)
    val buttonModifier = modifier
        .fillMaxWidth()
        .height(spacing.xxl)
        .padding(horizontal = spacing.md)

    when (style) {
        HomeButtonStyle.Filled -> Button(onClick = onClick, shape = shape, modifier = buttonModifier) {
            Text(text)
        }
        HomeButtonStyle.Outlined -> OutlinedButton(
            onClick = onClick,
            shape = shape,
            modifier = buttonModifier,
            border = BorderStroke(
                ButtonDefaults.outlinedButtonBorder(enabled = true).width,
                MaterialTheme.colorScheme.primary
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent
            )
        ) {
            Text(text, color = MaterialTheme.colorScheme.primary)
        }
    }
}
