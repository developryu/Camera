package com.ryu.personal.android.camerautil.model

import android.util.Size
import com.ryu.personal.android.camerautil.constant.CameraAngle
import com.ryu.personal.android.camerautil.constant.CameraDirection

data class CameraInfo(
    val id: String,
    val direction: CameraDirection,
    val angle: CameraAngle,
    val supportOutputSizes: OutputSizes,
    val supportFocalLength: List<Float>,
    val supportFlash: Boolean,
    val supportWideAngle: Boolean
)

data class OutputSizes(
    val preview: List<Size>,
    val picture: List<Size>,
    val video: List<Size>
)