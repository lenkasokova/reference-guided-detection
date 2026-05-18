/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.core.net.toUri
import cz.vut.oneshotdetector.view.screen.actions.CropPhotoScreen
import cz.vut.oneshotdetector.view.screen.actions.DetailFlow
import cz.vut.oneshotdetector.view.screen.actions.EditFlow
import cz.vut.oneshotdetector.view.screen.actions.PhotoActionsFlow
import cz.vut.oneshotdetector.view.screen.camera.CameraFlow
import cz.vut.oneshotdetector.view.screen.detect.DetectFlow
import cz.vut.oneshotdetector.view.screen.gallery.GalleryFlow
import cz.vut.oneshotdetector.view.screen.home.HomeScreen
import cz.vut.oneshotdetector.view.screen.settings.SettingsScreen

@Composable
fun AppNavGraph() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = StackRoutes.HOME) {
        composable(StackRoutes.HOME) {
            HomeScreen(
                onNavigateToGallery = { navController.navigate(StackRoutes.GALLERY) },
                onNavigateToDetect = { navController.navigate(StackRoutes.DETECT) },
                onNavigateToSettings = { navController.navigate(StackRoutes.SETTINGS) }
            )
        }
        composable(StackRoutes.CAMERA) {
            CameraFlow(
                onBack = { navController.popBackStack() },
                onPhotoCaptured = { uri -> navController.navigate(StackRoutes.photoActions(uri)) }
            )
        }
        composable(StackRoutes.GALLERY) {
            GalleryFlow(
                onAddImage = { navController.navigate(StackRoutes.CAMERA) },
                onOpenImage = { uri -> navController.navigate(StackRoutes.detail(uri)) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(StackRoutes.DETECT) {
            DetectFlow(
                onBack = { navController.popBackStack() },
                onOpenImage = { uri -> navController.navigate(StackRoutes.detail(uri)) },
                onAddDetectedImage = { uri -> navController.navigate(StackRoutes.photoActions(uri)) }
            )
        }
        composable(StackRoutes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = StackRoutes.PHOTO_ACTIONS,
            arguments = listOf(navArgument("imageUri") {
                type = NavType.StringType
                nullable = false
            })
        ) { backStackEntry ->
            val imageUri = Uri.decode(backStackEntry.arguments?.getString("imageUri") ?: "")
            PhotoActionsFlow(
                imageUri = imageUri,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }
        composable(
            route = StackRoutes.DETAIL,
            arguments = listOf(navArgument("plantId") { type = NavType.StringType })
        ) { backStackEntry ->
            val plantId = Uri.decode(backStackEntry.arguments?.getString("plantId") ?: "")
            DetailFlow(
                plantId = plantId,
                onEdit = { navController.navigate(StackRoutes.edit(plantId)) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = StackRoutes.EDIT,
            arguments = listOf(navArgument("plantId") { type = NavType.StringType })
        ) { backStackEntry ->
            val plantId = Uri.decode(backStackEntry.arguments?.getString("plantId") ?: "")
            val pendingCropRect by backStackEntry.savedStateHandle
                .getStateFlow<FloatArray?>("pendingCropRect", null)
                .collectAsState()
            EditFlow(
                plantId = plantId,
                pendingCropRect = pendingCropRect,
                onPendingCropRectConsumed = {
                    backStackEntry.savedStateHandle.remove<FloatArray>("pendingCropRect")
                },
                onCropPhoto = { navController.navigate(StackRoutes.crop(plantId)) },
                onSaved = { newPlantId ->
                    navController.navigate(StackRoutes.detail(newPlantId)) {
                        popUpTo(StackRoutes.DETAIL) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = StackRoutes.CROP,
            arguments = listOf(navArgument("plantId") { type = NavType.StringType })
        ) { backStackEntry ->
            val plantId = Uri.decode(backStackEntry.arguments?.getString("plantId") ?: "")
            CropPhotoScreen(
                photoUri = plantId.toUri(),
                onCrop = { normLeft, normTop, normRight, normBottom ->
                    navController.previousBackStackEntry?.savedStateHandle?.set(
                        "pendingCropRect", floatArrayOf(normLeft, normTop, normRight, normBottom)
                    )
                    navController.popBackStack()
                },
                onCancel = { navController.popBackStack() }
            )
        }
    }
}
