package com.ryu.personal.android.camerautil.engine

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.ryu.personal.android.camerautil.constant.CameraAngle
import com.ryu.personal.android.camerautil.constant.CameraDirection
import com.ryu.personal.android.camerautil.constant.CameraEngineState
import com.ryu.personal.android.camerautil.constant.CameraFlash
import com.ryu.personal.android.camerautil.constant.CameraRatio
import com.ryu.personal.android.camerautil.constant.ORIENTATIONS
import com.ryu.personal.android.camerautil.model.CameraInfo
import com.ryu.personal.android.camerautil.util.AutoFitTextureView
import com.ryu.personal.android.camerautil.util.CameraInfoUtil
import com.ryu.personal.android.camerautil.util.ImageSaver
import com.ryu.personal.android.camerautil.util.OrientationLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max

//https://github.com/android/camera-samples/tree/master/Camera2Basic
//https://github.com/googlearchive/android-Camera2Basic/tree/master

class Camera(
    private val context: Context,
    private val onLoadListener: (Boolean) -> Unit,
    private val processImage: ((Image) -> Unit)? = null,
) {
    private val cameraUtil: CameraInfoUtil by lazy { CameraInfoUtil(context) }
    private val cameraManager: CameraManager by lazy { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private var textureView: AutoFitTextureView = AutoFitTextureView(context)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewRequest: CaptureRequest? = null
    private var previewSize: Size? = null
    private var state = CameraEngineState.STATE_PREVIEW

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var imageReaderThread: HandlerThread? = null
    private var imageReaderHandler: Handler? = null

    private var sensorOrientation: Int = 0
    private var deviceOrientation: Int = 0
    private lateinit var relativeOrientation: OrientationLiveData
    private val orientationObserver = Observer<Int> {
        deviceOrientation = it
    }
    private var cameraOpenCloseLock = Semaphore(1)

    private var direction: CameraDirection? = null
    private var angle: CameraAngle? = null
    private var ratio: CameraRatio? = null
    private var flash: CameraFlash? = null
    private var realTextureViewSize: Size? = null

    private var lastProcessTime = 0L // 마지막 스캔 시간 저장
    private var isRestartRunning = false

    private val onImageAvailableListener = object: ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(reader: ImageReader?) {
            reader?.acquireLatestImage()?.let { image ->
                if (state == CameraEngineState.STATE_PREVIEW) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastProcessTime >= 500) {
                        lastProcessTime = currentTime
                        processImage?.invoke(image)
                    }
                    image.close()
                } else if (state == CameraEngineState.STATE_PICTURE_TAKEN) {
                    ratio?.let {
                        cameraHandler?.post { ImageSaver(
                            context,
                            image,
                            deviceOrientation,
                            ratio!!,
                            realTextureViewSize,
                            direction == CameraDirection.FRONT
                        ).run() }
                    }
                } else {
                    image.close()
                }
            }
        }
    }

    private val captureCallback = object: CameraCaptureSession.CaptureCallback() {
        private fun process(result: CaptureResult) {
            when (state) {
                CameraEngineState.STATE_WAITING_LOCK -> {
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    if (afState == null) {
                        captureStillPicture()
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState ||
                        CaptureResult.CONTROL_AF_STATE_INACTIVE == afState) {
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            state = CameraEngineState.STATE_PICTURE_TAKEN
                            captureStillPicture()
                        } else {
                            runPreCaptureSequence()
                        }
                    }
                }
                CameraEngineState.STATE_WAITING_PRECAPTURE -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null ||
                        aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                        aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        state = CameraEngineState.STATE_WAITING_NON_PRECAPTURE
                    }
                }
                CameraEngineState.STATE_WAITING_NON_PRECAPTURE -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        state = CameraEngineState.STATE_PICTURE_TAKEN
                        captureStillPicture()
                    }
                }
                else -> {
                    // CameraEngineState.STATE_PREVIEW
                    // CameraEngineState.STATE_PICTURE_TAKEN
                }
            }
        }

        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
            process(partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            process(result)
        }
    }

    fun getTextureView(): AutoFitTextureView = textureView

    fun setRealTextureViewSize(size: Size) {
        realTextureViewSize = size
    }

    fun startCamera(
        cameraDirection: CameraDirection,
        cameraAngle: CameraAngle,
        cameraRatio: CameraRatio,
        cameraFlash: CameraFlash,
    ) {
        onLoadListener(true)
        direction = cameraDirection
        angle = cameraAngle
        ratio = cameraRatio
        flash = cameraFlash
        startBackgroundThread()
        cameraUtil.getCameraInfo(cameraDirection, cameraAngle)?.let { cameraInfo ->
            if (textureView.isAvailable == true) {
                openCamera(textureView.width, textureView.height, cameraInfo, cameraRatio, cameraFlash)
            } else {
                textureView.surfaceTextureListener = object: TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        openCamera(width, height, cameraInfo, cameraRatio, cameraFlash)
                    }
                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                        configureTransform(width, height)
                    }
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
            }
        }
    }

    fun stopCamera() {
        try {
            onLoadListener(true)
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            relativeOrientation.removeObserver(orientationObserver)
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
        stopBackgroundThread()
    }

    fun reStartCamera(
        cameraDirection: CameraDirection? = null,
        cameraAngle: CameraAngle? = null,
        cameraRatio: CameraRatio? = null,
    ) {
        if (isRestartRunning) return
        isRestartRunning = true
        stopCamera()
        if (cameraDirection != null) direction = cameraDirection
        if (cameraAngle != null) angle = cameraAngle
        if (cameraRatio != null) ratio = cameraRatio
        if (direction != null && angle != null && ratio != null && flash != null) {
            startCamera(direction!!, angle!!, ratio!!, flash!!)
        }
    }

    suspend fun changeCameraFlash(cameraFlash: CameraFlash) {
        if (direction == null || angle == null || previewRequestBuilder == null) return
        val cameraInfo = cameraUtil.getCameraInfo(direction!!, angle!!) ?: return
        setAutoFlash(previewRequestBuilder!!, cameraInfo, CameraFlash.OFF)
        previewRequest = previewRequestBuilder!!.build()
        captureSession?.setRepeatingRequest(previewRequest!!, captureCallback, cameraHandler)
        delay(100)
        setAutoFlash(previewRequestBuilder!!, cameraInfo, cameraFlash)
        previewRequest = previewRequestBuilder!!.build()
        captureSession?.setRepeatingRequest(previewRequest!!, captureCallback, cameraHandler)
        flash = cameraFlash
    }

    private fun openCamera(
        viewWidth: Int,
        viewHeight: Int,
        cameraInfo: CameraInfo,
        cameraRatio: CameraRatio,
        cameraFlash: CameraFlash
    ) = CoroutineScope(Dispatchers.IO).launch {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) return@launch
        setUpCameraOutputs(viewWidth, viewHeight, cameraInfo, cameraRatio)
        configureTransform(viewWidth, viewHeight)

        val onLoadFinish = CoroutineScope(Dispatchers.Main).launch {
            delay(500)
            onLoadListener(false)
        }

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            cameraManager.openCamera(cameraInfo.id, object: CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    createCameraPreviewSession(cameraInfo, cameraFlash)
                    onLoadFinish.start()
                    isRestartRunning = false
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    isRestartRunning = false
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    isRestartRunning = false
                    val msg = when (error) {
                        ERROR_CAMERA_DEVICE -> "Fatal (device)"
                        ERROR_CAMERA_DISABLED -> "Device policy"
                        ERROR_CAMERA_IN_USE -> "Camera in use"
                        ERROR_CAMERA_SERVICE -> "Fatal (service)"
                        ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                        else -> "Unknown"
                    }
                    throw RuntimeException("Camera ${cameraInfo.id} error: ($error) $msg")
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    private fun setUpCameraOutputs(
        width: Int,
        height: Int,
        cameraInfo: CameraInfo,
        cameraRatio: CameraRatio
    ) {
        val supportOutputSizes = cameraUtil.getSupportOutputSizesFromRatio(cameraInfo.supportOutputSizes, cameraRatio, Size(width, height))
        val largest: Size = supportOutputSizes.maxByOrNull { it.width * it.height } ?: return

        val MAX_IMAGES = 3
        imageReader = ImageReader.newInstance(largest.width, largest.height, ImageFormat.YUV_420_888, MAX_IMAGES).apply {
            setOnImageAvailableListener(onImageAvailableListener, imageReaderHandler)
        }

        val characteristics = cameraManager.getCameraCharacteristics(cameraInfo.id)
        relativeOrientation = OrientationLiveData(context, characteristics).apply {
            GlobalScope.launch(Dispatchers.Main) {
                observeForever(orientationObserver)
            }
        }
        val displayRotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        var swappedDimensions = false
        when (displayRotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                if (sensorOrientation == 90 || sensorOrientation == 270) {
                    swappedDimensions = true;
                }
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                if (sensorOrientation == 0 || sensorOrientation == 180) {
                    swappedDimensions = true
                }
            }
        }

        var rotatedPreviewWidth = width
        var rotatedPreviewHeight = height
        var maxPreviewWidth = width
        var maxPreviewHeight = height
        if (swappedDimensions) {
            rotatedPreviewWidth = height
            rotatedPreviewHeight = width
            maxPreviewWidth = height
            maxPreviewHeight = width
        }
        previewSize = chooseOptimalSize(
            supportOutputSizes,
            rotatedPreviewWidth,
            rotatedPreviewHeight,
            maxPreviewWidth,
            maxPreviewHeight,
            largest
        )

        val orientation = context.resources.configuration.orientation
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            textureView.setAspectRatio(previewSize?.width ?: 0, previewSize?.height ?: 0)
        } else {
            textureView.setAspectRatio(previewSize?.height ?: 0, previewSize?.width ?: 0)
        }
    }

    private fun chooseOptimalSize(
        choices: List<Size>,
        textureViewWidth: Int,
        textureViewHeight: Int,
        maxWidth: Int,
        maxHeight: Int,
        aspectRatio: Size
    ): Size {
        val bigEnough = mutableListOf<Size>()
        val notBigEnough = mutableListOf<Size>()
        val w = aspectRatio.width
        val h = aspectRatio.height
        choices.forEach { option ->
            if (option.width <= maxWidth && option.height <= maxHeight &&
                option.height == option.width * h / w) {
                if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                    bigEnough.add(option)
                } else {
                    notBigEnough.add(option)
                }
            }
        }

        return if (bigEnough.isNotEmpty()) {
            bigEnough.minByOrNull { it.width * it.height }!!
        } else if (notBigEnough.isNotEmpty()) {
            notBigEnough.maxByOrNull { it.width * it.height }!!
        } else {
            choices[0]
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (previewSize == null) return
        val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize!!.height.toFloat(), previewSize!!.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(
                viewHeight.toFloat() / previewSize!!.height,
                viewWidth.toFloat() / previewSize!!.width
            )
            matrix.postRotate(90f * (rotation - 2), centerX, centerY)
            matrix.postScale(scale, scale, centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }

    private fun createCameraPreviewSession(cameraInfo: CameraInfo, cameraFlash: CameraFlash) {
        if (previewSize == null || cameraDevice == null || imageReader == null) return
        val texture: SurfaceTexture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)
        val surface: Surface = Surface(texture)
        previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewRequestBuilder?.addTarget(surface)
        cameraDevice!!.createCaptureSession(listOf(surface, imageReader!!.surface), object: CameraCaptureSession.StateCallback() {
            override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                captureSession = cameraCaptureSession
                try {
                    previewRequestBuilder?.let { builder ->
                        if (processImage != null) {
                            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            imageReader?.let {
                                builder.addTarget(it.surface)
                            }
                        }

                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        setAutoFlash(builder, cameraInfo, cameraFlash)
                        previewRequest = builder.build()
                        captureSession?.setRepeatingRequest(previewRequest!!, captureCallback, cameraHandler)
                    }
                } catch (e: CameraAccessException) {
                    e.printStackTrace()
                }
            }

            override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) = Unit
        }, null)
    }

    private fun startBackgroundThread() {
        stopBackgroundThread()
        cameraThread = HandlerThread("CameraThread").apply {
            start()
            cameraHandler = Handler(looper)
        }
        imageReaderThread = HandlerThread("imageReaderThread").apply {
            start()
            imageReaderHandler = Handler(looper)
        }
    }

    private fun stopBackgroundThread() {
        cameraThread?.quitSafely()
        imageReaderThread?.quitSafely()
        try {
            cameraThread?.join()
            imageReaderThread?.join()
            cameraThread = null
            imageReaderThread = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun setAutoFlash(requestBuilder: CaptureRequest.Builder, cameraInfo: CameraInfo, cameraFlash: CameraFlash) {
        if (cameraInfo.supportFlash) {
            when (cameraFlash) {
                CameraFlash.AUTO -> requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                CameraFlash.ON -> {
                    requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                }
                CameraFlash.OFF -> {
                    requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
                CameraFlash.TORCH -> {
                    requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }
            }
        } else {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }
    }


    fun takePicture() {
        lockFocus()
    }

    fun recordVideo() {

    }

    private fun lockFocus() {
        try {
            state = CameraEngineState.STATE_WAITING_LOCK
            previewRequestBuilder?.let {
                it.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                captureSession?.capture(it.build(), captureCallback, cameraHandler)
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun captureStillPicture() {
        try {
            if (cameraDevice == null || imageReader == null || direction == null || angle == null || flash == null) return
            val cameraInfo = cameraUtil.getCameraInfo(direction!!, angle!!) ?: return
            val captureBuilder: CaptureRequest.Builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader!!.surface)
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            setAutoFlash(captureBuilder, cameraInfo, flash!!)

            val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation))
            val captureCallback = object: CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    unlockFocus(cameraInfo)
                }
            }
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
            captureSession?.capture(captureBuilder.build(), captureCallback, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun unlockFocus(cameraInfo: CameraInfo) {
        try {
            previewRequestBuilder?.let {
                it.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                setAutoFlash(it, cameraInfo, flash!!)
                captureSession?.capture(it.build(), captureCallback, cameraHandler)
                state = CameraEngineState.STATE_PREVIEW
                previewRequest?.let {
                    captureSession?.setRepeatingRequest(it, captureCallback, cameraHandler)
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun runPreCaptureSequence() {
        try {
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            state = CameraEngineState.STATE_WAITING_PRECAPTURE
            previewRequestBuilder?.let {
                captureSession?.capture(it.build(), captureCallback, cameraHandler)
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun getOrientation(rotation: Int): Int {
        return (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360
    }
}