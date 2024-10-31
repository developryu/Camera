package com.ryu.personal.android.camerautil.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.util.Size
import com.ryu.personal.android.camerautil.constant.CameraRatio
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

fun Image.toByteArray(): ByteArray? {
    return if (this.format == ImageFormat.JPEG) {
        val buffer: ByteBuffer = this.planes[0].buffer
        val byteArray = ByteArray(buffer.remaining())
        buffer.get(byteArray)
        byteArray
    } else if (this.format == ImageFormat.YUV_420_888) {
        yuvToNv21ByteArray(this)
    } else {
        null
    }
}

private fun yuvToNv21ByteArray(image: Image): ByteArray {
    val planes = image.planes
    val buffer0 = planes[0].buffer
    val buffer1 = planes[1].buffer
    val buffer2 = planes[2].buffer

    val offset = 0

    val width = image.width
    val height = image.height

    val data = ByteArray(width * height * ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8)
    val rowData1 = ByteArray(planes[1].rowStride)
    val rowData2 = ByteArray(planes[2].rowStride)

    val bytesPerPixel = ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8

    var offsetY = 0

    val sizeY = width * height * bytesPerPixel
    val sizeUV = (width * height * bytesPerPixel) / 4

    for (row in 0 until height) {
        val length = bytesPerPixel * width
        buffer0.get(data, offsetY, length)
        if (height - row != 1) {
            buffer0.position(buffer0.position() + planes[0].rowStride - length)
        }
        offsetY += length

        if (row >= height / 2) continue

        var uvLength = planes[1].rowStride
        if ((height / 2 - row) == 1) {
            uvLength = width / 2 - planes[1].pixelStride + 1
        }
        buffer1.get(rowData1, 0, uvLength)
        buffer2.get(rowData2, 0, uvLength)

        for (col in 0 until width / 2) {
            data[sizeY + (row * width)/2 + col] = rowData1[col * planes[1].pixelStride]
            data[sizeY + sizeUV + (row * width)/2 + col] = rowData2[col * planes[2].pixelStride]
        }
    }
    return data
}

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

fun Bitmap.crop(ratio: CameraRatio, viewSize: Size?): Bitmap {
    if (ratio == CameraRatio.RATIO_FULL && viewSize == null) return this
    val (targetAspectX, targetAspectY) = if (this.width > this.height) {
        when (ratio) {
            CameraRatio.RATIO_4_3 -> 4 to 3
            CameraRatio.RATIO_16_9 -> 16 to 9
            CameraRatio.RATIO_1_1 -> 1 to 1
            CameraRatio.RATIO_FULL -> if (viewSize!!.width > viewSize.height) viewSize.width to viewSize.height else viewSize.height to viewSize.width
        }
    } else {
        when (ratio) {
            CameraRatio.RATIO_4_3 -> 3 to 4
            CameraRatio.RATIO_16_9 -> 9 to 16
            CameraRatio.RATIO_1_1 -> 1 to 1
            CameraRatio.RATIO_FULL -> if (viewSize!!.width > viewSize.height) viewSize.height to viewSize.width else viewSize.width to viewSize.height
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