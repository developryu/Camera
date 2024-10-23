package com.ryu.personal.android.camerautil.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Size
import com.ryu.personal.android.camerautil.constant.CameraRatio
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer


fun ByteArray.toBitmap(): Bitmap {
    return BitmapFactory.decodeByteArray(this, 0, this.size)
}

fun Bitmap.toByteArray(): ByteArray {
    val outputStream = ByteArrayOutputStream()
    this.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
    return outputStream.toByteArray()
}

fun Bitmap.rotate(degrees: Float): Bitmap {
    var rotatedBitmap = this
    try {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        rotatedBitmap = Bitmap.createBitmap(this, 0, 0, this.width, this.height, matrix, true)
    } catch (e: IOException) {
        e.printStackTrace()
    }
    return rotatedBitmap
}

fun Bitmap.crop(ratio: CameraRatio, viewSize: Size): Bitmap {
    val (targetAspectX, targetAspectY) = if (this.width > this.height) {
        when (ratio) {
            CameraRatio.RATIO_4_3 -> 4 to 3
            CameraRatio.RATIO_16_9 -> 16 to 9
            CameraRatio.RATIO_1_1 -> 1 to 1
            CameraRatio.RATIO_FULL -> if (viewSize.width > viewSize.height) viewSize.width to viewSize.height else viewSize.height to viewSize.width
        }
    } else {
        when (ratio) {
            CameraRatio.RATIO_4_3 -> 3 to 4
            CameraRatio.RATIO_16_9 -> 9 to 16
            CameraRatio.RATIO_1_1 -> 1 to 1
            CameraRatio.RATIO_FULL -> if (viewSize.width > viewSize.height) viewSize.height to viewSize.width else viewSize.width to viewSize.height
        }
    }
    if (targetAspectX == 0 || targetAspectY == 0) return this

    val currentAspectRatio = this.width.toFloat() / this.height.toFloat()
    val targetAspectRatio = targetAspectX.toFloat() / targetAspectY.toFloat()
    var newWidth = this.width
    var newHeight = this.height

    if (currentAspectRatio > targetAspectRatio) {
        newWidth = (this.height * targetAspectRatio).toInt()
    } else {
        newHeight = (this.width / targetAspectRatio).toInt()
    }
    val cropStartX = (this.width - newWidth) / 2
    val cropStartY = (this.height - newHeight) / 2
    return Bitmap.createBitmap(this, cropStartX, cropStartY, newWidth, newHeight)
}

fun Bitmap.mirror(): Bitmap {
    val matrix = Matrix()
    matrix.preScale(-1f, 1f)
    return Bitmap.createBitmap(this, 0, 0, this.width, this.height, matrix, true)
}

fun Image.toByteArray(): ByteArray? {
    return if (this.format == ImageFormat.JPEG) {
        val buffer: ByteBuffer = this.planes[0].buffer
        val byteArray = ByteArray(buffer.remaining())
        buffer.get(byteArray)
        byteArray
    } else if (this.format == ImageFormat.YUV_420_888) {
        nv21ToJpeg(yuv420888ToNv21(this), this.width, this.height)
    } else {
        null
    }
}

private fun yuv420888ToNv21(image: Image): ByteArray {
    val yBuffer: ByteBuffer = image.planes[0].buffer
    val vuBuffer: ByteBuffer = image.planes[2].buffer

    val ySize: Int = yBuffer.remaining()
    val vuSize: Int = vuBuffer.remaining()

    val nv21: ByteArray = ByteArray(ySize + vuSize)
    yBuffer.get(nv21, 0, ySize)
    vuBuffer.get(nv21, ySize, vuSize)
    return nv21
}

private fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    yuv.compressToJpeg(Rect(0, 0, width, height), 100, out)
    return out.toByteArray()
}
