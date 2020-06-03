package info.benjaminhill.ezcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.ajalt.timberkt.Timber
import com.github.ajalt.timberkt.d
import com.github.ajalt.timberkt.i
import com.github.ajalt.timberkt.w
import info.benjaminhill.droid.LazySuspend
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import java.util.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


@SuppressLint("MissingPermission")
/**
 * camera2 API deconstructed - done through lazy loads
 * To use, parent class should extend EZPermissionActivity
 * Then wrap calls in `launch { FlowCam(this@MainActivity, textureView, ImageFormat.JPEG).flow().take(5)... }`
 */
open class FlowCam
@RequiresPermission(allOf = [
    Manifest.permission.CAMERA,
    Manifest.permission.READ_EXTERNAL_STORAGE,
    Manifest.permission.WRITE_EXTERNAL_STORAGE
])
constructor(
        private val context: Activity,
        private val previewTextureView: TextureView,
        private val imageFormat: Int = ImageFormat.JPEG
) {
    @ExperimentalCoroutinesApi
    val images = flow {
        while (true) {
            i { "Flow:click" }
            emit(clickAndGet())
        }
    }.onStart {
        i { "Flow:onStart" }
        startPreview()
    }.onCompletion {
        i { "Flow:onCompletion" }
        stopPreview()
        close()
    }


    private lateinit var easyImageCont: Continuation<EasyImage>
    private suspend fun clickAndGet(): EasyImage = suspendCancellableCoroutine {
        easyImageCont = it
        runBlocking {
            takePicture()
        }
    }

    private val previewSurface = LazySuspend {
        d { "EZCam.previewSurface:start" }
        if (previewTextureView.isAvailable) {
            Surface(previewTextureView.surfaceTexture).also {
                i { "Created previewSurface directly" }
            }
        } else {
            suspendCoroutine { cont ->
                previewTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    @SuppressLint("Recycle")
                    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                        cont.resume(Surface(surfaceTexture).also {
                            i { "Created previewSurface through a surfaceTextureListener" }
                        })
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = false
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }
        }
    }

    /** A fully opened camera */
    private val cameraDevice = LazySuspend<CameraDevice> {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("You don't have the required permissions to open the camera, try guarding with EZPermission.")
        }
        d { "cameraManager.openCamera onOpened, cameraDevice is now ready." }
        suspendCoroutine { cont ->
            cameraManager.openCamera(bestCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) = cont.resume(camera).also {
                    i { "cameraManager.openCamera onOpened, cameraDevice is now ready." }
                }

                override fun onDisconnected(camera: CameraDevice) = cont.resumeWithException(Exception("Problem with cameraManager.openCamera onDisconnected")).also {
                    w { "camera onDisconnected: Camera device is no longer available for use." }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    when (error) {
                        ERROR_CAMERA_DEVICE -> w { "CameraDevice.StateCallback: Camera device has encountered a fatal error." }
                        ERROR_CAMERA_DISABLED -> w { "CameraDevice.StateCallback: Camera device could not be opened due to a device policy." }
                        ERROR_CAMERA_IN_USE -> w { "CameraDevice.StateCallback: Camera device is in use already." }
                        ERROR_CAMERA_SERVICE -> w { "CameraDevice.StateCallback: Camera service has encountered a fatal error." }
                        ERROR_MAX_CAMERAS_IN_USE -> w { "CameraDevice.StateCallback: Camera device could not be opened because there are too many other open camera devices." }
                    }
                    try {
                        cont.resumeWithException(Exception("openCamera onError $error"))
                    } catch (e: IllegalStateException) {
                        w { "Swallowing resumeWithException because not the first resume." }
                    }

                }
            }, backgroundHandler)
        }
    }


    /** A fully configured capture session */
    private val cameraCaptureSession = LazySuspend<CameraCaptureSession> {
        d { "EZCam.cameraCaptureSession:start" }
        val cd = cameraDevice()
        val rs = previewSurface()
        suspendCoroutine { cont ->

            val outputConfigs = listOf(rs, imageReader.surface).map { OutputConfiguration(it) }
            val stateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) = cont.resume(session).also {
                    i { "Created cameraCaptureSession through createCaptureSession.onConfigured" }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    val e = Exception("createCaptureSession.onConfigureFailed")
                    Timber.e(e) { "onConfigureFailed: Could not configure capture session." }
                    cont.resumeWithException(e)
                }
            }

            cd.createCaptureSession(SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    ContextCompat.getMainExecutor(context),
                    stateCallback
            ))
        }
    }

    /** Builder set to preview mode */
    private val captureRequestBuilderForPreview = LazySuspend {
        cameraDevice().createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).also {
            it.addTarget(previewSurface())
            i { "captureRequestBuilderForPreview:created" }
        }
    }


    /** Builder set to higher quality capture mode */
    private val captureRequestBuilderForImageReader = LazySuspend {
        cameraDevice().createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).also {
            it.addTarget(imageReader.surface)

            when (imageFormat) {
                ImageFormat.JPEG -> {
                    it.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
                    it.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_NIGHT)
                    it.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                    it.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
                    it.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                    it.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
                    it.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
                    it.set(CaptureRequest.JPEG_QUALITY, 99.toByte())
                    it.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    it.set(CaptureRequest.SENSOR_SENSITIVITY, 600) // iso
                    it.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)

                    // Max Exposure
                    cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.upper?.let { maxExposure ->
                        it.set(CaptureRequest.SENSOR_EXPOSURE_TIME, maxExposure)
                        i { "Set exposure to max ${maxExposure / 1_000_000_000.0} seconds" }
                    }

                    // FocusDistanceMax
                    val hyperfocalDistance = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)
                            ?: 0.0f.also { w { "Hyperfocal distance not available" } }
                    // div 2 because I think it might help hedging towards infinity
                    it.set(CaptureRequest.LENS_FOCUS_DISTANCE, hyperfocalDistance / 2) //
                    i { "Set focus to hyperfocal diopters: $hyperfocalDistance / 2" }

                }
                ImageFormat.DEPTH16 -> {
                    it.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
                }
            }

            i { "captureRequestBuilderForImageReader:created (and set to format-specific settings)" }
        }
    }

    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE).also {
            i { "cameraManager:created" }
        } as CameraManager
    }

    private val imageSizeForImageReader: Size by lazy {
        cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(imageFormat).maxBy {
            it.width * it.height
        }!!.also {
            i { "Found max size for the camera: $it" }
        }
    }

    protected val cameraCharacteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(bestCameraId).also {
            i { "cameraCharacteristics:created for camera $bestCameraId" }
        }
    }

    private val imageReader: ImageReader by lazy {
        // TODO: Previews should be smaller res
        ImageReader.newInstance(
                imageSizeForImageReader.width,
                imageSizeForImageReader.height,
                imageFormat,
                MAX_IMAGES
        ).also {
            it.setOnImageAvailableListener(onImageAvailableForImageReader, backgroundHandler)
            i { "imageReader:created maxImages ${it.maxImages}, registered onImageAvailableForImageReader" }
        }
    }

    /** Back beats everything */
    private val bestCameraId: String by lazy {
        val cameraId = cameraManager.cameraIdList.filterNotNull().filter { cameraId ->
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val configs = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            configs.outputFormats.contains(imageFormat)
        }.maxBy { cameraId ->
            when (cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> 10
                CameraCharacteristics.LENS_FACING_FRONT -> 3
                CameraCharacteristics.LENS_FACING_EXTERNAL -> 1
                else -> 0
            }
        }
                ?: throw NoSuchElementException("Unable to find camera supporting format ${imageFormatToName(imageFormat)}.")
        i { "bestCameraId:created $cameraId" }
        cameraId
    }

    private val backgroundThread: HandlerThread by lazy {
        HandlerThread("EZCam").also {
            it.start()
            i { "backgroundThread:created (and started)" }
        }
    }

    private val backgroundHandler: Handler by lazy {
        Handler(backgroundThread.looper).also {
            i { "backgroundHandler:created" }
        }
    }

    /** Write full image captures to disk */
    private val onImageAvailableForImageReader by lazy {
        ImageReader.OnImageAvailableListener {
            i { "onImageAvailableForImageReader" }

            imageReader.acquireLatestImage().use { image: Image ->
                easyImageCont.resume(EasyImage(image))
                image.close()
            }
        }
    }

    /**
     * start the preview, rebuilding the preview request each time
     */
    private suspend fun startPreview() = withContext(Dispatchers.Default) {
        d { "EZCam.startPreview:start" }
        cameraCaptureSession().setRepeatingRequest(captureRequestBuilderForPreview().build(), null, backgroundHandler)
        d { "EZCam.startPreview:end" }
    }

    /**
     * stop the preview
     */
    private suspend fun stopPreview() = withContext(Dispatchers.Default) {
        d { "EZCam.stopPreview (stops repeating capture session)" }
        cameraCaptureSession().stopRepeating()
    }

    /**
     * close the camera definitively
     */
    private suspend fun close() = withContext(Dispatchers.Default) {
        cameraDevice().close()
        previewSurface().release()
        stopBackgroundThread()
    }

    /**
     * take just one picture
     */
    private suspend fun takePicture() = withContext(Dispatchers.Default) {
        when (imageFormat) {
            ImageFormat.JPEG -> captureRequestBuilderForImageReader()
                    .set(CaptureRequest.JPEG_ORIENTATION, cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION))
            ImageFormat.DEPTH16, ImageFormat.DEPTH_POINT_CLOUD -> captureRequestBuilderForImageReader()
                    .set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
        }

        cameraCaptureSession()
                .capture(captureRequestBuilderForImageReader().build(), null, backgroundHandler)
    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
        } catch (e: InterruptedException) {
            Timber.e(e) { "stopBackgroundThread error waiting for background thread" }
        }
    }


    companion object {
        private const val MAX_IMAGES = 4

        private fun imageFormatToName(imageFormat: Int): String = when (imageFormat) {
            ImageFormat.JPEG -> "jpg"
            ImageFormat.DEPTH16 -> "DEPTH16"
            ImageFormat.DEPTH_POINT_CLOUD -> "DEPTH_POINT_CLOUD"
            else -> error("Unsupported image format:$imageFormat")
        }
    }
}


