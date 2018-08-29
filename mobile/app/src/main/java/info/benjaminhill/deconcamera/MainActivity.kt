package info.benjaminhill.deconcamera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch

class MainActivity : EZPermissionActivity() {
    private lateinit var cam: EZCam
    private lateinit var lotsOfPictures: SetInterval
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
            cam = EZCam(this@MainActivity, textureView)

            cam.setCaptureSetting(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
            cam.setCaptureSetting(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_NIGHT)
            cam.setCaptureSetting(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            cam.setCaptureSetting(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_HIGH_QUALITY)
            // setCaptureSetting(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
            cam.setCaptureSetting(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)

            cam.setCaptureSetting(CaptureRequest.JPEG_QUALITY, 99.toByte())

            cam.setCaptureSetting(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            //cam.setCaptureSetting(CaptureRequest.SENSOR_EXPOSURE_TIME, (4.0 * 1_000_000_000L).toLong()) // ns
            cam.setCaptureSettingMaxExposure()
            cam.setCaptureSetting(CaptureRequest.SENSOR_SENSITIVITY, 800) // iso

            cam.setCaptureSetting(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            cam.setFocusDistanceMax()

            Log.i(TAG, "Finished construction, now starting preview.")
            cam.startPreview()
            Log.i(TAG, "Finished starting preview")

            lotsOfPictures = SetInterval(5_000) {
                launch(UI) {
                    cam.takePicture()
                }
            }
        }
    }


    override fun onPause() {
        super.onPause()
        Log.i(TAG, "onPause - EZCam.stopPreview")
        launch(UI) { cam.stopPreview() }

    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy - EZCam.close")
        lotsOfPictures.pause()
        launch(UI) { cam.close() }
    }

    companion object {
        const val TAG = "ezcam"
    }
}


