/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable

@Composable
fun onTertiaryOutlinedTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onTertiary,
    unfocusedTextColor = MaterialTheme.colorScheme.onTertiary,
    focusedBorderColor = MaterialTheme.colorScheme.onTertiary,
    unfocusedBorderColor = MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.5f),
    focusedPlaceholderColor = MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.5f),
    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.5f),
    cursorColor = MaterialTheme.colorScheme.onTertiary,
    focusedContainerColor = MaterialTheme.colorScheme.background,
    unfocusedContainerColor = MaterialTheme.colorScheme.background,
)
