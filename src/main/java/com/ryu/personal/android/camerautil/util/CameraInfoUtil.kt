package com.ryu.personal.android.camerautil.util

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import com.ryu.personal.android.camerautil.constant.CameraAngle
import com.ryu.personal.android.camerautil.constant.CameraDirection
import com.ryu.personal.android.camerautil.constant.CameraRatio
import com.ryu.personal.android.camerautil.model.CameraInfo
import kotlin.math.abs

class CameraInfoUtil(private val context: Context) {

    private var cameraInfoList: List<CameraInfo> = emptyList()

    fun getCameraInfo(direction: CameraDirection, angle: CameraAngle): CameraInfo? {
        if (cameraInfoList.isEmpty()) getCameraInfoList()
        return cameraInfoList.firstOrNull {
            it.direction == direction && it.angle == angle
        } ?: cameraInfoList.firstOrNull {
            it.direction == direction
        }
    }

    fun getCameraInfoList(): List<CameraInfo> {
        if (cameraInfoList.isEmpty()) {
            cameraInfoList = createCameraInfoList()
        }
        return cameraInfoList
    }

    fun getSupportOutputSizesFromRatio(
        supportOutputSizes: List<Size>,
        cameraRatio: CameraRatio,
        viewSize: Size
    ): List<Size> {
        val getData: (CameraRatio) -> List<Size> = { ratio ->
            val aspectRatioValue = when (ratio) {
                CameraRatio.RATIO_1_1 -> 1f/ 1f
                CameraRatio.RATIO_4_3 -> 4f / 3f
                CameraRatio.RATIO_16_9 -> 16f / 9f
                CameraRatio.RATIO_FULL -> viewSize.width.toFloat() / viewSize.height.toFloat()
            }
            supportOutputSizes.filter { outputSize ->
                val ratio = outputSize.width.toFloat() / outputSize.height.toFloat()
                abs(ratio - aspectRatioValue) < 0.01
            }
        }
        var supportOutputSizesFromRatio = getData(cameraRatio)
        if (supportOutputSizesFromRatio.isEmpty()) {
            supportOutputSizesFromRatio = getData(CameraRatio.RATIO_4_3)
        }
        return supportOutputSizesFromRatio
    }

    private fun createCameraInfoList(): List<CameraInfo> {
        var returnCameraInfoList = mutableListOf<CameraInfo>()
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraManager.cameraIdList.forEach { cameraId ->
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)

                // CameraDirection
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val direction = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                    CameraDirection.FRONT
                } else {
                    CameraDirection.BACK
                }

                // CameraAngle
                val angle = CameraAngle.NORMAL

                // SupportOutputSizes
                val configurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val supportOutputSizes = mutableListOf<Size>().apply {
                    addAll(configurationMap?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray())
                }

                // SupportFocalLength
                val supportFocalLength = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()

                // SupportFlash
                val supportFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

                // SupportWideAngle
                val supportWideAngle = false

                if (supportOutputSizes.isNotEmpty() && supportFocalLength.isNotEmpty()) {
                    returnCameraInfoList.add(
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
            }

            // angle, supportFlash, supportWideAngle 재정립
            returnCameraInfoList = returnCameraInfoList.groupBy { it.direction }.flatMap { (_, sameDirectionList) ->
                if (sameDirectionList.size > 1) {
                    val supportFlash = sameDirectionList.any { it.supportFlash }
                    val supportWideAngle = true
                    sameDirectionList.sortedWith(compareBy({ it.supportFocalLength.minOrNull() }, { it.id })).mapIndexed { index, cameraInfo ->
                        cameraInfo.copy(
                            angle = if (index == 0) CameraAngle.WIDE else CameraAngle.NORMAL,
                            supportFlash = supportFlash,
                            supportWideAngle = supportWideAngle
                        )
                    }
                } else { sameDirectionList }
            }.toMutableList()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return returnCameraInfoList.sortedBy { it.id }.toList()
    }
}