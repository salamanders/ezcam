package info.benjaminhill.galaxy

import java.lang.Math.toRadians
import kotlin.system.measureTimeMillis

/**
 * Frame that can find and cache star locations, so you can do maths without loading the image.
 */
class FrameWithStars(fileName: String = "out", width: Int = 0, height: Int = 0) : Frame(fileName, width, height) {
    val stars: MutableSet<Star> = mutableSetOf()

    private fun findStars() {
        measureTimeMillis {
            stars.clear()
            STAR_BUCKETS.forEach { numBuckets ->

                val numBucketsSqrt = Math.sqrt(numBuckets.toDouble())
                val bestStars = Array(numBuckets) { Star(-1, -1, -1) }

                val bucketWidth = Math.ceil(width / numBucketsSqrt)
                val bucketHeight = Math.ceil(height / numBucketsSqrt)

                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val i = xy2i(x, y)
                        val xBucket = Math.floor(x / bucketWidth).toInt()
                        val yBucket = Math.floor(y / bucketHeight).toInt()
                        val bucketNum = Math.floor(yBucket * numBucketsSqrt + xBucket).toInt()
                        if (bucketNum >= bestStars.size) {
                            // println("$bucketNum >= ${bestStars.size}") Not sure what I'm doing wrong here...
                            continue
                        }
                        if (lum[i] > bestStars[bucketNum].lum) {
                            bestStars[bucketNum] = Star(x, y, lum[i]) // TODO something better with blurring or wavelets or lum deltas
                        }
                    }
                }
                stars += bestStars.filter { it.lum >= Star.MINIMUM_LUM }
            }
        }.let {
            //println("findStars took $it ms")
        }

        stars.removeIf { mayRemove ->
            stars.find { other ->
                other.lum > mayRemove.lum && other.distSq(mayRemove) < Star.MINIMUM_DIST_SQ
            } != null
        }
    }

    override fun loadMetadata() {
        if (frameMetadataFile.canRead()) {
            println("Cache hit ${frameMetadataFile.name}")
            frameMetadataFile.bufferedReader().use {
                GSON.fromJson(it, this::class.java)!!.let { newFrame ->
                    this.fileName = newFrame.fileName
                    this.exposure = newFrame.exposure
                    this.ts = newFrame.ts
                    this.height = newFrame.height
                    this.width = newFrame.width
                    this.stars.addAll(newFrame.stars)
                }
            }
        } else {
            println("Cache miss ${frameMetadataFile.name}")
            loadSourceImage()
            findStars()
            saveMetadata()
        }
    }

    // TODO: Maybe rotation isn't always so simple?
    // TODO: are we rotating the right way?
    fun deviation(other: FrameWithStars, rotationCenterX: Int, rotationCenterY: Int, degreesPerMs: Double): Double {
        require(stars.isNotEmpty())
        require(other.stars.isNotEmpty())
        val theta = toRadians(degreesPerMs) * (other.ts - ts)
        var totalError = 0.0
        other.stars.map { it.rotate(theta, rotationCenterX, rotationCenterY) }.forEach { otherStar ->
            totalError += stars.map { star ->
                star.distSq(otherStar)
            }.min()!!
        }
        return totalError
    }

    companion object {
        private val STAR_BUCKETS = listOf(10 * 10, 20 * 20, 40 * 40)
        const val DEGREES_PER_MS = 360.0 / (24 * 60 * 60 * 1_000)
    }
}