package com.ryu.personal.android.camerautil.model

data class ImageProcessData(
    val nv21ImageData: ByteArray,
    val width: Int,
    val height: Int,
    val orientation: Int
)