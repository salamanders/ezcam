package info.benjaminhill.galaxy

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.google.gson.GsonBuilder
import java.awt.image.*
import java.io.File
import java.io.Serializable
import javax.imageio.ImageIO

/** Wrapper around any source image, caches fun stuff like dimensions.
 * Extend to support features within the image (like stars)
 */
open class SourceImage(fileName: String) : Serializable {

    protected val sourceImageFile: File = when {
        File(Session.STAR_FOLDER, fileName).canRead() -> File(Session.STAR_FOLDER, fileName)
        File(Session.STAR_FOLDER, "$fileName.png").canRead() -> File(Session.STAR_FOLDER, "$fileName.png")
        File(Session.STAR_FOLDER, "$fileName.jpg").canRead() -> File(Session.STAR_FOLDER, "$fileName.jpg")
        else -> throw Exception("Unable to find any format source image for $fileName")
    }

    protected val metadataFile: File = File(Session.STAR_FOLDER, "${sourceImageFile.nameWithoutExtension}.json")

    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var ts: Long = 0
        private set
    var exposure: Double = 0.0
        private set

    protected fun xy2i(x: Int, y: Int): Int = y * width + x

    fun getName(): String = sourceImageFile.nameWithoutExtension

    private fun readFromSource() {
        val colorImage: BufferedImage = ImageIO.read(sourceImageFile)
        require(colorImage.alphaRaster == null)
        // There are faster ways to do this
        width = colorImage.width
        height = colorImage.height

        val directory = ImageMetadataReader.readMetadata(sourceImageFile).getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)!!
        ts = (directory.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
                ?: directory.getDate(ExifSubIFDDirectory.TAG_DATETIME)).time
        exposure = directory.getRational(ExifSubIFDDirectory.TAG_EXPOSURE_TIME).let {
            1.0 * (it.numerator / it.denominator)
        }
    }

    /** Doesn't store this internally because would chew up memory too fast */
    open fun getColorData(): Triple<ByteArray, ByteArray, ByteArray> = getColorData(ImageIO.read(sourceImageFile))

    fun getLum(): ShortArray = colorToLum(getColorData())

    open fun loadMetadata() {
        if (!metadataFile.canRead()) {
            readFromSource()
            saveMetadata()
        }
        require(metadataFile.canRead())
        metadataFile.bufferedReader().use {
            val loadedCapture = GSON.fromJson(it, this::class.java)!!
            ts = loadedCapture.ts
            exposure = loadedCapture.exposure
            width = loadedCapture.width
            height = loadedCapture.height
        }
    }

    open fun saveMetadata() {
        metadataFile.printWriter().use {
            GSON.toJson(this, it)
        }
    }

    override fun toString(): String = "Capture(${sourceImageFile.name}, $width x $height)"

    companion object {
        val GSON = GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create()!!

        fun getColorData(colorImage: BufferedImage): Triple<ByteArray, ByteArray, ByteArray> {
            // require(colorImage.alphaRaster == null)
            val numChannels = if (colorImage.alphaRaster == null) 3 else 4
            var pixelLocation = 0
            val red = ByteArray(colorImage.width * colorImage.height)
            val green = ByteArray(colorImage.width * colorImage.height)
            val blue = ByteArray(colorImage.width * colorImage.height)
            (colorImage.raster.dataBuffer as DataBufferByte).data.asIterable().chunked(numChannels).forEach { channels ->
                blue[pixelLocation] = channels[0]
                green[pixelLocation] = channels[1]
                red[pixelLocation] = channels[2]
                pixelLocation++
            }
            return Triple(red, green, blue)
        }

        fun blur(colorImage: BufferedImage): BufferedImage {
            val matrix = FloatArray(9) { 0.111111f }
            val op: BufferedImageOp = ConvolveOp(Kernel(3, 3, matrix), ConvolveOp.EDGE_NO_OP, null)
            val destImage = BufferedImage(colorImage.colorModel, colorImage.colorModel.createCompatibleWritableRaster(colorImage.width, colorImage.height), colorImage.colorModel.isAlphaPremultiplied, null)
            return op.filter(colorImage, destImage)
        }


        fun colorToLum(rgb: Triple<ByteArray, ByteArray, ByteArray>): ShortArray {
            val (red, green, blue) = rgb
            val lum = ShortArray(red.size)
            for (i in 0 until red.size) {
                lum[i] = colorToLum(red[i], green[i], blue[i])
            }
            return lum
        }

        fun colorToLum(red: Byte, green: Byte, blue: Byte): Short = (0.299 * (red.toInt() and 0xFF) +
                0.587 * (green.toInt() and 0xFF) +
                0.114 * (blue.toInt() and 0xFF)).toShort()
    }
}