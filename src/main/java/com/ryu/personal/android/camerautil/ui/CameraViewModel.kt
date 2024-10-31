package com.ryu.personal.android.camerautil.ui

import android.content.Context
import android.content.res.Configuration
import android.view.TextureView
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ryu.personal.android.camerautil.constant.CameraAngle
import com.ryu.personal.android.camerautil.constant.CameraDirection
import com.ryu.personal.android.camerautil.constant.CameraFlash
import com.ryu.personal.android.camerautil.constant.CameraQuality
import com.ryu.personal.android.camerautil.constant.CameraRatio
import com.ryu.personal.android.camerautil.constant.TakePictureState
import com.ryu.personal.android.camerautil.engine.Camera
import com.ryu.personal.android.camerautil.model.ImageProcessData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

class CameraViewModel(
    context: Context,
    cameraDirection: CameraDirection = CameraDirection.BACK,
    cameraAngle: CameraAngle = CameraAngle.NORMAL,
    cameraRatio: CameraRatio = CameraRatio.RATIO_4_3,
    cameraFlash: CameraFlash = CameraFlash.OFF,
    imageQuality: CameraQuality = CameraQuality.HIGH,
    videoQuality: CameraQuality = CameraQuality.HIGH,
    onProcessImage: ((ImageProcessData) -> Unit)? = null
) : ViewModel() {

    private val camera = Camera(
        context = context,
        cameraDirection = cameraDirection,
        cameraAngle = cameraAngle,
        cameraRatio = cameraRatio,
        cameraFlash = cameraFlash,
        imageQuality = imageQuality,
        videoQuality = videoQuality,
        onShowPreview = { onShowPreview.value = it },
        onProcessImage = onProcessImage,
    )

    val textureView: MutableState<TextureView> = mutableStateOf(camera.getView())
    var onShowPreview: MutableState<Boolean> = mutableStateOf(false)
        private set

    private val _cameraDirection = MutableStateFlow(cameraDirection)
    private val _cameraAngle = MutableStateFlow(cameraAngle)
    private val _cameraRatio: MutableStateFlow<CameraRatio> = MutableStateFlow(cameraRatio)
    private val _cameraFlash = MutableStateFlow(cameraFlash)
    private val _imageQuality = MutableStateFlow(imageQuality)
    private val _videoQuality = MutableStateFlow(videoQuality)
    private val _takePictureState = MutableStateFlow(TakePictureState.IDLE)
    private val _takeVideoState = MutableStateFlow(TakePictureState.IDLE)

    val cameraDirection: StateFlow<CameraDirection> = _cameraDirection
    val cameraAngle: StateFlow<CameraAngle> = _cameraAngle
    val cameraRatio: StateFlow<CameraRatio> = _cameraRatio
    val cameraFlash: StateFlow<CameraFlash> = _cameraFlash
    val imageQuality: StateFlow<CameraQuality> = _imageQuality
    val videoQuality: StateFlow<CameraQuality> = _videoQuality
    val takePictureState: StateFlow<TakePictureState> = _takePictureState
    val takeVideoState: StateFlow<TakePictureState> = _takeVideoState


    fun startPreview() {
        onShowPreview.value = false
        camera.startPreview()
    }

    fun stopPreview() {
        onShowPreview.value = false
        camera.stopPreview()
    }

    fun swapDirection() {
        _cameraDirection.value = when (_cameraDirection.value) {
            CameraDirection.BACK -> CameraDirection.FRONT
            CameraDirection.FRONT -> CameraDirection.BACK
            CameraDirection.UNKNOWN -> CameraDirection.UNKNOWN
        }
        camera.setCameraDirection(_cameraDirection.value)
    }

    fun swapAngle() {
        _cameraAngle.value = when (_cameraAngle.value) {
            CameraAngle.NORMAL -> CameraAngle.WIDE
            CameraAngle.WIDE -> CameraAngle.NORMAL
            CameraAngle.UNKNOWN -> CameraAngle.UNKNOWN
        }
        camera.setCameraAngle(_cameraAngle.value)
    }

    fun changeRatio(ratio: CameraRatio) {
        if (_cameraRatio.value == ratio) return
        _cameraRatio.value = ratio
        camera.setCameraRatio(_cameraRatio.value)
    }

    fun finishTextureViewSizeChanged() {
        camera.changePreviewSession()
    }

    fun changeFlash(flash: CameraFlash) {
        if (_cameraFlash.value == flash) return
        _cameraFlash.value = flash
        camera.setCameraFlash(_cameraFlash.value)
    }

    fun takePicture() {
//        if (_takePictureState.value != TakePictureState.IDLE) return
//        _takePictureState.value = TakePictureState.TAKING
        camera.takePicture()
    }

    fun takeVideo() {

    }

    fun pauseVideo() {

    }

    fun resumeVideo() {

    }

    fun stopVideo() {

    }

    fun getTextureViewSize(context: Context, parentSize: DpSize, ratio: CameraRatio = _cameraRatio.value) : DpSize? {
        val orientation = context.resources.configuration.orientation
        if (parentSize.width == 0.dp || parentSize.height == 0.dp) return null
        return when (ratio) {
            CameraRatio.RATIO_1_1 -> {
                val size = min(parentSize.width, parentSize.height)
                DpSize(size, size)
            }
            CameraRatio.RATIO_4_3 -> {
                val ratioX = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 4 else 3
                val ratioY = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 4
                if (parentSize.width / ratioX * ratioY <= parentSize.height) {
                    DpSize(parentSize.width, parentSize.width / ratioX * ratioY)
                } else {
                    DpSize(parentSize.height / ratioY * ratioX, parentSize.height)
                }
            }
            CameraRatio.RATIO_16_9 -> {
                val ratioX = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 16 else 9
                val ratioY = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 9 else 16
                if (parentSize.width / ratioX * ratioY <= parentSize.height) {
                    DpSize(parentSize.width, parentSize.width / ratioX * ratioY)
                } else {
                    DpSize(parentSize.height / ratioY * ratioX, parentSize.height)
                }
            }
            CameraRatio.RATIO_FULL -> {
                DpSize(parentSize.width, parentSize.height)
            }
        }
    }

    class Factory(
        private val context: Context,
        private val cameraDirection: CameraDirection = CameraDirection.BACK,
        private val cameraAngle: CameraAngle = CameraAngle.NORMAL,
        private val cameraRatio: CameraRatio = CameraRatio.RATIO_4_3,
        private val cameraFlash: CameraFlash = CameraFlash.OFF,
        private val imageQuality: CameraQuality = CameraQuality.HIGH,
        private val videoQuality: CameraQuality = CameraQuality.HIGH,
        private val onProcessImage: ((ImageProcessData) -> Unit)? = null
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(CameraViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return CameraViewModel(
                    context = context,
                    cameraDirection = cameraDirection,
                    cameraAngle = cameraAngle,
                    cameraRatio = cameraRatio,
                    cameraFlash = cameraFlash,
                    imageQuality = imageQuality,
                    videoQuality = videoQuality,
                    onProcessImage = onProcessImage,
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

