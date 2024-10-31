package com.ryu.personal.android.camerautil.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.lifecycle.Observer
import com.ryu.personal.android.camerautil.constant.CameraAngle
import com.ryu.personal.android.camerautil.constant.CameraEngineState
import com.ryu.personal.android.camerautil.constant.CameraDirection
import com.ryu.personal.android.camerautil.constant.CameraFlash
import com.ryu.personal.android.camerautil.constant.CameraQuality
import com.ryu.personal.android.camerautil.constant.CameraQualityType
import com.ryu.personal.android.camerautil.constant.CameraRatio
import com.ryu.personal.android.camerautil.model.CameraInfo
import com.ryu.personal.android.camerautil.model.ImageProcessData
import com.ryu.personal.android.camerautil.model.ImageSaveData
import com.ryu.personal.android.camerautil.util.AutoFitTextureView
import com.ryu.personal.android.camerautil.util.CameraInfoUtil
import com.ryu.personal.android.camerautil.util.ImageSaver
import com.ryu.personal.android.camerautil.util.OrientationLiveData
import com.ryu.personal.android.camerautil.util.toByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max

class Camera(
    private val context: Context,
    private var cameraDirection: CameraDirection,
    private var cameraAngle: CameraAngle,
    private var cameraRatio: CameraRatio,
    private var cameraFlash: CameraFlash,
    private var imageQuality: CameraQuality,
    private var videoQuality: CameraQuality,
    private val onShowPreview: ((Boolean) -> Unit)? = null,
    private val onProcessImage: ((image: ImageProcessData) -> Unit)? = null,
) {
    /***********************************************************************************************
     * 변수 설정
     **********************************************************************************************/

    /*================================
        * 공동 사용 관련
    ===============================*/
    private val cameraInfoUtil: CameraInfoUtil = CameraInfoUtil(context)
    private lateinit var cameraInfo: CameraInfo
    private var captureState = CameraEngineState.STATE_PREVIEW
    private var backgroundHandlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var cameraOpenCloseLock = Semaphore(1)
    private val cameraManager: CameraManager by lazy { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    /*================================
        * 회전 관련
    ===============================*/
    private var orientation: Int = 0
    private var orientationLiveData: OrientationLiveData? = null
    private val orientationObserver = Observer<Int> { orientation = it }

    /*================================
        * CameraDevice 관련
    ===============================*/
    private var cameraDevice: CameraDevice? = null
    private val cameraDeviceStateCallback = object: CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = camera
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
            if(isRecording) {

            } else {
                startPreviewSession()
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
        }

    }

    /*================================
        * TextureView 관련
    ===============================*/
    private var textureView: AutoFitTextureView = AutoFitTextureView(context)
    private val textureViewListener = object: TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    /*================================
        * 실시간 프리뷰
    ===============================*/
    private var previewSize: Size? = null
    private var previewImageReader: ImageReader? = null
    private var lastProcessTime = 0L // 마지막 스캔 시간 저장
    private var onPreviewImageAvailableListener = object: ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(reader: ImageReader?) {
            reader?.acquireLatestImage()?.let { image ->
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastProcessTime >= 300 && image.format == ImageFormat.YUV_420_888) {
                    lastProcessTime = currentTime
                    image.toByteArray()?.let { byteArray ->
                        onProcessImage?.invoke(
                            ImageProcessData(
                                nv21ImageData = byteArray,
                                width = image.width,
                                height = image.height,
                                orientation = orientation
                            )
                        )
                    }
                }
                image.close()
            }
        }
    }

    /*================================
       * 사진 촬영
    ===============================*/
    private var imageSize: Size? = null
    private var imageReader: ImageReader? = null
    private val onImageAvailableListener = object: ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(reader: ImageReader?) {
            if (captureState == CameraEngineState.STATE_WATING_SAVE) {
                reader?.acquireLatestImage()?.let { image ->
                    image.toByteArray()?.let { byteArray ->
                        backgroundHandler?.post(ImageSaver(
                            context = context,
                            saveData = ImageSaveData(
                                imageData = byteArray,
                                width = image.width,
                                height = image.height,
                                orientation = orientation,
                                ratio = cameraRatio,
                                viewSize = Size(textureView.width, textureView.height),
                                mirrorMode = cameraDirection == CameraDirection.FRONT,
                            )
                        ))
                    }
                    image.close()
                }
            }
        }
    }

    /*================================
        * 비디오 촬영
    ===============================*/
    private var videoSize: Size? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var isTimelapse = false

    /*================================
        * 캡처 세션 관련
    ===============================*/
    private var previewCaptureSession: CameraCaptureSession? = null
    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        private fun  process(captureResult: CaptureResult) {

            when (captureState) {
                CameraEngineState.STATE_WAITING_LOCK -> {
                    val afState = captureResult.get(CaptureResult.CONTROL_AF_STATE)
                    if (afState == null) {
                        captureStillPicture()
                    } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                        val aeState = captureResult.get(CaptureResult.CONTROL_AE_STATE)
                        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            captureStillPicture()
                        } else {
                            runPreCaptureSequence()
                        }
                    } else {
                        captureState = CameraEngineState.STATE_PREVIEW
                    }
                }
                CameraEngineState.STATE_WAITING_PRECAPTURE -> {
                    val aeState = captureResult.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null ||
                        aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                        aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        captureState = CameraEngineState.STATE_WAITING_NON_PRECAPTURE
                    }
                }
                CameraEngineState.STATE_WAITING_NON_PRECAPTURE -> {
                    val aeState = captureResult.get(CaptureResult.CONTROL_AE_STATE)
                    captureStillPicture()
//                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
//                        captureStillPicture()
//                    } else {
//                        captureState = CameraEngineState.STATE_PREVIEW
//                    }
                }
                else -> Unit
            }
        }

        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
//            super.onCaptureProgressed(session, request, partialResult)
            process(partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
//            super.onCaptureCompleted(session, request, result)
            process(result)
        }
    }
    private var recordCaptureSession: CameraCaptureSession? = null
    private val recordCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        private fun  process(captureResult: CaptureResult) {

        }

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            super.onCaptureCompleted(session, request, result)
            process(result)
        }
    }
    private var captureRequestBuilder: CaptureRequest.Builder? = null

    /***********************************************************************************************
     * 유저 호출 이벤트
     **********************************************************************************************/
    fun getView(): AutoFitTextureView = textureView

    fun startPreview() {
        onShowPreview?.invoke(false)
        this.startBackgroundThread()
        if (textureView.isAvailable) {
            this.openCamera()
        } else {
            this.textureView.surfaceTextureListener = textureViewListener
        }
    }

    fun changePreviewSession() {
        setUpCameraOutputs()
        configureTransform()
        startPreviewSession()
    }

    fun stopPreview() {
        this.closeCamera()
        this.stopBackgroundThread()
    }

    fun takePicture() {
        lockFocus()
    }



    fun takeVideo() {

    }

    fun setCameraDirection(cameraDirection: CameraDirection) {
        if (this.cameraDirection != cameraDirection) {
            this.cameraDirection = cameraDirection
            this.reOpenCamera()
        }
    }

    fun setCameraAngle(cameraAngle: CameraAngle) {
        if (this.cameraAngle != cameraAngle) {
            this.cameraAngle = cameraAngle
            this.reOpenCamera()
        }
    }

    fun setCameraRatio(cameraRatio: CameraRatio) {
        if (this.cameraRatio != cameraRatio) {
            this.cameraRatio = cameraRatio
            onShowPreview?.invoke(false)
            changePreviewSession()
        }
    }

    fun setCameraFlash(cameraFlash: CameraFlash) {
        if (this.cameraFlash != cameraFlash) {
            this.cameraFlash = cameraFlash
            this.setCameraFlash()
        }
    }

    /***********************************************************************************************
     * 카메라 설정
     **********************************************************************************************/

    /*================================
        * 프리뷰 시작
    ===============================*/
    @SuppressLint("MissingPermission")
    private fun openCamera() {
        this.setCameraInfo()
        this.setUpCameraOutputs()
        this.configureTransform()
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            cameraManager.openCamera(cameraInfo.id, cameraDeviceStateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setUpCameraOutputs() {
        val width = textureView.width
        val height = textureView.height
        previewSize = chooseOptimalSize(cameraInfo.supportOutputSizes.preview, width, height, CameraQualityType.PREVIEW).also {
            if (onProcessImage != null) {
                previewImageReader = ImageReader.newInstance(it.width, it.height, ImageFormat.YUV_420_888, 3).apply {
                    setOnImageAvailableListener(onPreviewImageAvailableListener, backgroundHandler)
                }
            }
        }
        imageSize = chooseOptimalSize(cameraInfo.supportOutputSizes.picture, width, height, CameraQualityType.IMAGE).also {
            imageReader = ImageReader.newInstance(it.width, it.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
            }
        }
        videoSize = chooseOptimalSize(cameraInfo.supportOutputSizes.video, width, height, CameraQualityType.VIDEO)
    }

    private fun chooseOptimalSize(
        choices: List<Size>,
        width: Int,
        height: Int,
        qualityType: CameraQualityType
    ): Size {
        val sizes = cameraInfoUtil.getSupportOutputSizesFromRatio(choices, cameraRatio, Size(width, height))
        return when (qualityType) {
            CameraQualityType.PREVIEW -> {
                val bigEnough = mutableListOf<Size>()
                val notBigEnough = mutableListOf<Size>()
                for (size in sizes) {
                    if (size.width * size.height > width * height) bigEnough.add(size) else notBigEnough.add(size)
                }
                notBigEnough.maxByOrNull { it.width * it.height } ?:bigEnough.minByOrNull { it.width * it.height } ?: sizes[0]
            }
            CameraQualityType.IMAGE -> {
                when (imageQuality) {
                    CameraQuality.HIGH -> {
                        sizes.maxByOrNull { it.width * it.height } ?: sizes[0]
                    }
                    CameraQuality.MEDIUM -> {
                        sizes[(sizes.lastIndex * 0.3).toInt()]
                    }
                    CameraQuality.LOW -> {
                        sizes[(sizes.lastIndex * 0.6).toInt()]
                    }
                }
            }
            CameraQualityType.VIDEO -> {
                when (videoQuality) {
                    CameraQuality.HIGH -> {
                        sizes.maxByOrNull { it.width * it.height } ?: sizes[0]
                    }
                    CameraQuality.MEDIUM -> {
                        sizes[(sizes.lastIndex * 0.3).toInt()]
                    }
                    CameraQuality.LOW -> {
                        sizes[(sizes.lastIndex * 0.6).toInt()]
                    }
                }
            }
        }
    }

    private fun configureTransform() {
        val width = textureView.width
        val height = textureView.height
        if (previewSize == null) return
        val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        val bufferRect = RectF(0f, 0f, previewSize!!.height.toFloat(), previewSize!!.width.toFloat())
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        var scale = max(viewRect.width() / bufferRect.width(), viewRect.height() / bufferRect.height())

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            matrix.postRotate(90f * (rotation - 2), centerX, centerY)
            scale = max(viewRect.width() / bufferRect.height(), viewRect.height() / bufferRect.width())
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        matrix.postScale(scale, scale, centerX, centerY)
        textureView.setTransform(matrix)
    }

    private fun startPreviewSession() {
        if (previewSize == null || cameraDevice == null || imageReader == null) return
        val texture: SurfaceTexture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)
        val surface: Surface = Surface(texture)
        val surfaceList = mutableListOf(surface, imageReader!!.surface)
        try {
            captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                previewImageReader?.let {
                    addTarget(it.surface)
                    surfaceList.add(it.surface)
                }
            }
//            captureRequestBuilder!!.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            cameraDevice!!.createCaptureSession(surfaceList, object: CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    previewCaptureSession = session
                    try {
                        setCameraFlash()
                        previewCaptureSession?.setRepeatingRequest(captureRequestBuilder!!.build(), null, backgroundHandler)
                    } catch (e: CameraAccessException) {
                        e.printStackTrace()
                    } catch (e: IllegalArgumentException) {
                        e.printStackTrace()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) = Unit
            }, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        CoroutineScope(Dispatchers.Main).launch {
            delay(500)
            onShowPreview?.invoke(true)
        }
    }

    private fun startBackgroundThread() {
        backgroundHandlerThread = HandlerThread("CameraThread").apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }
    /*================================
        * 프리뷰 종료
    ===============================*/
    private fun closeCamera() {
        try {
            onShowPreview?.invoke(false)
            cameraOpenCloseLock.acquire()
            orientationLiveData?.removeObserver(orientationObserver)
            orientationLiveData = null
            previewCaptureSession?.close()
            previewCaptureSession = null
            recordCaptureSession?.close()
            recordCaptureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            previewImageReader?.close()
            previewImageReader = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        } finally {
            cameraOpenCloseLock.release()
        }

    }

    private fun stopBackgroundThread() {
        backgroundHandlerThread?.quitSafely()
        try {
            backgroundHandlerThread?.join()
            backgroundHandlerThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    /*================================
        * 카메라 설정값 변경
    ===============================*/
    private fun reOpenCamera() {
        closeCamera()
        if (textureView.isAvailable) {
            this.openCamera()
        } else {
            this.textureView.surfaceTextureListener = textureViewListener
        }
    }

    private fun setCameraInfo() {
        cameraInfo = cameraInfoUtil.getCameraInfo(cameraDirection, cameraAngle).getOrThrow()
        orientationLiveData?.removeObserver(orientationObserver)
        val characteristics = cameraManager.getCameraCharacteristics(cameraInfo.id)
        orientationLiveData = OrientationLiveData(context, characteristics).apply {
            GlobalScope.launch(Dispatchers.Main) { observeForever(orientationObserver) }
        }
    }

    private fun setCameraFlash() {
        if (captureRequestBuilder == null) return
        if (cameraInfo.supportFlash) {
            when (cameraFlash) {
                CameraFlash.OFF -> {
                    captureRequestBuilder!!.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    captureRequestBuilder!!.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
                CameraFlash.ON -> {
                    captureRequestBuilder!!.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    captureRequestBuilder!!.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
                CameraFlash.TORCH -> {
                    captureRequestBuilder!!.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    captureRequestBuilder!!.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }
                CameraFlash.AUTO -> {
                    captureRequestBuilder!!.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                    captureRequestBuilder!!.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
                CameraFlash.RED_EYE -> {
                    captureRequestBuilder!!.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE)
                    captureRequestBuilder!!.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }
        } else {
            captureRequestBuilder!!.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            captureRequestBuilder!!.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }
        try {
            previewCaptureSession?.setRepeatingRequest(captureRequestBuilder!!.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /*================================
        * 사진 촬영
    ===============================*/
    private fun lockFocus() {
        if (captureRequestBuilder == null || captureState != CameraEngineState.STATE_PREVIEW) return
        captureState = CameraEngineState.STATE_WAITING_LOCK
        captureRequestBuilder!!.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        try {
            if(isRecording) {
                recordCaptureSession?.capture(captureRequestBuilder!!.build(), recordCaptureCallback, backgroundHandler)
            } else {
                previewCaptureSession?.capture(captureRequestBuilder!!.build(), previewCaptureCallback, backgroundHandler)
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace();
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun captureStillPicture() {
        captureState = CameraEngineState.STATE_WATING_SAVE
        if (cameraDevice == null || imageReader == null) return
        try {
            captureRequestBuilder  = if(isRecording) {
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT)
            } else {
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            }.apply {
                addTarget(imageReader!!.surface)
                set(CaptureRequest.JPEG_ORIENTATION, orientation)
            }

            val stillCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                    super.onCaptureStarted(session, request, timestamp, frameNumber)
                    Timber.d("이미지 저장 시작")
                }

                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    Timber.d("이미지 저장 종료")
                    unlockFocus()
                }
            }

            if(isRecording) {
                recordCaptureSession?.let { session ->
//                    session.stopRepeating()
//                    session.abortCaptures()
                    session.capture(captureRequestBuilder!!.build(), stillCaptureCallback, null)
                }
            } else {
                previewCaptureSession?.let { session ->
//                    session.stopRepeating()
//                    session.abortCaptures()
                    session.capture(captureRequestBuilder!!.build(), stillCaptureCallback, null)
                }
            }


        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun runPreCaptureSequence() {
        captureRequestBuilder?.let { builder ->
            captureState = CameraEngineState.STATE_WAITING_PRECAPTURE
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            try {
                if (isRecording) {
                    recordCaptureSession?.capture(builder.build(), recordCaptureCallback, backgroundHandler)
                } else {
                    previewCaptureSession?.capture(builder.build(), previewCaptureCallback, backgroundHandler)
                }
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }
        }
    }

    private fun unlockFocus() {
        try {
            captureRequestBuilder?.let { builder ->
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                startPreviewSession()
            }
            captureState = CameraEngineState.STATE_PREVIEW
//            finishTakePicture?.invoke()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}