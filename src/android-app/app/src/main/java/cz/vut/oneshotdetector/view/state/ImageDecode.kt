/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.state

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.roundToInt

fun decodeImageWithExif(context: Context, uri: Uri, inSampleSize: Int = 1): Bitmap? {
    if (uri.scheme == "asset") {
        return decodeImageWithExif(context, uri.toString().removePrefix("asset://"))
    }
    val resolver = context.contentResolver
    val orientation = resolver.openInputStream(uri)?.use { stream ->
        ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    } ?: ExifInterface.ORIENTATION_NORMAL

    val bitmap = resolver.openInputStream(uri)?.use { stream ->
        val options = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        BitmapFactory.decodeStream(stream, null, options)
    } ?: return null

    return applyExifOrientation(bitmap, orientation)
}

fun decodeImageWithExif(context: Context, assetPath: String): Bitmap? {
    val assets = context.assets
    val orientation = runCatching {
        assets.open(assetPath).use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val bitmap = assets.open(assetPath).use { stream ->
        BitmapFactory.decodeStream(stream)
    } ?: return null

    return applyExifOrientation(bitmap, orientation)
}

fun cropImageToFile(
    context: Context,
    uri: Uri,
    normLeft: Float,
    normTop: Float,
    normRight: Float,
    normBottom: Float,
    fileNamePrefix: String = "crop_"
): File? {
    val original = decodeImageWithExif(context, uri) ?: return null
    val l = (normLeft * original.width).roundToInt().coerceIn(0, original.width)
    val t = (normTop * original.height).roundToInt().coerceIn(0, original.height)
    val r = (normRight * original.width).roundToInt().coerceIn(0, original.width)
    val b = (normBottom * original.height).roundToInt().coerceIn(0, original.height)
    val cropped = Bitmap.createBitmap(original, l, t, (r - l).coerceAtLeast(1), (b - t).coerceAtLeast(1))
    original.recycle()
    val f = File(context.cacheDir, "${fileNamePrefix}${System.currentTimeMillis()}.jpg")
    f.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    cropped.recycle()
    return f
}

fun decodeImageForDisplay(context: Context, uri: Uri, maxDimension: Int = 2048): Bitmap? {
    val resolver = context.contentResolver
    val sizeOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, sizeOpts) }
    val larger = maxOf(sizeOpts.outWidth, sizeOpts.outHeight)
    val sample = if (larger > maxDimension) larger / maxDimension else 1
    return decodeImageWithExif(context, uri, inSampleSize = sample)
}

fun saveBitmapToFile(context: Context, bitmap: Bitmap, fileNamePrefix: String = "frame_"): java.io.File {
    val f = java.io.File(context.cacheDir, "${fileNamePrefix}${System.currentTimeMillis()}.png")
    f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 0, it) }
    return f
}

private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            matrix.setScale(1f, -1f)
            matrix.postRotate(180f)
        }
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
