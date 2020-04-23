package info.benjaminhill.depthcamera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.os.Bundle
import android.view.WindowManager
import com.github.ajalt.timberkt.Timber
import com.github.ajalt.timberkt.e
import com.github.ajalt.timberkt.i
import com.github.ajalt.timberkt.w
import info.benjaminhill.ezcam.EZPermissionActivity
import info.benjaminhill.ezcam.FlowCam
import info.benjaminhill.ezcam.ImageData
import info.benjaminhill.ezcam.saveToMediaStore
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlin.experimental.and


class MainActivity : EZPermissionActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.plant(Timber.DebugTree())
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

        launch {
            i { "Creating FlowCam" }
            FlowCam(this@MainActivity, textureView, ImageFormat.DEPTH16)
                    .flow()
                    .catch { e -> e { "Error in flow: ${e.localizedMessage}" } }
                    .take(3)
                    .collect { imageData ->
                        i { "Got image back from Flow: ${imageData.width}x${imageData.height} size:${imageData.plane.size}" }
                        processDepthImage(imageData, applicationContext)
                        delay(2_000)
                    }
        }
    }
}

@ExperimentalUnsignedTypes
fun processDepthImage(image: ImageData, context: Context) {

    val depths = ShortArray(image.plane.size / 2) { i ->
        (image.plane[i * 2].toUByte().toInt() + (image.plane[(i * 2) + 1].toInt() shl 8)).toShort()
    }.map { sample ->
        val range = (sample and 0x1FFF)
        val depthConfidence = (sample shr 13 and 0x7)
        val depthConfidence2 = if (depthConfidence.toInt() == 0) 1f else (depthConfidence - 1) / 7f
        Pair(range, depthConfidence2)
    }

    val depthResult = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)!!.apply {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val (range, conf) = depths[y * image.width + x]
                val red = range / 256
                val green = range % 256
                val blue = (conf * 255.0).toInt()
                setPixel(x, y, Color.rgb(red, green, blue))
            }
        }
    }
    depthResult.saveToMediaStore(context)
}


infix fun Short.shr(shift: Int): Short = (this.toInt() shr shift).toShort()