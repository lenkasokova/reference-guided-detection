/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.components.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import cz.vut.oneshotdetector.view.theme.LocalSpacing
import java.io.File

@Composable
fun CameraPreviewCore(
    permissionMessage: String,
    fileNamePrefix: String,
    previewScaleType: PreviewView.ScaleType = PreviewView.ScaleType.FILL_CENTER,
    onCaptureSaved: (uri: Uri, fileName: String) -> Unit,
    onCaptureFailed: () -> Unit,
    content: @Composable (
        preview: @Composable () -> Unit,
        onCapture: () -> Unit,
        getCurrentFrameBitmap: () -> Bitmap?
    ) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val spacing = LocalSpacing.current

    fun checkPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    var hasPermission by remember { mutableStateOf(checkPermission()) }
    var askedOnce by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted || checkPermission()
        askedOnce = true
    }

    LaunchedEffect(Unit) {
        if (!hasPermission && !askedOnce) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasPermission) {
        val activity = context as? android.app.Activity
        val canAskAgain = activity?.let {
            androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                it,
                Manifest.permission.CAMERA
            )
        } ?: false
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(spacing.md),
            verticalArrangement = Arrangement.Center
        ) {
            Text(permissionMessage)
            Spacer(Modifier.height(spacing.sm))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant permission")
            }
            if (askedOnce && !canAskAgain) {
                Spacer(Modifier.height(spacing.sm))
                OutlinedButton(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:${context.packageName}".toUri()
                        )
                        context.startActivity(intent)
                    }
                ) {
                    Text("Open app settings")
                }
            }
        }
        return
    }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(95)
            .build()
    }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = previewScaleType
        }
    }

    DisposableEffect(lifecycleOwner, previewView, imageCapture) {
        val executor = ContextCompat.getMainExecutor(context)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var preview: Preview? = null
        val listener = Runnable {
            val cameraProvider = cameraProviderFuture.get()
            preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview!!,
                imageCapture
            )
        }
        cameraProviderFuture.addListener(listener, executor)

        onDispose {
            preview?.setSurfaceProvider(null)
            runCatching { cameraProviderFuture.get().unbindAll() }
        }
    }

    val captureAction = {
        val outputFile = File(
            context.cacheDir,
            "${fileNamePrefix}${System.currentTimeMillis()}.jpg"
        )
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onCaptureSaved(Uri.fromFile(outputFile), outputFile.name)
                }

                override fun onError(exception: ImageCaptureException) {
                    onCaptureFailed()
                }
            }
        )
    }

    val previewComposable: @Composable () -> Unit = {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { previewView }
        )
    }

    content(previewComposable, captureAction) { previewView.bitmap }
}
