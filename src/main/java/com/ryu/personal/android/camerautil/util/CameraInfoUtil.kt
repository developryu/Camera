package com.ryu.personal.android.camerautil.util

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.util.Size
import com.ryu.personal.android.camerautil.constant.CameraAngle
import com.ryu.personal.android.camerautil.constant.CameraDirection
import com.ryu.personal.android.camerautil.constant.CameraRatio
import com.ryu.personal.android.camerautil.model.CameraInfo
import com.ryu.personal.android.camerautil.model.OutputSizes
import kotlin.math.abs

class CameraInfoUtil(
    private val context: Context
) {
    private var cameraInfos: List<CameraInfo> = emptyList()

    fun getCameraInfo(
        direction: CameraDirection,
        angle: CameraAngle
    ): Result<CameraInfo> = runCatching {
        if (cameraInfos.isEmpty()) cameraInfos = createCameraInfos()
        cameraInfos.firstOrNull {
            it.direction == direction && it.angle == angle
        } ?: cameraInfos.firstOrNull {
            it.direction == direction
        } ?: cameraInfos.first()
    }

    fun getCameraInfos(): List<CameraInfo> {
        if (cameraInfos.isEmpty()) cameraInfos = createCameraInfos()
        return cameraInfos
    }

    fun getSupportOutputSizesFromRatio(outputSizes: List<Size>, ratio: CameraRatio, viewSize: Size) : List<Size> {
        val getList: (CameraRatio) -> List<Size> = {
            outputSizes.filter { size ->
                val aspectRatio = when (it) {
                    CameraRatio.RATIO_1_1 -> 1f / 1f
                    CameraRatio.RATIO_4_3 -> 4f / 3f
                    CameraRatio.RATIO_16_9 -> 16f / 9f
                    CameraRatio.RATIO_FULL -> getRatioFromSize(viewSize.width.toFloat(), viewSize.height.toFloat())
                }
                abs((getRatioFromSize(size.width.toFloat(), size.height.toFloat())) - aspectRatio) <= 0.01
            }
        }
        var list = getList(ratio)
        if (list.isEmpty()) {
            list = getList(CameraRatio.RATIO_4_3)
        }
        if (list.isEmpty()) {
            list = outputSizes
        }
        return list.sortedByDescending { it.width * it.height }
    }

    fun getRatioFromSize(value1: Float, value2: Float): Float {
        val (larger, smaller) = if (value1 > value2) value1 to value2 else value2 to value1
        return larger / smaller
    }

    private fun createCameraInfos(): List<CameraInfo> {
        var list = mutableListOf<CameraInfo>()
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)

                // CameraDirection
                val direction = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> CameraDirection.FRONT
                    CameraCharacteristics.LENS_FACING_BACK -> CameraDirection.BACK
                    else -> CameraDirection.UNKNOWN
                }

                // CameraAngle
                val angle = CameraAngle.UNKNOWN

                // SupportOutputSizes
                val configurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val previewSizes = configurationMap?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
                val pictureSizes = configurationMap?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
                val videoSizes = configurationMap?.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()
                val supportOutputSizes = OutputSizes(
                    preview = if (previewSizes.isNotEmpty()) previewSizes else pictureSizes,
                    picture = pictureSizes,
                    video = if (videoSizes.isNotEmpty()) videoSizes else pictureSizes
                )

                // SupportFocalLength
                val supportFocalLength = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()

                // SupportFlash
                val supportFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

                // SupportWideAngle
                val supportWideAngle = false

                if (direction == CameraDirection.UNKNOWN || pictureSizes.isEmpty() || supportFocalLength.isEmpty()) continue
                list.add(
                    CameraInfo(
                        id = cameraId,
                        direction = direction,
                        angle = angle,
                        supportOutputSizes = supportOutputSizes,
                        supportFocalLength = supportFocalLength,
                        supportFlash = supportFlash,
                        supportWideAngle = supportWideAngle
                    )
                )
            }

            list = list.groupBy { info -> info.direction }.flatMap { (_, sameDirectionList) ->
                if (sameDirectionList.size > 1) {
                    sameDirectionList.sortedWith(compareBy({ it.supportFocalLength.minOrNull() }, { it.id })).mapIndexed { index, cameraInfo ->
                        cameraInfo.copy(
                            angle = when (index) {
                                0 -> CameraAngle.WIDE
                                1 -> CameraAngle.NORMAL
                                else -> CameraAngle.UNKNOWN
                            },
                            supportFlash = sameDirectionList.any { it.supportFlash },
                            supportWideAngle = true
                        )
                    }
                } else {
                    sameDirectionList
                }
            }.toMutableList()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list.sortedBy { info -> info.id }.toList()
    }
}