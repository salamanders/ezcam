package info.benjaminhill.galaxy

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class WritableImage(private val destinationName: String, private val width: Int, private val height: Int) {
    val red = ByteArray(width * height)
    val green = ByteArray(width * height)
    val blue = ByteArray(width * height)
    val lum = ShortArray(width * height)

    fun save(name: String = destinationName) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val data = IntArray(width * height)
        for (i in 0 until data.size) {
            data[i] = ((red[i].toInt() and 0xFF) shl 16) or
                    ((green[i].toInt() and 0xFF) shl 8) or
                    ((blue[i].toInt() and 0xFF) shl 0)
        }
        image.setRGB(0, 0, width, height, data, 0, width)
        ImageIO.write(image, "png", File(Session.STAR_FOLDER, "$name.png"))
    }

    companion object {

        /** Each pixel is chosen from the max(lum) of all frames */
        fun fromMaxLum(others: Collection<Capture>): WritableImage {
            require(others.isNotEmpty())
            println("Generating maxLum from ${others.size}")
            val wi = WritableImage("max_lum", others.first().width, others.first().height)
            others.forEach { other ->
                val otherColor = other.getColorData()
                val otherLum = SourceImage.colorToLum(otherColor)

                for (i in 0 until wi.red.size) {
                    if (wi.lum[i] < otherLum[i]) {
                        wi.lum[i] = otherLum[i]
                        wi.red[i] = otherColor.first[i]
                        wi.green[i] = otherColor.second[i]
                        wi.blue[i] = otherColor.third[i]
                    }
                }
            }
            return wi
        }

        /** Sum pixels, capped at 255 per channel
         * Multiplier is how many x of a base image the brightest lights should reach. (eg. 5.0 is a 5x brightness increase)
         */
        fun fromSum(others: Collection<Capture>, backgroundLight: SourceImage, multiplier: Double = 5.0): WritableImage {
            require(others.isNotEmpty())
            println("Generating fromSum from ${others.size} multiplier:$multiplier")
            val wi = WritableImage("sum", others.first().width, others.first().height)
            val size = wi.red.size
            val backgroundColorBytes = backgroundLight.getColorData()
            val backgroundColors = Triple(IntArray(size), IntArray(size), IntArray(size))
            for (i in 0 until size) {
                backgroundColors.first[i] = backgroundColorBytes.first[i].toInt() and 0xFF
                backgroundColors.second[i] = backgroundColorBytes.second[i].toInt() and 0xFF
                backgroundColors.third[i] = backgroundColorBytes.third[i].toInt() and 0xFF
            }

            val tempSums = Triple(LongArray(size), LongArray(size), LongArray(size))
            others.forEach { cap ->
                val capColor = cap.getColorData()
                for (i in 0 until size) {
                    tempSums.first[i] += Math.max(0L, ((capColor.first[i].toInt() and 0xFF) - backgroundColors.first[i]).toLong())
                    tempSums.second[i] += Math.max(0L, ((capColor.second[i].toInt() and 0xFF) - backgroundColors.second[i]).toLong())
                    tempSums.third[i] += Math.max(0L, ((capColor.third[i].toInt() and 0xFF) - backgroundColors.third[i]).toLong())
                }
            }
            val scale = Math.min(multiplier, others.size.toDouble()) / others.size
            for (i in 0 until size) {
                wi.red[i] = minOf(255, (scale * tempSums.first[i]).toInt()).toByte()
                wi.green[i] = minOf(255, (scale * tempSums.second[i]).toInt()).toByte()
                wi.blue[i] = minOf(255, (scale * tempSums.third[i]).toInt()).toByte()
                wi.lum[i] = SourceImage.colorToLum(wi.red[i], wi.green[i], wi.blue[i])
            }

            return wi
        }

        /** Requires all sources in memory at once */
        fun fromMedian(others: Collection<Capture>): WritableImage {
            require(others.isNotEmpty())
            println("Generating fromMedian from ${others.size}")
            val wi = WritableImage("median", others.first().width, others.first().height)
            val isEven = others.size % 2 == 0
            if (isEven) {
                println("Warning: must do averaging when median and even size: ${others.size}")
            }
            val half = Math.floor((others.size - 1) / 2.0).toInt()
            val otherColors = others.associate { it to it.getColorData() }
            val otherLums = others.associate { it to SourceImage.colorToLum(otherColors[it]!!) }
            println("Median: All image data loaded")
            for (i in 0 until wi.red.size) {
                val sorted = others.sortedBy { otherLums[it]!![i] }
                val halfCapture = sorted[half]
                if (isEven) {
                    val halfCapturePlus = sorted[half + 1]
                    wi.red[i] = byteAvg(otherColors[halfCapture]!!.first[i], otherColors[halfCapturePlus]!!.first[i])
                    wi.green[i] = byteAvg(otherColors[halfCapture]!!.second[i], otherColors[halfCapturePlus]!!.second[i])
                    wi.blue[i] = byteAvg(otherColors[halfCapture]!!.third[i], otherColors[halfCapturePlus]!!.third[i])
                    wi.lum[i] = Math.min(255, (otherLums[halfCapture]!![i] + otherLums[halfCapturePlus]!![i]) / 2).toShort()
                } else {
                    wi.red[i] = otherColors[halfCapture]!!.first[i]
                    wi.green[i] = otherColors[halfCapture]!!.second[i]
                    wi.blue[i] = otherColors[halfCapture]!!.third[i]
                    wi.lum[i] = otherLums[halfCapture]!![i]
                }
            }
            return wi
        }

        private fun byteAvg(vararg others: Byte): Byte = (others.map { it.toInt() and 0xFF }.sum() / others.size).toByte()
    }
}