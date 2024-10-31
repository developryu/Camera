package com.ryu.personal.android.camerautil.model

import android.util.Size
import com.ryu.personal.android.camerautil.constant.CameraRatio

data class ImageSaveData(
    val imageData: ByteArray,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val ratio: CameraRatio,
    val viewSize: Size? = null,
    val mirrorMode: Boolean = false
)