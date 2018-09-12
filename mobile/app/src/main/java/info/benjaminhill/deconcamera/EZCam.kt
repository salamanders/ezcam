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
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.app.ActivityCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.experimental.suspendCoroutine

@SuppressLint("MissingPermission")
/**
 * camera2 API deconstructed - done through lazy loads
 * Wrap calls in `GlobalScope.launch(Dispatchers.Main) { cam.takePicture() }`
 * from https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.experimental/launch.html
 */
class EZCam(private val context: Activity, private val previewTextureView: TextureView) {

    /** The surface that the preview gets drawn on */
    private val previewSurface = LazySuspend {
        Log.d(TAG, "EZCam.previewSurface:start")
        if (previewTextureView.isAvailable) {
            Surface(previewTextureView.surfaceTexture).also {
                Log.i(TAG, "Created previewSurface directly")
            }
        } else {
            suspendCoroutine { cont ->
                previewTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                        cont.resume(Surface(surfaceTexture).also {
                            Log.i(TAG, "Created previewSurface through a surfaceTextureListener")
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
        Log.d(TAG, "cameraManager.openCamera onOpened, cameraDevice is now ready.")
        suspendCoroutine { cont ->
            cameraManager.openCamera(bestCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) = cont.resume(camera).also {
                    Log.i(TAG, "cameraManager.openCamera onOpened, cameraDevice is now ready.")
                }

                override fun onDisconnected(camera: CameraDevice) = cont.resumeWithException(Exception("Problem with cameraManager.openCamera onDisconnected")).also {
                    Log.w(TAG, "camera onDisconnected: Camera device is no longer available for use.")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    when (error) {
                        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> Log.w(TAG, "CameraDevice.StateCallback: Camera device has encountered a fatal error.")
                        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> Log.w(TAG, "CameraDevice.StateCallback: Camera device could not be opened due to a device policy.")
                        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> Log.w(TAG, "CameraDevice.StateCallback: Camera device is in use already.")
                        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> Log.w(TAG, "CameraDevice.StateCallback: Camera service has encountered a fatal error.")
                        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> Log.w(TAG, "CameraDevice.StateCallback: Camera device could not be opened because there are too many other open camera devices.")
                    }
                    try {
                        cont.resumeWithException(Exception("openCamera onError $error"))
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Swallowing resumeWithException because not the first resume.")
                    }

                }
            }, backgroundHandler)
        }
    }


    /** A fully configured capture session */
    private val cameraCaptureSession = LazySuspend<CameraCaptureSession> {
        Log.d(TAG, "EZCam.cameraCaptureSession:start")
        val cd = cameraDevice()
        val rs = previewSurface()
        suspendCoroutine { cont ->
            cd.createCaptureSession(Arrays.asList(rs, imageReaderJPEG.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) = cont.resume(session).also {
                    Log.i(TAG, "Created cameraCaptureSession through createCaptureSession.onConfigured")
                }

                override fun onConfigureFailed(session: CameraCaptureSession) = cont.resumeWithException(Exception("createCaptureSession.onConfigureFailed")).also {
                    Log.e(TAG, "onConfigureFailed: Could not configure capture session.")
                }
            }, backgroundHandler)
        }
    }

    /** Builder set to preview mode */
    private val captureRequestBuilderForPreview = LazySuspend<CaptureRequest.Builder> {
        cameraDevice().createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).also {
            it.addTarget(previewSurface())
            Log.i(TAG, "captureRequestBuilderForPreview:created")
        }
    }


    /** Builder set to higher quality capture mode */
    private val captureRequestBuilderForImageReader = LazySuspend<CaptureRequest.Builder> {
        cameraDevice().createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).also {
            it.addTarget(imageReaderJPEG.surface)
            Log.i(TAG, "captureRequestBuilderForImageReader:created")
        }
    }

    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE).also {
            Log.i(TAG, "cameraManager:created")
        } as CameraManager
    }

    private val imageSizeForImageReader: Size by lazy {
        cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(ImageFormat.JPEG).maxBy {
            it.width * it.height
        }!!.also {
            Log.i(TAG, "Found max size for the camera JPEG: $it")
        }
    }

    private val cameraCharacteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(bestCameraId).also {
            Log.i(TAG, "cameraCharacteristics:created for camera $bestCameraId")
        }
    }

    private val imageReaderJPEG: ImageReader by lazy {
        // TODO: Previews should be smaller res
        ImageReader.newInstance(imageSizeForImageReader.width, imageSizeForImageReader.height, ImageFormat.JPEG, 3).also {
            it.setOnImageAvailableListener(onImageAvailableForImageReader, backgroundHandler)
            Log.i(TAG, "imageReaderJPEG:created maxImages ${it.maxImages}, registered onImageAvailableForImageReader")
        }
    }

    /** Back beats everything */
    private val bestCameraId: String by lazy {
        cameraManager.cameraIdList.filterNotNull().maxBy { cameraId ->
            when (cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> 3
                CameraCharacteristics.LENS_FACING_FRONT -> 2
                CameraCharacteristics.LENS_FACING_EXTERNAL -> 1
                else -> 0
            }
        }!!.also {
            Log.i(TAG, "bestCameraId:created $it")
        }
    }

    private val backgroundThread: HandlerThread by lazy {
        HandlerThread("EZCam").also {
            it.start()
            Log.i(TAG, "backgroundThread:created (and started)")
        }
    }

    private val backgroundHandler: Handler by lazy {
        Handler(backgroundThread.looper).also {
            Log.i(TAG, "backgroundHandler:created")
        }
    }

    /** Write full image captures to disk */
    private val onImageAvailableForImageReader by lazy {
        ImageReader.OnImageAvailableListener {
            Log.i(EZCam.TAG, "onImageAvailableForImageReader")

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                throw IllegalStateException("You don't have the required permission WRITE_EXTERNAL_STORAGE, try guarding with EZPermission.")
            }

            val albumFolder = File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DCIM), "/Camera/" + EZCam.TAG)
            albumFolder.mkdirs()
            val imageFile = File(albumFolder, "image_${SDF.format(Date())}.jpg")
            saveImage(imageReaderJPEG.acquireLatestImage(), imageFile)

            MediaScannerConnection.scanFile(context, arrayOf(imageFile.toString()), arrayOf("image/jpeg")) { filePath, u ->
                Log.i(EZCam.TAG, "scanFile finished $filePath $u")
            }
        }
    }

    /**
     * Set CaptureRequest parameters e.g. setCaptureSetting(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
     */
    suspend fun <T> setCaptureSetting(key: CaptureRequest.Key<T>, value: T) {
        // captureRequestBuilderForPreview().set(key, value) Long exposure makes preview sad
        captureRequestBuilderForImageReader().set(key, value)
    }

    suspend fun setCaptureSettingMaxExposure() {
        cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.upper?.let { maxExposure ->
            setCaptureSetting(CaptureRequest.SENSOR_EXPOSURE_TIME, maxExposure)
            Log.i(TAG, "Set exposure to max ${maxExposure / 1_000_000_000.0} seconds")
        }
    }

    suspend fun setFocusDistanceMax() {
        val hyperfocalDistance = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)
                ?: 0.0f.also {
                    Log.w(TAG, "Hyperfocal distance not available")
                }

        // ➗2 because I think it might help hedging towards infinity
        setCaptureSetting(CaptureRequest.LENS_FOCUS_DISTANCE, hyperfocalDistance / 2) //
        Log.i(TAG, "Set focus to hyperfocal diopters: $hyperfocalDistance / 2")
    }


    /**
     * start the preview, rebuilding the preview request each time
     */
    suspend fun startPreview() {
        Log.d(TAG, "EZCam.startPreview:start")
        cameraCaptureSession().setRepeatingRequest(captureRequestBuilderForPreview().build(), null, backgroundHandler)
        Log.d(TAG, "EZCam.startPreview:end")
    }

    /**
     * stop the preview
     */
    suspend fun stopPreview() {
        cameraCaptureSession().stopRepeating()
    }

    /**
     * close the camera definitively
     */
    suspend fun close() {
        cameraDevice().close()
        previewSurface().release()
        stopBackgroundThread()
    }

    /**
     * take just one picture
     */
    suspend fun takePicture() {
        captureRequestBuilderForImageReader().set(CaptureRequest.JPEG_ORIENTATION, cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION))
        cameraCaptureSession().capture(captureRequestBuilderForImageReader().build(), null, backgroundHandler)
    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "stopBackgroundThread error waiting for background thread", e)
        }
    }

    companion object {
        const val TAG = "ezcam"
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
            val output = FileOutputStream(file)
            output.write(bytes)
            image.close()
            output.close()
            Log.i(EZCam.TAG, "Finished writing image to $file: ${file.length()}")
        }
    }
}
