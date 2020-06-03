package info.benjaminhill.ezcam

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.view.TextureView
import com.github.ajalt.timberkt.i
import kotlin.experimental.and

@SuppressLint("MissingPermission")
class DepthCam(
        context: Activity,
        previewTextureView: TextureView,
) : FlowCam(
        context,
        previewTextureView,
        ImageFormat.DEPTH16
) {

    /**
     * (x, y) coordinates to (xd, yd) undistorted locations
     */
    private val depth16UndistortionMap: Array<Array<Pair<Float, Float>>> by lazy {
        i { " Generating distortion map" }

        val (focalLengthX, focalLengthY, principalPointX, principalPointY) =
                cameraCharacteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)!!
                        .map { it * CAMERA_TO_DEPTH_SCALE }
        val (distortionK1, distortionK2, distortionK3, distortionP1, distortionP2) =
                cameraCharacteristics.get(CameraCharacteristics.LENS_DISTORTION)!!

        Array(IR_CAMERA_HEIGHT) { y ->
            Array(IR_CAMERA_WIDTH) { x ->
                // Convert to normalized homogeneous coordinates.
                val xf = (x.toFloat() - principalPointX) / focalLengthX
                val yf = (y.toFloat() - principalPointY) / focalLengthY
                val xx = xf * xf
                val xy = xf * yf
                val yy = yf * yf
                val r2 = xx + yy
                val r4 = r2 * r2
                val r6 = r4 * r2
                val radialDistortion = 1f + distortionK1 * r2 + distortionK2 * r4 + distortionK3 * r6
                val tangentialDistortionX = 2f * distortionP1 * xy + distortionP2 * (r2 + 2f * xx)
                val tangentialDistortionY = 2f * distortionP2 * xy + distortionP1 * (r2 + 2f * yy)
                Pair(xf * radialDistortion + tangentialDistortionX, yf * radialDistortion + tangentialDistortionY)
            }
        }.also {
            i { "Generated DEPTH16 Undistortion map" }
        }
    }

    @ExperimentalUnsignedTypes
    fun depthToCloudAndBitmap(image: EasyImage): Pair<List<Triple<Float, Float, Float>>, Bitmap> {

        i { "Converting DEPTH16 Image to Undistorted Depth Image: ($image.width,$image.height)" }

        // Depth and Confidence, both as 0.0..1.0
        val depths = FloatArray(image.plane.size / 2)
        val confidences = FloatArray(depths.size)

        for (i in depths.indices) {
            val sample = (image.plane[i * 2].toUByte().toInt() + (image.plane[(i * 2) + 1].toInt() shl 8)).toShort()
            depths[i] = ((sample and 0x1FFF) / MAX_VISIBLE_DEPTH_MM).coerceIn(0f, 1f)
            confidences[i] = (sample shr 13 and 0x7).let {
                if (it.toInt() == 0) 1f else (it - 1) / 7f
            }
        }

        val points = mutableListOf<Triple<Float, Float, Float>>()
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)!!

        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val range = depths[y * image.width + x]
                val confidence = confidences[y * image.width + x]

                // Ignore far away stuff.
                if (range > .5f) {
                    continue
                }
                if (confidence < .5f) {
                    continue
                }

                val (undistortedX, undistortedY) = depth16UndistortionMap[y][x] * range
                points.add(Triple(undistortedX, undistortedY, range))

                val red = ((range * MAX_VISIBLE_DEPTH_MM) / 256).toInt()
                val green = ((range * MAX_VISIBLE_DEPTH_MM) % 256).toInt()
                val blue = (confidence * 255.0).toInt()
                bitmap.setPixel(x, y, Color.rgb(red, green, blue))
            }
        }
        return Pair(points, bitmap)
    }

    companion object {
        private const val IR_CAMERA_WIDTH = 640
        private const val IR_CAMERA_HEIGHT = 480
        private const val CAMERA_TO_DEPTH_SCALE = 0.195f
        private const val MAX_VISIBLE_DEPTH_MM = 1000f
        infix fun Short.shr(shift: Int): Short = (this.toInt() shr shift).toShort()

        internal operator fun Pair<Float, Float>.times(other: Float) = Pair(first * other, second * other)
    }
}