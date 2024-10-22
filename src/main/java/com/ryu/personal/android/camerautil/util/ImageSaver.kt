package com.ryu.personal.android.camerautil.util

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import com.ryu.personal.android.camerautil.constant.CameraRatio
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min

class ImageSaver(
    private val context: Context,
    private val image: Image,
    private val deviceOrientation: Int,
    private val ratio: CameraRatio,
    private val viewSize: Size?,
    private val mirrorMode: Boolean = false
): Runnable {

    @SuppressLint("SimpleDateFormat")
    override fun run() {
        var byteArray: ByteArray = imageToByteArray(image)?.run {
            var bitmap = byteArrayToBitmap(this)
            bitmap = cropBitmap(bitmap, ratio, viewSize)
            bitmap = rotateByteArrayIfNeeded(bitmap, deviceOrientation)
            if (mirrorMode) {
                bitmap = mirrorMode(bitmap)
            }
            bitmapToByteArray(bitmap)
        } ?: return

        val contentResolver = context.contentResolver
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val displayName = "IMG_$timeStamp.jpg"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let {
                    val outputStream = contentResolver.openOutputStream(it)
                    outputStream?.use { stream ->
                        stream.write(byteArray)
                        stream.flush()
                    }
                }
            } else {
                val galleryDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "Camera"
                )
                if (!galleryDir.exists()) {
                    galleryDir.mkdirs()
                }

                val imageFile = File(galleryDir, "$displayName.jpg")
                val outputStream = FileOutputStream(imageFile)
                outputStream.use {
                    it.write(byteArray)
                    it.flush()
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }

    private fun imageToByteArray(image: Image): ByteArray? {
        var byteArray: ByteArray? = null
        val NV21toJPEG: (nv21: ByteArray, width: Int, height: Int) -> ByteArray = { nv21, width, height ->
            val out = ByteArrayOutputStream()
            val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            yuv.compressToJpeg(Rect(0, 0, width, height), 100, out)
            out.toByteArray()
        }
        val YUV_420_888toNV21: (image: Image) -> ByteArray = { image ->
            val yBuffer: ByteBuffer = image.planes[0].buffer
            val vuBuffer: ByteBuffer = image.planes[2].buffer

            val ySize: Int = yBuffer.remaining()
            val vuSize: Int = vuBuffer.remaining()

            val nv21: ByteArray = ByteArray(ySize + vuSize)
            yBuffer.get(nv21, 0, ySize)
            vuBuffer.get(nv21, ySize, vuSize)
            nv21
        }

        if (image.format == ImageFormat.JPEG) {
            val buffer: ByteBuffer = image.planes[0].buffer
            val byteArray = ByteArray(buffer.remaining())
            buffer.get(byteArray)
        } else {
            byteArray = NV21toJPEG(YUV_420_888toNV21(image), image.width, image.height)
        }
        return byteArray
    }

    private fun byteArrayToBitmap(byteArray: ByteArray): Bitmap {
        return BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
    }

    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
        return outputStream.toByteArray()
    }

    private fun rotateByteArrayIfNeeded(bitmap: Bitmap, orientation: Int): Bitmap {
        var rotatedBitmap = bitmap
        try {
            val matrix = Matrix()
            matrix.postRotate(orientation.toFloat())
            rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: IOException) {
            e.printStackTrace()
        }

        // 회전된 Bitmap을 다시 ByteArray로 변환하여 반환
        return rotatedBitmap
    }

    private fun cropBitmap(bitmap: Bitmap, ratio: CameraRatio, viewSize: Size?): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        var targetAspectX = 0
        var targetAspectY = 0

        when (ratio) {
            CameraRatio.RATIO_4_3 -> {
                if (width > height) {
                    targetAspectX = 4
                    targetAspectY = 3
                } else {
                    targetAspectX = 3
                    targetAspectY = 4
                }
            }
            CameraRatio.RATIO_16_9 -> {
                if (width > height) {
                    targetAspectX = 16
                    targetAspectY = 9
                } else {
                    targetAspectX = 9
                    targetAspectY = 16
                }
            }
            CameraRatio.RATIO_1_1 -> {
                targetAspectX = 1
                targetAspectY = 1
            }
            CameraRatio.RATIO_FULL -> {
                if (width > height) {
                    targetAspectX = max(viewSize?.width ?: 0, viewSize?.height ?: 0)
                    targetAspectY = min(viewSize?.width ?: 0, viewSize?.height ?: 0)
                } else {
                    targetAspectX = min(viewSize?.width ?: 0, viewSize?.height ?: 0)
                    targetAspectY = max(viewSize?.width ?: 0, viewSize?.height ?: 0)
                }
            }
        }

        if (targetAspectX == 0 || targetAspectY == 0) {
            return bitmap
        }


        val currentAspectRatio = width.toFloat() / height.toFloat()
        val targetAspectRatio = targetAspectX.toFloat() / targetAspectY.toFloat()

        var newWidth = width
        var newHeight = height

        if (currentAspectRatio > targetAspectRatio) {
            // 현재 비율이 타겟 비율보다 크면, 가로가 너무 길기 때문에 가로를 잘라야 함
            newWidth = (height * targetAspectRatio).toInt()
        } else {
            // 현재 비율이 타겟 비율보다 작으면, 세로가 너무 길기 때문에 세로를 잘라야 함
            newHeight = (width / targetAspectRatio).toInt()
        }

        // 중앙을 기준으로 이미지를 잘라냄
        val cropStartX = (width - newWidth) / 2
        val cropStartY = (height - newHeight) / 2

        return Bitmap.createBitmap(bitmap, cropStartX, cropStartY, newWidth, newHeight)
    }

    private fun mirrorMode(bitmap: Bitmap): Bitmap {
        val matrix = Matrix()
        matrix.preScale(-1.0f, 1.0f)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}