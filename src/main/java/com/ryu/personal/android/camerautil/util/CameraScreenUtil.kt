package com.ryu.personal.android.camerautil.util

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ryu.personal.android.camerautil.constant.CameraOrientation
import com.ryu.personal.android.camerautil.constant.CameraRatio

object CameraScreenUtil {
    fun getCameraPreviewSize(parentWidth: Dp, parentHeight: Dp, ratio: CameraRatio, orientation: CameraOrientation): Pair<Dp, Dp>? {
        if (parentWidth == 0.dp || parentHeight == 0.dp) return null
        return when(ratio) {
            CameraRatio.RATIO_1_1 -> {
                if (parentWidth > parentHeight) parentHeight to parentHeight else parentWidth to parentWidth
            }
            CameraRatio.RATIO_4_3 -> {
                if (orientation == CameraOrientation.LANDSCAPE) {
                    if (parentWidth / 4 * 3 <= parentHeight) parentWidth to parentWidth / 4 * 3 else parentHeight / 3 * 4 to parentHeight
                } else {
                    if (parentWidth / 3 * 4 <= parentHeight) parentWidth to parentWidth / 3 * 4 else parentHeight / 4 * 3 to parentHeight
                }
            }
            CameraRatio.RATIO_16_9 -> {
                if (orientation == CameraOrientation.LANDSCAPE) {
                    if (parentWidth / 16 * 9 <= parentHeight) parentWidth to parentWidth / 16 * 9 else parentHeight / 9 * 16 to parentHeight
                } else {
                    if (parentWidth / 9 * 16 <= parentHeight) parentWidth to parentWidth / 9 * 16 else parentHeight / 16 * 9 to parentHeight
                }
            }
            CameraRatio.RATIO_FULL -> {
                parentWidth to parentHeight
            }
        }
    }
}