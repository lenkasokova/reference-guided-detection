/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.detect

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cz.vut.oneshotdetector.viewmodel.detect.DetectViewModel
import kotlin.math.roundToLong

/**
 * Central configuration for DetectScreen: layout weights, sizes, colours, string constants
 */
internal object DetectConfig {

    // ── Debug ────────────────────────────────────────────────────────────────────────────────

    /** When true, shows a small performance overlay on the live preview (Loop / Rate / Proc). */
    const val DEBUG = true

    // ── Layout ───────────────────────────────────────────────────────────────────────────────

    /** Weight of the preview (camera / image) section relative to the full screen height. */
    const val PREVIEW_WEIGHT = 3f

    /** Weight of the results panel at the bottom of the screen. */
    const val PANEL_WEIGHT = 1f

    /** Horizontal padding inside the preview area (0 = edge-to-edge). */
    val previewHorizontalPadding = 0.dp

    // ── Camera ───────────────────────────────────────────────────────────────────────────────

    /** Minimum retry delay when no live frame is currently available from the preview. */
    const val LIVE_FRAME_NO_FRAME_RETRY_MS = 60L

    /** Lower bound for adaptive live-frame delays. */
    const val LIVE_FRAME_MIN_INTERVAL_MS = 40L

    /** Upper bound for adaptive live-frame delays. */
    const val LIVE_FRAME_MAX_INTERVAL_MS = 900L

    /** Exponential smoothing factor for recent live-frame processing time. */
    const val LIVE_FRAME_SMOOTHING_ALPHA = 0.25f

    /** File-name prefix used when saving a captured image to disk. */
    const val CAMERA_FILE_PREFIX = "detect_"

    /** Message shown to the user when camera permission has not been granted. */
    const val CAMERA_PERMISSION_MSG = "Camera permission is required for detection."

    // ── Crop ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Half-size of the fixed bounding box used in Crop mode, expressed as a fraction of the
     * corresponding image dimension.  E.g. 0.15 means the crop extends 15 % of the image width
     * to each side of the tapped point.
     */
    const val CROP_HALF_FRACTION = 0.15f

    // ── Colours ──────────────────────────────────────────────────────────────────────────────

    /** Foreground / icon colour on the black camera background. */
    val onBackground = Color.White

    /** Full-screen background colour. */
    val background = Color.Black

    /** Semi-transparent scrim behind the mode-selector popup. */
    val overlayScrim = Color.Black.copy(alpha = 0.5f)

    /** Background of the mode-selector card. */
    val modeSelectorBg = Color.Black.copy(alpha = 0.85f)

    /** Alpha applied to the divider line between preview and results. */
    const val DIVIDER_ALPHA = 0.15f

    /** Alpha applied to the status-text label in the results panel. */
    const val STATUS_TEXT_ALPHA = 0.7f

    /** Alpha for the subtle tinted background of result panel cards. */
    const val RESULT_CARD_TINT_ALPHA = 0.10f

    /** Alpha for the frosted-glass container behind the action (capture / refresh) button. */
    const val ACTION_BTN_CONTAINER_ALPHA = 0.15f

    /** Alpha for the mode-selector button border. */
    const val MODE_BORDER_ALPHA = 0.5f

    // ── Sizes ─────────────────────────────────────────────────────────────────────────────────

    /** Touch-target size of the floating action button (capture / refresh). */
    val actionButtonSize = 56.dp

    /** Icon size inside the floating action button. */
    val actionIconSize = 28.dp

    /** Corner radius of the mode-selector popup card. */
    val modeSelectorCornerRadius = 16.dp

    fun adaptiveLiveFrameDelayMs(
        detectMode: DetectViewModel.DetectMode,
        compareMode: DetectViewModel.CompareMode,
        smoothedProcessingMs: Double
    ): Long {
        val minCycleMs = when {
            detectMode == DetectViewModel.DetectMode.Segment -> 240.0
            detectMode == DetectViewModel.DetectMode.WholeImage &&
                compareMode == DetectViewModel.CompareMode.WholeGallery -> 280.0
            detectMode == DetectViewModel.DetectMode.Roi -> 220.0
            else -> 140.0
        }

        val desiredCycleMs = maxOf(minCycleMs, smoothedProcessingMs * 1.35)
        val idleDelayMs = (desiredCycleMs - smoothedProcessingMs)
            .roundToLong()
            .coerceIn(LIVE_FRAME_MIN_INTERVAL_MS, LIVE_FRAME_MAX_INTERVAL_MS)

        return idleDelayMs
    }
}
