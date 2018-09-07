package info.benjaminhill.galaxy

import java.lang.Math.cos
import java.lang.Math.sin
import javax.imageio.ImageIO
import kotlin.system.measureTimeMillis


/**
 * A single captured read-only frame that supports star locations, rotation settings, etc.
 * Persists to a metadata cache
 */
class Capture(fileName: String) : SourceImage(fileName) {

    private val stars: MutableSet<Star> = mutableSetOf()
    var rotationRadians = 0.0 // Can't be based off of ts directly, because we don't know what time=0 is
    var rotationAnchorX = 0.0
    var rotationAnchorY = 0.0

    fun getStarsSize(): Int = stars.size

    fun getFilteredStarsSize(): Int = stars.filter { it.valid }.size

    fun filterStars(pre: Capture, post: Capture) {
        stars.forEach { star ->
            star.valid = pre.stars.find { star.distSq(it) <= Star.MINIMUM_DIST_SQ * 2 } != null &&
                    post.stars.find { star.distSq(it) < Star.MINIMUM_DIST_SQ * 2 } != null
        }
    }

    /**
     * Updates all star vx, vy to virtual locations based on rotations
     */
    fun updateVirtualStarLocations(
            newRotationAnchorX: Double,
            newRotationAnchorY: Double,
            newRotationRadiansPerMs: Double,
            ts0: Long
    ) {

        rotationAnchorX = newRotationAnchorX
        rotationAnchorY = newRotationAnchorY
        rotationRadians = newRotationRadiansPerMs * (ts - ts0)

        val cosAlpha = cos(rotationRadians)
        val sinAlpha = sin(rotationRadians)
        stars.forEach {
            val dX = it.x - rotationAnchorX
            val dY = it.y - rotationAnchorY
            it.vx = rotationAnchorX + cosAlpha * dX - sinAlpha * dY
            it.vy = rotationAnchorY + sinAlpha * dX + cosAlpha * dY
        }
    }

    override fun getColorData(): Triple<ByteArray, ByteArray, ByteArray> {
        if (rotationRadians.isNaN()) {
            return getColorData(ImageIO.read(sourceImageFile))
        }
        return getColorData(rotate(ImageIO.read(sourceImageFile), rotationRadians, rotationAnchorX, rotationAnchorY))
    }

    /** Time intensive, so caller should save after this */
    fun findStars(backgroundLum: ShortArray) {

        measureTimeMillis {
            stars.clear()
            val lum = getLum()
            for (i in 0 until lum.size) {
                lum[i] = Math.max(0, lum[i] - backgroundLum[i]).toShort()
            }
            STAR_BUCKET_DIM.forEach { bucketDim ->
                val bucketWidth = width / bucketDim
                val bucketHeight = height / bucketDim
                val totalBucketCount = Math.ceil(bucketDim * bucketDim).toInt()
                val bestStarPerBucket = Array(totalBucketCount) { Star(-1, -1, -1) }

                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val i = xy2i(x, y)
                        val xBucket = Math.floor(x / bucketWidth).toInt()
                        val yBucket = Math.floor(y / bucketHeight).toInt()
                        val bucketNum = Math.floor(yBucket * bucketDim + xBucket).toInt()
                        if (lum[i] > bestStarPerBucket[bucketNum].lum) {
                            bestStarPerBucket[bucketNum] = Star(x, y, lum[i]) // TODO something better with blurring or wavelets or lum deltas
                        }
                    }
                }
                stars += bestStarPerBucket.filter { it.lum >= Star.MINIMUM_LUM_DELTA }
            }

            stars.removeIf { mayRemove ->
                stars.find { other ->
                    other.lum > mayRemove.lum && other.distSq(mayRemove) < Star.MINIMUM_DIST_SQ
                } != null
            }

        }.let {
            println("findStars took $it ms to find ${stars.size}")
        }
    }

    /**
     * Sum of distances to nearest star (squared)
     * Nearest determined pre-rotation
     * Caller must have set rotations and anchors and called updateVirtualStarLocations
     * Not thread-safe because depends on each Capture's internal rotation settings
     * TODO: penalty for star brightness differences `val pctDiffLum = Math.abs((star.lum - otherStar.lum) / 255.0)`
     */
    fun starDistance(other: Capture): Double {
        //require(getFilteredStarsSize()>0)
        //require(other.getFilteredStarsSize()>0)

        val starPairs = stars.filter { it.valid }.map { star ->
            star to other.stars.filter { otherStar -> otherStar.valid }.minBy { otherStar -> star.distSq(otherStar) }!!
        }

        return starPairs.map { pair -> pair.first.virtualDistSq(pair.second) }.average()
    }

    override fun loadMetadata() {
        super.loadMetadata()
        metadataFile.bufferedReader().use {
            val loadedCapture = SourceImage.GSON.fromJson(it, this::class.java)!!
            stars.addAll(loadedCapture.stars)
            rotationRadians = loadedCapture.rotationRadians
            rotationAnchorX = loadedCapture.rotationAnchorX
            rotationAnchorY = loadedCapture.rotationAnchorY
        }
    }

    override fun saveMetadata() {
        metadataFile.printWriter().use {
            GSON.toJson(this, it)
        }
    }

    companion object {
        /** How many big buckets along the x and y axis?   Must be more than 0, shouldn't line up along the same boundaries */
        private val STAR_BUCKET_DIM = listOf(11.0, 29.0, 41.0)
        const val RADIANS_PER_MS = (2 * Math.PI) / (24 * 60 * 60 * 1_000)
    }
}
