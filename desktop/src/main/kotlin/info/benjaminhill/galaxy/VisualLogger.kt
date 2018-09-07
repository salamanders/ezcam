package info.benjaminhill.galaxy

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ThreadLocalRandom
import javax.imageio.ImageIO

/**
 * Last point gets priority
 * Multiple log runs to the same image will overlay with new random colors, good for series.
 */
@Synchronized
fun logToImage(imageName: String, pairs: Collection<Pair<Int, Int>>,
               minX: Int = pairs.minBy { it.first }!!.first,
               maxX: Int = pairs.maxBy { it.first }!!.first,
               minY: Int = pairs.minBy { it.second }!!.second,
               maxY: Int = pairs.maxBy { it.second }!!.second
) {
    val debugImageFile = File(Session.STAR_FOLDER, "$imageName.png")

    val debugImage = if (debugImageFile.exists()) {
        ImageIO.read(debugImageFile)!!
    } else {
        BufferedImage(maxX - minX, maxY - minY, BufferedImage.TYPE_INT_RGB)
    }

    val pixelSizeW = Math.max(1, (maxX - minX) / 1_000)
    val pixelSizeH = Math.max(1, (maxY - minY) / 1_000)

    debugImage.createGraphics()!!.let { g ->
        g.color = Color.getHSBColor(ThreadLocalRandom.current().nextFloat(), 1.0f, 1.0f)
        pairs.forEach { p ->
            require(p.first in minX..maxX)
            require(p.second in minY..maxY)
            g.fillRect(p.first - minX, p.second - minY, pixelSizeW, pixelSizeH)
        }
        g.fillRect(pairs.last().first - minX, pairs.last().second - minY, pixelSizeW * 3, pixelSizeH * 3)
        g.dispose()
    }
    ImageIO.write(debugImage, "png", debugImageFile)
}
