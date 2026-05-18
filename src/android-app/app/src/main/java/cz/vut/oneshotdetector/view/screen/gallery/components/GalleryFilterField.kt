/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.gallery.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cz.vut.oneshotdetector.view.theme.LocalDimens
import cz.vut.oneshotdetector.view.theme.Spacing
import cz.vut.oneshotdetector.view.theme.onTertiaryOutlinedTextFieldColors

@Composable
fun GalleryFilterField(filterState: TextFieldState, spacing: Spacing) {
    val dimens = LocalDimens.current
    OutlinedTextField(
        state = filterState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.xs)
            .height(dimens.filterFieldHeight),
        placeholder = { Text("Filter by label", style = MaterialTheme.typography.bodySmall) },
        lineLimits = TextFieldLineLimits.SingleLine,
        textStyle = MaterialTheme.typography.bodySmall,
        contentPadding = PaddingValues(horizontal = spacing.sm, vertical = spacing.xxs),
        colors = onTertiaryOutlinedTextFieldColors()
    )
}
