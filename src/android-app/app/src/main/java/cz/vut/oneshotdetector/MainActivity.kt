/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import cz.vut.oneshotdetector.navigation.AppNavGraph
import cz.vut.oneshotdetector.view.theme.OneShotDetectorTheme

/**
 * @author Bc. Lenka Šoková
 * Mobile application for plant detection and identification.
 *
 * The app allows users to take photos of plants, which are then processed
 * using a  model to identify the plant species. Users can also view detailed information ab
 * out the identified plants, including care instructions and similar species.
 *
 */

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OneShotDetectorTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .consumeWindowInsets(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                        )
                ) {
                    AppNavGraph()
                }
            }
        }
    }
}
