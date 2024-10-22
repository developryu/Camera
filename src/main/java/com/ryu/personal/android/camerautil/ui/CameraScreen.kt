package com.ryu.personal.android.camerautil.ui

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.util.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.ryu.personal.android.camerautil.constant.CameraAngle
import com.ryu.personal.android.camerautil.constant.CameraDirection
import com.ryu.personal.android.camerautil.constant.CameraFlash
import com.ryu.personal.android.camerautil.constant.CameraOrientation
import com.ryu.personal.android.camerautil.constant.CameraPreviewQuality
import com.ryu.personal.android.camerautil.constant.CameraRatio
import com.ryu.personal.android.camerautil.constant.CameraState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ryu.personal.android.camerautil.engine.Camera
import com.ryu.personal.android.camerautil.util.CameraScreenUtil

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun CameraScreen(
    cameraState: CameraState = CameraState.IDLE,
    cameraRatio: CameraRatio = CameraRatio.RATIO_4_3,
    cameraPreviewQuality: CameraPreviewQuality = CameraPreviewQuality.MEDIUM,
    cameraDirection: CameraDirection = CameraDirection.BACK,
    cameraAngle: CameraAngle = CameraAngle.NORMAL,
    cameraFlash: CameraFlash = CameraFlash.OFF,
    previewAlignment: Alignment = Alignment.TopCenter,
    onErrorFinish: () -> Unit = {},
    showGrid: Boolean = false,
    backgroundColor: Color = Color.Black,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val orientation by remember { mutableStateOf(if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) CameraOrientation.LANDSCAPE else CameraOrientation.PORTRAIT) }
    var previewSize by remember { mutableStateOf<Pair<Dp, Dp>?>(null) }

    var isFirstLaunch by remember { mutableStateOf(true) }
    var currentCameraState by remember { mutableStateOf(cameraState) }
    var currentCameraDirection by remember { mutableStateOf(cameraDirection) }
    var currentCameraAngle by remember { mutableStateOf(cameraAngle) }
    var currentCameraRatio by remember { mutableStateOf(cameraRatio) }
    var currentCameraFlash by remember { mutableStateOf(cameraFlash) }

    var isCameraLoading by remember { mutableStateOf(true) }
    val camera by remember { mutableStateOf(Camera(context = context, onLoadListener = {
        isCameraLoading = it
    }, processImage = {})) }

    LaunchedEffect(previewSize, cameraState, cameraDirection, cameraAngle, cameraRatio, cameraFlash) {
        camera.setRealTextureViewSize(
            Size(previewSize?.first?.value?.toInt() ?: 0, previewSize?.second?.value?.toInt() ?: 0)
        )
        if (!isFirstLaunch) {
            if (currentCameraState != cameraState) {
                when (cameraState) {
                    CameraState.PREVIEW -> camera.startCamera(
                        cameraDirection = cameraDirection,
                        cameraAngle = cameraAngle,
                        cameraRatio = cameraRatio,
                        cameraFlash = cameraFlash,
                    )
                    CameraState.CAPTURE -> camera.takePicture()
                    CameraState.RECORD -> camera.recordVideo()
                    CameraState.IDLE -> camera.stopCamera()
                }
                currentCameraState = cameraState
            }
            if (currentCameraDirection != cameraDirection) {    // 전후면 변경
                camera.reStartCamera(cameraDirection = cameraDirection)
                currentCameraDirection = cameraDirection
            }
            if (currentCameraAngle != cameraAngle) {    // 앵글 변경
                camera.reStartCamera(cameraAngle = cameraAngle)
                currentCameraAngle = cameraAngle
            }
            if (currentCameraRatio != cameraRatio) {    // 비율 변경
                camera.reStartCamera(cameraRatio = cameraRatio)
                currentCameraRatio = cameraRatio
            }
            if (currentCameraFlash != cameraFlash) {    // 플래시 변경
                camera.changeCameraFlash(cameraFlash)
                currentCameraFlash = cameraFlash
            }
        } else {
            isFirstLaunch = false
        }
    }

    DisposableEffect(Unit) {
        camera.startCamera(
            cameraDirection = cameraDirection,
            cameraAngle = cameraAngle,
            cameraRatio = cameraRatio,
            cameraFlash = cameraFlash
        )
        onDispose {
            camera.stopCamera()
        }
    }

    BoxWithConstraints(
        modifier = modifier.background(backgroundColor),
        contentAlignment = previewAlignment
    ) {
        val tmpPreviewSize = CameraScreenUtil.getCameraPreviewSize(maxWidth, maxHeight, cameraRatio, orientation)
        if (tmpPreviewSize != previewSize) {
            isCameraLoading = true
            previewSize = tmpPreviewSize
        }
        previewSize?.let { size ->
            AndroidView(
                modifier = Modifier.size(size.first, size.second),
                factory = {
                    camera.getTextureView()
                }
            )
            if (showGrid) {
                CameraGrid(size.first, size.second)
            }
            if (isCameraLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                )
            }
        }
    }
}