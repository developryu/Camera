package com.ryu.personal.android.camerautil.constant

import android.util.Size
import android.util.SparseIntArray
import android.view.Surface

// 카메라 옵션
enum class CameraRatio { RATIO_1_1, RATIO_4_3, RATIO_16_9, RATIO_FULL }
enum class CameraDirection { FRONT, BACK }
enum class CameraAngle { WIDE, NORMAL }
enum class CameraFlash { AUTO, ON, OFF, TORCH }
enum class CameraState { IDLE, PREVIEW, CAPTURE, RECORD }
enum class CameraPreviewQuality { LOW, MEDIUM, HIGH }
enum class CameraCaptureQuality { LOW, MEDIUM, HIGH }
enum class CameraOrientation { PORTRAIT, LANDSCAPE }

// 카메라 상태
enum class CameraEngineState {
    STATE_PREVIEW,
    STATE_WAITING_LOCK,
    STATE_WAITING_PRECAPTURE,
    STATE_WAITING_NON_PRECAPTURE,
    STATE_PICTURE_TAKEN
}

// 프리뷰 최대 사이즈
val MAX_PREVIEW_SIZE = Size(1920, 1080)

// 회전 상태
val ORIENTATIONS = SparseIntArray().apply {
    append(Surface.ROTATION_0, 90)
    append(Surface.ROTATION_90, 0)
    append(Surface.ROTATION_180, 270)
    append(Surface.ROTATION_270, 180)
}