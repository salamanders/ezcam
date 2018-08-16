package info.benjaminhill.deconcamera

import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.WindowManager
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch

class MainActivity : EZPermissionActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        if (!hasAllRequiredPermissions()) {
            Log.w(TAG, "Halted onResume because don't yet have the required permissions.")
            return
        }

        launch(UI) {
            val cam = EZCam(this@MainActivity, textureView)

            /*
            cam.setCaptureSetting(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
            cam.setCaptureSetting(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_NIGHT)
            cam.setCaptureSetting(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            cam.setCaptureSetting(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_HIGH_QUALITY)
            // setCaptureSetting(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
            cam.setCaptureSetting(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)

            cam.setCaptureSetting(CaptureRequest.JPEG_QUALITY, 99.toByte())

            cam.setCaptureSetting(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            cam.setCaptureSetting(CaptureRequest.SENSOR_EXPOSURE_TIME, (2.0 * 1_000_000_000L).toLong()) // ns
            cam.setCaptureSetting(CaptureRequest.SENSOR_SENSITIVITY, 400) // iso

            cam.setCaptureSetting(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            cam.setCaptureSetting(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
            */

            Log.i(TAG, "Finished construction, now starting preview.")
            cam.startPreview()
            Log.i(TAG, "Finished starting preview")

            val lotsOfPictures = SetInterval(5_000) {
                launch(UI) {
                    cam.takePicture()
                }
            }
        }
    }


    override fun onPause() {
        super.onPause()
        //cam.stopPreview()
        Log.i(TAG, "onPause - EZCam.stopPreview")
    }

    override fun onDestroy() {
        //cam.close()
        super.onDestroy()
        Log.i(TAG, "onDestroy - EZCam.close")
    }

    companion object {
        const val TAG = "ezcam"
    }
}


/** Repeating callback that starts in a running state */
class SetInterval(private val delayMs: Long = 1_000L, action: () -> Unit) {
    val handler = Handler()
    private val internalRunnable = object : Runnable {
        override fun run() {
            Log.i(MainActivity.TAG, "SetInterval.run")
            handler.postDelayed(this, delayMs)
            action()
        }
    }

    init {
        resume()
    }

    fun pause() {
        Log.i(MainActivity.TAG, "SetInterval.pause")
        handler.removeCallbacks(internalRunnable)
    }

    fun resume() {
        Log.i(MainActivity.TAG, "SetInterval.resume")
        handler.postDelayed(internalRunnable, delayMs)
    }
}