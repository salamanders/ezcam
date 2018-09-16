package info.benjaminhill.deconcamera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.experimental.suspendCoroutine

@SuppressLint("MissingPermission")
/**
 * camera2 API deconstructed - done through lazy loads
 * To use, parent class should extend CoroutineScope
 * see https://github.com/Kotlin/kotlinx.coroutines/blob/master/ui/coroutines-guide-ui.md
 * Then wrap calls in `launch { cam.takePicture() }`
 * from https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.experimental/launch.html
 */
class EZCam
@RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE])
constructor(private val context: Activity, private val previewTextureView: TextureView) {

    /** The surface that the preview gets drawn on */
    private val previewSurface = LazySuspend {
        Timber.d("EZCam.previewSurface:start")
        if (previewTextureView.isAvailable) {
            Surface(previewTextureView.surfaceTexture).also {
                Timber.i("Created previewSurface directly")
            }
        } else {
            suspendCoroutine { cont ->
                previewTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    @SuppressLint("Recycle")
                    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                        cont.resume(Surface(surfaceTexture).also {
                            Timber.i("Created previewSurface through a surfaceTextureListener")
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
        Timber.d("cameraManager.openCamera onOpened, cameraDevice is now ready.")
        suspendCoroutine { cont ->
            cameraManager.openCamera(bestCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) = cont.resume(camera).also {
                    Timber.i("cameraManager.openCamera onOpened, cameraDevice is now ready.")
                }

                override fun onDisconnected(camera: CameraDevice) = cont.resumeWithException(Exception("Problem with cameraManager.openCamera onDisconnected")).also {
                    Timber.w("camera onDisconnected: Camera device is no longer available for use.")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    when (error) {
                        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> Timber.w("CameraDevice.StateCallback: Camera device has encountered a fatal error.")
                        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> Timber.w("CameraDevice.StateCallback: Camera device could not be opened due to a device policy.")
                        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> Timber.w("CameraDevice.StateCallback: Camera device is in use already.")
                        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> Timber.w("CameraDevice.StateCallback: Camera service has encountered a fatal error.")
                        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> Timber.w("CameraDevice.StateCallback: Camera device could not be opened because there are too many other open camera devices.")
                    }
                    try {
                        cont.resumeWithException(Exception("openCamera onError $error"))
                    } catch (e: IllegalStateException) {
                        Timber.w("Swallowing resumeWithException because not the first resume.")
                    }

                }
            }, backgroundHandler)
        }
    }


    /** A fully configured capture session */
    private val cameraCaptureSession = LazySuspend<CameraCaptureSession> {
        Timber.d("EZCam.cameraCaptureSession:start")
        val cd = cameraDevice()
        val rs = previewSurface()
        suspendCoroutine { cont ->
            cd.createCaptureSession(Arrays.asList(rs, imageReaderJPEG.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) = cont.resume(session).also {
                    Timber.i("Created cameraCaptureSession through createCaptureSession.onConfigured")
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    val e = Exception("createCaptureSession.onConfigureFailed")
                    Timber.e(e, "onConfigureFailed: Could not configure capture session.")
                    cont.resumeWithException(e)
                }
            }, backgroundHandler)
        }
    }

    /** Builder set to preview mode */
    private val captureRequestBuilderForPreview = LazySuspend<CaptureRequest.Builder> {
        cameraDevice().createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).also {
            it.addTarget(previewSurface())
            Timber.i("captureRequestBuilderForPreview:created")
        }
    }


    /** Builder set to higher quality capture mode */
    private val captureRequestBuilderForImageReader = LazySuspend<CaptureRequest.Builder> {
        cameraDevice().createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).also {
            it.addTarget(imageReaderJPEG.surface)
            Timber.i("captureRequestBuilderForImageReader:created")
        }
    }

    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE).also {
            Timber.i("cameraManager:created")
        } as CameraManager
    }

    private val imageSizeForImageReader: Size by lazy {
        cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(ImageFormat.JPEG).maxBy {
            it.width * it.height
        }!!.also {
            Timber.i("Found max size for the camera JPEG: $it")
        }
    }

    private val cameraCharacteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(bestCameraId).also {
            Timber.i("cameraCharacteristics:created for camera $bestCameraId")
        }
    }

    private val imageReaderJPEG: ImageReader by lazy {
        // TODO: Previews should be smaller res
        ImageReader.newInstance(imageSizeForImageReader.width, imageSizeForImageReader.height, ImageFormat.JPEG, 3).also {
            it.setOnImageAvailableListener(onImageAvailableForImageReader, backgroundHandler)
            Timber.i("imageReaderJPEG:created maxImages ${it.maxImages}, registered onImageAvailableForImageReader")
        }
    }

    /** Back beats everything */
    private val bestCameraId: String by lazy {
        val cameraId = cameraManager.cameraIdList.filterNotNull().maxBy { cameraId ->
            when (cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> 3
                CameraCharacteristics.LENS_FACING_FRONT -> 2
                CameraCharacteristics.LENS_FACING_EXTERNAL -> 1
                else -> 0
            }
        } ?: throw NoSuchElementException("Unable to find camera")
        Timber.i("bestCameraId:created $cameraId")
        cameraId
    }

    private val backgroundThread: HandlerThread by lazy {
        HandlerThread("EZCam").also {
            it.start()
            Timber.i("backgroundThread:created (and started)")
        }
    }

    private val backgroundHandler: Handler by lazy {
        Handler(backgroundThread.looper).also {
            Timber.i("backgroundHandler:created")
        }
    }

    /** Write full image captures to disk */
    private val onImageAvailableForImageReader by lazy {
        ImageReader.OnImageAvailableListener {
            Timber.i("onImageAvailableForImageReader")

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                throw IllegalStateException("You don't have the required permission WRITE_EXTERNAL_STORAGE, try guarding with EZPermission.")
            }

            val albumFolder = File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DCIM), "/Camera/decon")
            albumFolder.mkdirs()
            val imageFile = File(albumFolder, "image_${SDF.format(Date())}.jpg")
            imageReaderJPEG.acquireLatestImage().use { image ->
                saveImage(image, imageFile)
            }

            MediaScannerConnection.scanFile(context, arrayOf(imageFile.toString()), arrayOf("image/jpeg")) { filePath, u ->
                Timber.i("scanFile finished $filePath $u")
            }
        }
    }

    /**
     * Set CaptureRequest parameters e.g. setCaptureSetting(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
     */
    suspend fun <T> setCaptureSetting(key: CaptureRequest.Key<T>, value: T) = withContext(Dispatchers.Default) {
        // captureRequestBuilderForPreview().set(key, value) Long exposure makes preview sad
        captureRequestBuilderForImageReader().set(key, value)
    }

    suspend fun setCaptureSettingMaxExposure() = withContext(Dispatchers.Default) {
        cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.upper?.let { maxExposure ->
            setCaptureSetting(CaptureRequest.SENSOR_EXPOSURE_TIME, maxExposure)
            Timber.i("Set exposure to max ${maxExposure / 1_000_000_000.0} seconds")
        }
    }

    suspend fun setFocusDistanceMax() = withContext(Dispatchers.Default) {
        val hyperfocalDistance = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)
                ?: 0.0f.also {
                    Timber.w("Hyperfocal distance not available")
                }

        // div 2 because I think it might help hedging towards infinity
        setCaptureSetting(CaptureRequest.LENS_FOCUS_DISTANCE, hyperfocalDistance / 2) //
        Timber.i("Set focus to hyperfocal diopters: $hyperfocalDistance / 2")
    }


    /**
     * start the preview, rebuilding the preview request each time
     */
    suspend fun startPreview() = withContext(Dispatchers.Default) {
        Timber.d("EZCam.startPreview:start")
        cameraCaptureSession().setRepeatingRequest(captureRequestBuilderForPreview().build(), null, backgroundHandler)
        Timber.d("EZCam.startPreview:end")
    }

    /**
     * stop the preview
     */
    suspend fun stopPreview() = withContext(Dispatchers.Default) {
        Timber.d("EZCam.stopPreview (stops repeating capture session)")
        cameraCaptureSession().stopRepeating()
    }

    /**
     * close the camera definitively
     */
    suspend fun close() = withContext(Dispatchers.Default) {
        cameraDevice().close()
        previewSurface().release()
        stopBackgroundThread()
    }

    /**
     * take just one picture
     */
    suspend fun takePicture() = withContext(Dispatchers.Default) {
        captureRequestBuilderForImageReader().set(CaptureRequest.JPEG_ORIENTATION, cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION))
        cameraCaptureSession().capture(captureRequestBuilderForImageReader().build(), null, backgroundHandler)
    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
        } catch (e: InterruptedException) {
            Timber.e(e, "stopBackgroundThread error waiting for background thread")
        }
    }

    companion object {
        private val SDF = SimpleDateFormat("yyyyMMddhhmmssSSS", Locale.US)

        /**
         * Save image to storage
         * @param image Image object got from onPicture() callback of EZCamCallback
         * @param file File where image is going to be written
         * @return File object pointing to the file uri, null if file already exist
         */
        private fun saveImage(image: Image, file: File) {
            require(!file.exists()) { "Image target file $file must not exist." }
            val buffer = image.planes[0].buffer!!
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            file.writeBytes(bytes)
            Timber.i("Finished writing image to $file: ${file.length()}")
        }
    }
}
