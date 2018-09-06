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

    val stars: MutableSet<Star> = mutableSetOf()
    val filteredStars: MutableSet<Star> = mutableSetOf()
    var rotationRadians = Double.NaN // Can't be based off of ts directly, because we don't know what time=0 is
    var rotationAnchorX = Double.NaN
    var rotationAnchorY = Double.NaN

    /**
     * Get the (potentially rotated) star locations
     */
    fun getStars(): Collection<Star> {
        if (rotationRadians.isNaN()) {
            return filteredStars.sortedByDescending { it.lum }
        }
        val cosAlpha = cos(rotationRadians)
        val sinAlpha = sin(rotationRadians)
        return filteredStars.sortedByDescending { it.lum }.map {
            val dX = it.x - rotationAnchorX
            val dY = it.y - rotationAnchorY

            val px = rotationAnchorX + cosAlpha * dX - sinAlpha * dY
            val py = rotationAnchorY + sinAlpha * dX + cosAlpha * dY

            Star(px.toInt(), py.toInt(), it.lum)
        }.toList()
    }

    override fun getColorData(): Triple<ByteArray, ByteArray, ByteArray> {
        if (rotationRadians.isNaN()) {
            return getColorData(ImageIO.read(sourceImageFile))
        }
        return getColorData(rotate(ImageIO.read(sourceImageFile), rotationRadians, rotationAnchorX, rotationAnchorY))
    }

    /** Loads everything interesting from metadata */
    fun copy(): Capture = Capture(this.sourceImageFile.name).also { it.loadMetadata() }

    /** Time intensive, so caller should save after this */
    fun findStars() {

        measureTimeMillis {
            stars.clear()
            val lum = getLum()
            val backgroundLum = SourceImage(Session.BACKGROUND_LIGHT_NAME).getLum()
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
     * Average distance to nearest star (squared)
     * Caller must have set rotations and anchors
     * Not thread-safe because depends on each Capture's internal rotation settings
     * TODO: penalty for star brightness differences `val pctDiffLum = Math.abs((star.lum - otherStar.lum) / 255.0)`
     */
    fun starDistance(other: Capture): Double {
        require(filteredStars.isNotEmpty())
        require(other.filteredStars.isNotEmpty())

        val otherStarLocations = other.getStars()
        return filteredStars.map { thisStar ->
            // maybe sqrt because lots of small offsets is worse than one big offset
            otherStarLocations.map { otherStar ->
                thisStar.distSq(otherStar)
            }.min()!!
        }.average()
    }

    override fun loadMetadata() {
        super.loadMetadata()
        metadataFile.bufferedReader().use {
            val loadedCapture = SourceImage.GSON.fromJson(it, this::class.java)!!
            stars.addAll(loadedCapture.stars)
            filteredStars.addAll(loadedCapture.filteredStars)
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
