package info.benjaminhill.depthcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.github.ajalt.timberkt.Timber
import com.github.ajalt.timberkt.e
import com.github.ajalt.timberkt.i
import com.github.ajalt.timberkt.w
import info.benjaminhill.droid.EZPermissionActivity
import info.benjaminhill.droid.rootCause
import info.benjaminhill.ezcam.DepthCam
import info.benjaminhill.ezcam.saveToMediaStore
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter


class MainActivity : EZPermissionActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.plant(Timber.DebugTree())
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private lateinit var depthCam: DepthCam

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            takeDepthPictures()
        }
        return true
    }

    @ExperimentalUnsignedTypes
    @ExperimentalCoroutinesApi
    private fun takeDepthPictures() {
        launch(Dispatchers.Default) {
            depthCam.images
                    .catch { e ->
                        e {
                            val rootCause = e.rootCause()
                            rootCause.printStackTrace()
                            "Error in flow: ${rootCause.localizedMessage}"
                        }
                    }
                    .onStart {
                        i { "Starting capture of burst ($BURST_SIZE) depth images." }
                    }
                    .onCompletion {
                        runOnUiThread {
                            Toast.makeText(applicationContext, "Captured $BURST_SIZE Frames", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .take(BURST_SIZE)
                    .flowOn(Dispatchers.Default)
                    .collectIndexed { index, imageData ->
                        i { "Processing image $index: ${imageData.width}x${imageData.height} size:${imageData.plane.size}" }
                        runOnUiThread {
                            Toast.makeText(applicationContext, "$index/$BURST_SIZE", Toast.LENGTH_SHORT).show()
                        }
                        val (points, bitmap) = depthCam.depthToCloudAndBitmap((imageData))
                        bitmap.saveToMediaStore(applicationContext)
                        points.saveToDownloads()
                        delay(1_000)
                    }
            i { "Burst finished." }
        }
    }

    @ExperimentalUnsignedTypes
    @SuppressLint("MissingPermission")
    @ExperimentalCoroutinesApi
    override fun onResume() {
        super.onResume()
        if (!hasAllRequiredPermissions()) {
            w { "Halted onResume because don't yet have the required permissions." }
            return
        }

        if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            error("Lint permission check XXX")
        }
        depthCam = DepthCam(this@MainActivity, textureView)

    }

    private fun List<Triple<Float, Float, Float>>.saveToDownloads() {
        val file = File(applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "pointcloud_${System.currentTimeMillis()}.asc")
        i { "Saving ASC to ${file.absolutePath}" }
        PrintWriter(file).use { pw ->
            forEach { (x, y, z) ->
                pw.println("${x.str} ${y.str} ${z.str}")
            }
        }
        val values = ContentValues()
        values.put(MediaStore.Files.FileColumns.TITLE, file.name)
        values.put(MediaStore.Files.FileColumns.DISPLAY_NAME, file.nameWithoutExtension)
        values.put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
        values.put(MediaStore.Files.FileColumns.SIZE, file.length())
        values.put(MediaStore.Files.FileColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + "DepthCam")

        val database = applicationContext.contentResolver!!
        database.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        i { "Informed contentResolver manager about '${file.name}'" }
    }

    companion object {
        const val BURST_SIZE = 10
        internal val Float.str: String
            get() = "%.5f".format(this)
    }
}





