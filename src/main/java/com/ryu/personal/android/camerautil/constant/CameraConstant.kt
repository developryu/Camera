package com.ryu.personal.android.camerautil.constant

enum class CameraRatio { RATIO_1_1, RATIO_4_3, RATIO_16_9, RATIO_FULL }
enum class CameraDirection { FRONT, BACK, UNKNOWN }
enum class CameraAngle { WIDE, NORMAL, UNKNOWN }
enum class CameraFlash { AUTO, ON, OFF, TORCH , RED_EYE }
enum class CameraState { IDLE, PREVIEW, CAPTURE, RECORD }
enum class CameraQuality { LOW, MEDIUM, HIGH }
enum class CameraQualityType { PREVIEW, IMAGE, VIDEO }
enum class CameraEngineState {
    STATE_PREVIEW,
    STATE_WAITING_LOCK,
    STATE_WAITING_PRECAPTURE,
    STATE_WAITING_NON_PRECAPTURE,
    STATE_WATING_SAVE
}

enum class TakePictureState {
    IDLE,
    TAKING,
}
enum class TakeVideoState {
    IDLE,
    RECODING,
    RECODE_PAUSE,
}