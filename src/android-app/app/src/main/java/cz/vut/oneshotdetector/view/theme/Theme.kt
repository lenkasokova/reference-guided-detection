/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

private val DarkColorScheme = darkColorScheme(
    primary = Green300,
    onPrimary = Green800,
    secondary = Green200,
    onSecondary = Green800,
    secondaryContainer = DarkContainer,
    onSecondaryContainer = Green200,
    tertiary = DarkContainer,
    onTertiary = Green100,
    background = DarkSurface,
    onBackground = Green50,
    surface = DarkSurface,
    onSurface = Green50,
    surfaceVariant = DarkContainer,
    onSurfaceVariant = Green200,

    error = DarkError,
    onError = DarkOnError,
    outline = DarkOutline,
    outlineVariant = DarkContainer,
)

private val LightColorScheme = lightColorScheme(
    primary = Green600,               // main actions
    onPrimary = White,
    secondary = Green700,             // secondary actions — harmonised green
    onSecondary = White,
    secondaryContainer = Green100,    // tonal button background
    onSecondaryContainer = Green800,  // tonal button content
    tertiary = Green100,              // screen/card backgrounds — one step deeper than base
    onTertiary = Green800,            // titles and labels on green backgrounds
    background = Green50,             // base page background
    onBackground = NearBlack,
    surface = Green50,                // TopAppBar, bottom bar — seamless with background
    onSurface = NearBlack,
    surfaceVariant = Green50,         // muted surfaces — green-tinted, not blue-gray
    onSurfaceVariant = GreenGray,     // muted text — distinct from primary
    error = Red600,
    onError = White,
    outline = Green200,               // borders
    outlineVariant = Green100,        // subtle borders / switch track
)

val LocalSpacing = staticCompositionLocalOf { adaptiveSpacing(360) }
val LocalDimens = staticCompositionLocalOf { adaptiveDimens(360) }

@Composable
fun OneShotDetectorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val windowWidthDp = LocalConfiguration.current.screenWidthDp
    val spacing = remember(windowWidthDp) { adaptiveSpacing(windowWidthDp) }
    val dimens = remember(windowWidthDp) { adaptiveDimens(windowWidthDp) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.navigationBarColor = NavBarScrim.toArgb()
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
    ) {
        CompositionLocalProvider(
            LocalSpacing provides spacing,
            LocalDimens provides dimens,
            content = content
        )
    }
}
