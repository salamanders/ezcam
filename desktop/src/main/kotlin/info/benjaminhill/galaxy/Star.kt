package info.benjaminhill.galaxy

/** Small wrapper around star coordinates */
class Star(
        /** Fixed location within the image */
        val loc: Point2D,
        /** Brightest pixel lum */
        val lum: Short
) {

    constructor(x: Double, y: Double, lum: Short) : this(Point2D(x, y), lum)

    /** Virtual location after distortion/rotation correction, may move around */
    // @delegate:Transient // Causes NPE because missing after deserialization
    val vLoc: Point2D = loc.copy()

    fun distSq(other: Star): Double = loc.distSq(other.loc)

    fun virtualDistSq(other: Star): Double = vLoc.distSq(other.vLoc)

    companion object {
        const val MINIMUM_LUM_DELTA = 255 / 4 // How much brighter than the median to be a star
        const val MINIMUM_DIST_SQ = 16 * 16 // only one star allowed within this rage (in pixels sq)
    }
}