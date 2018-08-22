package info.benjaminhill.galaxy

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifSubIFDDirectory
import javafx.collections.ObservableArray
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.File
import javax.imageio.ImageIO
import kotlin.properties.Delegates


/**
 * Load an image into memory as rgb byte arrays for ease of manipulation.  Can be saved as a png.
 */
class Frame constructor(val width: Int, val height: Int, private val imageFileName: String = "out") {
    val red = ByteArray(width * height)
    val green = ByteArray(width * height)
    val blue = ByteArray(width * height)
    val lum = ShortArray(width * height)
    private var ts: Long = 0
    private var exposure: Double = 0.0

    companion object {
        fun load(imageFile: File): Frame {
            val colorImage: BufferedImage = ImageIO.read(imageFile)
            val f = Frame(colorImage.width, colorImage.height, imageFile.name)
            require(colorImage.alphaRaster == null)
            var pixelLocation = 0
            (colorImage.raster.dataBuffer as DataBufferByte).data.asIterable().chunked(3).forEach { channels ->
                f.blue[pixelLocation] = channels[0]
                f.green[pixelLocation] = channels[1]
                f.red[pixelLocation] = channels[2]
                pixelLocation++
            }
            val directory = ImageMetadataReader.readMetadata(imageFile).getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)!!
            f.ts = (directory.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
                    ?: directory.getDate(ExifSubIFDDirectory.TAG_DATETIME)).time
            f.exposure = directory.getRational(ExifSubIFDDirectory.TAG_EXPOSURE_TIME).let {
                1.0 * (it.numerator / it.denominator)
            }
            f.updateLums()
            return f
        }
    }

    fun updateLums() {
        for (i in 0 until width * height) {
            lum[i] = (0.299 * (red[i].toInt() and 0xFF) +
                    0.587 * (green[i].toInt() and 0xFF) +
                    0.114 * (blue[i].toInt() and 0xFF)).toShort()
        }
    }

    override fun toString(): String = "$imageFileName ($width x $height)"

    fun getColorAsInt(i: Int): IntArray = intArrayOf(red[i].toInt() and 0xFF, green[i].toInt() and 0xFF, blue[i].toInt() and 0xFF)

    fun save(saveName:String = imageFileName) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val data = IntArray(width * height)
        for (i in 0 until data.size) {
            data[i] = ((red[i].toInt() and 0xFF) shl 16) or
                    ((green[i].toInt() and 0xFF) shl 8) or
                    ((blue[i].toInt() and 0xFF) shl 0)
        }
        image.setRGB(0, 0, width, height, data, 0, width)
        ImageIO.write(image, "png", File("${System.getProperty("user.home")}${File.separator}Desktop${File.separator}$saveName.png"))
    }
}

