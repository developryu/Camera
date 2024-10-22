package com.ryu.personal.android.camerautil.model

import android.util.Size
import com.ryu.personal.android.camerautil.constant.CameraAngle
import com.ryu.personal.android.camerautil.constant.CameraDirection

data class CameraInfo(
    val id: String,
    val direction: CameraDirection,
    val angle: CameraAngle,
    val supportOutputSizes: List<Size> = emptyList(),
    val supportFocalLength: List<Float> = emptyList(),
    val supportFlash: Boolean = false,
    val supportWideAngle: Boolean = false,
)