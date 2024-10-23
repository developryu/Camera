package com.ryu.personal.android.camerautil.util

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.Image
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import com.ryu.personal.android.camerautil.constant.CameraRatio
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date

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
        var byteArray: ByteArray = image.toByteArray()?.run {
            var bitmap = this.toBitmap()
            viewSize?.let {
                bitmap = bitmap.crop(ratio, it)
            }
            bitmap = bitmap.rotate(deviceOrientation.toFloat())
            if (mirrorMode) {
                bitmap = bitmap.mirror()
            }
            bitmap.toByteArray()
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
}