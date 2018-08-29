package info.benjaminhill.galaxy

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.google.gson.GsonBuilder
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.File
import java.io.Serializable
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO


/**
 * Load an image into memory as rgb byte arrays for ease of manipulation.
 * Can be saved as a png.
 * Can have the pixels set from the median/sum/avg of other frames
 * Doesn't have to load the source image data
 */
open class Frame(var fileName: String = "out", var width: Int = 0, var height: Int = 0) : Serializable {
    @delegate:Transient
    private val red: ByteArray by lazy { ByteArray(width * height) }
    @delegate:Transient
    private val green: ByteArray by lazy { ByteArray(width * height) }
    @delegate:Transient
    private val blue: ByteArray by lazy { ByteArray(width * height) }
    @delegate:Transient
    val lum: ShortArray by lazy { ShortArray(width * height) }
    @Transient
    val inputImageFile = File("$STAR_FOLDER${File.separator}$fileName.jpg")
    @Transient
    val outputImageFile = File("$STAR_FOLDER${File.separator}$fileName.png")
    @Transient
    val frameMetadataFile = File("$STAR_FOLDER${File.separator}$fileName.json")

    var ts: Long = 0
    var exposure: Double = 0.0

    /** Must be run manually after modifying the rgb arrays */
    fun updateLums() {
        for (i in 0 until width * height) {
            lum[i] = (0.299 * (red[i].toInt() and 0xFF) +
                    0.587 * (green[i].toInt() and 0xFF) +
                    0.114 * (blue[i].toInt() and 0xFF)).toShort()
        }
    }

    override fun toString(): String = "$fileName ($width x $height)"

    fun xy2i(x: Int, y: Int): Int = y * width + x

    fun getColorAsInt(i: Int): IntArray = intArrayOf(red[i].toInt() and 0xFF, green[i].toInt() and 0xFF, blue[i].toInt() and 0xFF)

    open fun loadMetadata() {
        if (frameMetadataFile.canRead()) {
            println("Cache hit ${frameMetadataFile.name}")
            frameMetadataFile.bufferedReader().use {
                GSON.fromJson(it, Frame::class.java)!!.let { newFrame ->
                    this.fileName = newFrame.fileName
                    this.exposure = newFrame.exposure
                    this.ts = newFrame.ts
                    this.height = newFrame.height
                    this.width = newFrame.width
                }
            }
        } else {
            println("Cache miss ${frameMetadataFile.name}")
            loadSourceImage()
            saveMetadata()
        }
    }

    private val isSourceImageLoaded = AtomicBoolean(false)
    fun loadSourceImage() {
        require(inputImageFile.isFile && inputImageFile.canRead()) {
            "Unable to read source image $inputImageFile"
        }
        if (isSourceImageLoaded.compareAndSet(false, true)) {
            val colorImage: BufferedImage = ImageIO.read(inputImageFile)
            // val colorImage = JPEGImageDecoderImpl(FileInputStream(inputImageFile)).decodeAsBufferedImage()!! // maybe faster?
            // val colorImage = BufferedImageFactory(Toolkit.getDefaultToolkit().createImage(file.getAbsolutePat‌​ h ())).getBufferedImage()
            require(colorImage.alphaRaster == null)
            this.width = colorImage.width
            this.height = colorImage.height
            var pixelLocation = 0
            (colorImage.raster.dataBuffer as DataBufferByte).data.asIterable().chunked(3).forEach { channels ->
                blue[pixelLocation] = channels[0]
                green[pixelLocation] = channels[1]
                red[pixelLocation] = channels[2]
                pixelLocation++
            }
            val directory = ImageMetadataReader.readMetadata(inputImageFile).getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)!!
            ts = (directory.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
                    ?: directory.getDate(ExifSubIFDDirectory.TAG_DATETIME)).time
            exposure = directory.getRational(ExifSubIFDDirectory.TAG_EXPOSURE_TIME).let {
                1.0 * (it.numerator / it.denominator)
            }
            updateLums()
        }
    }

    fun saveImage() {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val data = IntArray(width * height)
        for (i in 0 until data.size) {
            data[i] = ((red[i].toInt() and 0xFF) shl 16) or
                    ((green[i].toInt() and 0xFF) shl 8) or
                    ((blue[i].toInt() and 0xFF) shl 0)
        }
        image.setRGB(0, 0, width, height, data, 0, width)
        ImageIO.write(image, "png", outputImageFile)
    }

    protected fun saveMetadata() {
        frameMetadataFile.printWriter().use {
            GSON.toJson(this, it)
        }
    }

    companion object {
        fun sourceImages(): Sequence<String> = File(STAR_FOLDER).walk()
                .filter { it.isImage() }
                .filter { "jpg" == it.extension.toLowerCase() }
                .map { it.nameWithoutExtension }

        private fun File.isImage(): Boolean = this.isFile && this.length() > 0 && setOf("png", "jpg", "jpeg", "bmp", "tif", "tiff").contains(this.extension.toLowerCase())
        fun byteAvg(vararg others: Byte): Byte = (others.map { it.toInt() and 0xFF }.sum() / others.size).toByte()
        val STAR_FOLDER = "${System.getProperty("user.home")}${File.separator}Desktop${File.separator}stars"
        val GSON = GsonBuilder().setPrettyPrinting().create()!!

        fun fromMaxLum(others: Collection<Frame>): Frame {
            require(others.isNotEmpty())
            return Frame("max_lum", others.first().width, others.first().height).apply {
                for (i in 0 until width * height) {
                    others.maxBy { it.lum[i] }!!.let { maxFrame ->
                        red[i] = maxFrame.red[i]
                        green[i] = maxFrame.green[i]
                        blue[i] = maxFrame.blue[i]
                    }
                }
                updateLums()
            }
        }

        fun fromSum(others: Collection<Frame>): Frame {
            require(others.isNotEmpty())
            return Frame("sum", others.first().width, others.first().height).apply {
                for (i in 0 until width * height) {
                    var r = 0
                    var g = 0
                    var b = 0
                    others.forEach {
                        r += it.red[i].toInt() and 0xFF
                        g += it.green[i].toInt() and 0xFF
                        b += it.blue[i].toInt() and 0xFF
                    }
                    red[i] = minOf(r, 255).toByte()
                    green[i] = minOf(g, 255).toByte()
                    blue[i] = minOf(b, 255).toByte()
                }
                updateLums()
            }
        }

        fun fromMedian(others: Collection<Frame>): Frame {
            require(others.isNotEmpty())
            return Frame("median", others.first().width, others.first().height).apply {
                val isEven = others.size % 2 == 0
                if (isEven) {
                    println("Warning: must do averaging when median and even size: ${others.size}")
                }
                val half = Math.floor((others.size - 1) / 2.0).toInt()
                for (i in 0 until width * height) {
                    others.sortedBy { it.lum[i] }.let { sortedFrames ->
                        val halfFrame = sortedFrames.elementAt(half)
                        if (isEven) {
                            val halfFramePlus = sortedFrames.elementAt(half + 1)
                            red[i] = byteAvg(halfFrame.red[i], halfFramePlus.red[i])
                            green[i] = byteAvg(halfFrame.green[i], halfFramePlus.green[i])
                            blue[i] = byteAvg(halfFrame.blue[i], halfFramePlus.blue[i])
                        } else {
                            red[i] = halfFrame.red[i]
                            green[i] = halfFrame.green[i]
                            blue[i] = halfFrame.blue[i]
                        }
                    }
                }
                updateLums()
            }
        }
    }

}

