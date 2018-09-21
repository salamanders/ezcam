package info.benjaminhill.galaxy

import kotlin.system.measureTimeMillis


/**
 * A single captured read-only frame that supports star locations, rotation settings, etc.
 * Persists to a metadata cache
 */
open class Capture(fileName: String) : SourceImage(fileName) {

    val stars: MutableSet<Star> = mutableSetOf()

    /** Time intensive, so caller should save after this */
    fun findStars(backgroundLum: ShortArray) {

        val ms = measureTimeMillis {
            stars.clear()
            val lum = getLum()
            for (i in 0 until lum.size) {
                lum[i] = Math.max(0, lum[i] - backgroundLum[i]).toShort()
            }
            STAR_BUCKET_DIM.forEach { bucketDim ->
                val bucketWidth = width / bucketDim
                val bucketHeight = height / bucketDim
                val totalBucketCount = Math.ceil(bucketDim * bucketDim).toInt()
                val bestStarPerBucket = Array(totalBucketCount) { Star(-1.0, -1.0, -1) }

                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val i = xy2i(x, y)
                        val xBucket = Math.floor(x / bucketWidth).toInt()
                        val yBucket = Math.floor(y / bucketHeight).toInt()
                        val bucketNum = Math.floor(yBucket * bucketDim + xBucket).toInt()
                        if (lum[i] > bestStarPerBucket[bucketNum].lum) {
                            bestStarPerBucket[bucketNum] = Star(x.toDouble(), y.toDouble(), lum[i])
                            // TODO something better with blurring or wavelets or lum deltas, better still to do the successive centroids
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

        }
        println("findStars took $ms ms to find ${stars.size}")
    }

    override fun loadMetadata() {
        super.loadMetadata()
        metadataFile.bufferedReader().use {
            val loadedCapture = SourceImage.GSON.fromJson(it, this::class.java)!!
            stars.addAll(loadedCapture.stars)
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
