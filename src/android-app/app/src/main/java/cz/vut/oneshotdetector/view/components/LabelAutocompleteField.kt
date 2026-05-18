/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.components

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelAutocompleteField(
    value: String,
    onValueChange: (String) -> Unit,
    existingLabels: List<String>,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    val filtered = remember(value, existingLabels) {
        existingLabels
            .filter { it.contains(value.trim(), ignoreCase = true) && !it.equals(value.trim(), ignoreCase = true) }
            .distinct()
    }
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded && filtered.isNotEmpty(),
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text("Label") },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            singleLine = true,
            colors = colors,
            trailingIcon = {
                if (filtered.isNotEmpty()) {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            }
        )
        ExposedDropdownMenu(
            expanded = expanded && filtered.isNotEmpty(),
            onDismissRequest = { expanded = false }
        ) {
            filtered.forEach { label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onValueChange(label)
                        expanded = false
                    }
                )
            }
        }
    }
}
