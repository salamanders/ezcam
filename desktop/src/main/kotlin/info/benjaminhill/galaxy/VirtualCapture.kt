package info.benjaminhill.galaxy

import org.nield.kotlinstatistics.median
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** Representation of a capture including filtered stars, rotation, and distortion */
class VirtualCapture(fileName: String) : Capture(fileName) {

    private var rotationRadians = 0.0 // Can't be based off of ts directly, because we don't know what time=0 is
    private val rotationAnchor = Point2D()
    private var distortionK1 = 0.0

    @Transient
    val filteredStars: MutableSet<Star> = mutableSetOf()

    fun filterStars(pre: Capture, post: Capture) {
        filteredStars.addAll(
                stars.filter { star ->
                    pre.stars.find { star.distSq(it) <= Star.MINIMUM_DIST_SQ * 2 } != null &&
                            post.stars.find { star.distSq(it) < Star.MINIMUM_DIST_SQ * 2 } != null
                }
        )
    }

    fun setVirtualState(
            newRotationAnchorX: Double,
            newRotationAnchorY: Double,
            newRotationRadiansPerMs: Double,
            ts0: Long,
            newDistortionK1: Double
    ) {
        rotationAnchor.set(newRotationAnchorX, newRotationAnchorY)
        rotationRadians = newRotationRadiansPerMs * (ts - ts0)
        distortionK1 = newDistortionK1
        updateFilteredLocations()
    }


    private fun updateFilteredLocations() {
        stars.forEach { star ->
            star.vLoc.set(star.loc)

            // First undo distortion
            undistort(star.vLoc)

            // Second undo rotation
            star.vLoc.rotateAroundAnchor(rotationAnchor, rotationRadians)
        }
        // println("${stars.first().loc} -> ${stars.first().vLoc}")
    }

    private fun undistort(loc: Point2D) {
        val centerX = width / 2.0
        val centerY = height / 2.0
        val radiusNormalizationScale = 1.0 / Math.min(centerX, centerY)

        val distToCenterX = loc.x - centerX
        val distToCenterY = loc.y - centerY

        val radiusDistortedNormalized = radiusNormalizationScale *
                Math.sqrt(distToCenterX * distToCenterX + distToCenterY * distToCenterY)

        // poly3 model
        val radiusOriginalNormalized = distortionK1 * Math.pow(radiusDistortedNormalized, 3.0) +
                (1 - distortionK1) * radiusDistortedNormalized

        loc.scaleAroundAnchor(Point2D(centerX, centerY), radiusOriginalNormalized / radiusDistortedNormalized)
    }

    override fun getColorData(): Triple<ByteArray, ByteArray, ByteArray> {
        if (rotationRadians.isNaN()) {
            return getColorData(ImageIO.read(sourceImageFile))
        }
        return getColorData(rotate(ImageIO.read(sourceImageFile), rotationAnchor, rotationRadians))
    }

    override fun loadMetadata() {
        super.loadMetadata()
        metadataFile.bufferedReader().use {
            val loadedCapture = SourceImage.GSON.fromJson(it, this::class.java)!!
            rotationRadians = loadedCapture.rotationRadians
            rotationAnchor.set(loadedCapture.rotationAnchor)
        }
    }


    /**
     * Median of distances to *nearest* star (squared)
     * Caller must have setVirtualState on both this and other
     * Not thread-safe because depends on each Capture's internal rotation settings
     * TODO: penalty for star brightness differences `val pctDiffLum = Math.abs((star.lum - otherStar.lum) / 255.0)`
     */
    fun virtualStarDistance(other: VirtualCapture): Double {
        require(filteredStars.isNotEmpty())
        require(other.filteredStars.isNotEmpty())

        return filteredStars.map { star ->
            other.filteredStars.asSequence().map { otherStar ->
                star.virtualDistSq(otherStar)
            }.min()!!
        }.median()
    }

    override fun toString(): String = "VirtualCapture(${sourceImageFile.name}, $width x $height, stars:${stars.size}, filtered:${filteredStars.size})"

    companion object {
        fun rotate(colorImage: BufferedImage, rotationAnchor: Point2D = Point2D(colorImage.width / 2.0, colorImage.height / 2.0), radianAngle: Double): BufferedImage {
            val result = BufferedImage(colorImage.width, colorImage.height, BufferedImage.TYPE_3BYTE_BGR)
            //GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.createCompatibleImage(colorImage.width, colorImage.height, Transparency.TRANSLUCENT)
            result.createGraphics().let { g ->
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

                // val at = AffineTransform.getTranslateInstance(((neww - colorImage.width) / 2).toDouble(), ((newh - colorImage.height) / 2).toDouble())!!
                // at.rotate(radianAngle, (colorImage.width / 2).toDouble(), (colorImage.height / 2).toDouble())

                val at = AffineTransform.getRotateInstance(radianAngle, rotationAnchor.x, rotationAnchor.y)
                g.drawRenderedImage(colorImage, at)
                g.dispose()
            }

            return result
        }
    }
}