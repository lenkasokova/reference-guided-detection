/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cz.vut.oneshotdetector.view.components.HomeButton
import cz.vut.oneshotdetector.view.components.HomeButtonStyle
import cz.vut.oneshotdetector.view.layout.AppScreen
import cz.vut.oneshotdetector.view.layout.AppTopBar
import cz.vut.oneshotdetector.view.theme.LocalSpacing

@Composable
fun HomeScreen(
    onNavigateToGallery: () -> Unit,
    onNavigateToDetect: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val spacing = LocalSpacing.current

    AppScreen(
        topBar = {
            AppTopBar(
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onTertiary
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HomeButton(text = "Detect", onClick = onNavigateToDetect)
            Spacer(Modifier.height(spacing.sm))
            HomeButton(text = "My Plants", onClick = onNavigateToGallery, style = HomeButtonStyle.Outlined)
        }
    }
}
