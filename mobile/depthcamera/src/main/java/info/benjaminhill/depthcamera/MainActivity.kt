package info.benjaminhill.depthcamera

import android.annotation.SuppressLint
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
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlin.experimental.and
import kotlin.system.measureTimeMillis


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
            i { "Creating FlowCam, pausing 2 seconds" }
            delay(2_000)

            val images = mutableListOf<ImageData>()
            val ms = measureTimeMillis {
                FlowCam(this@MainActivity, textureView, ImageFormat.DEPTH16)
                        .flow()
                        .catch { e -> e { "Error in flow: ${e.localizedMessage}" } }
                        .take(10)
                        .toList(images)
            }

            i { "Collected ${images.size} images in ${ms}ms.  Saving." }

            images.forEach { imageData ->
                i { "Processing image: ${imageData.width}x${imageData.height} size:${imageData.plane.size}" }
                depthImageToBitmap(imageData).saveToMediaStore(applicationContext)
            }
        }
    }
}

@ExperimentalUnsignedTypes
fun depthImageToBitmap(image: ImageData): Bitmap {

    val depths = ShortArray(image.plane.size / 2) { i ->
        (image.plane[i * 2].toUByte().toInt() + (image.plane[(i * 2) + 1].toInt() shl 8)).toShort()
    }.map { sample ->
        val range = (sample and 0x1FFF)
        val depthConfidence = (sample shr 13 and 0x7)
        val depthConfidence2 = if (depthConfidence.toInt() == 0) 1f else (depthConfidence - 1) / 7f
        Pair(range, depthConfidence2)
    }

    return Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)!!.apply {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val (rangeMm, confidence) = depths[y * image.width + x]
                val red = rangeMm / 256
                val green = rangeMm % 256
                val blue = (confidence * 255.0).toInt()
                setPixel(x, y, Color.rgb(red, green, blue))
            }
        }
    }
}


infix fun Short.shr(shift: Int): Short = (this.toInt() shr shift).toShort()