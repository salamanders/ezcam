package info.benjaminhill.galaxy

/** Small wrapper around star coordinates */
class Star(val x: Int, val y: Int, val lum: Short) {
    fun distSq(other: Star): Int = (x - other.x) * (x - other.x) + (y - other.y) * (y - other.y)
    fun virtualDistSq(other: Star): Double = (vx - other.vx) * (vx - other.vx) + (vy - other.vy) * (vy - other.vy)

    // Virtual location after rotation
    @Transient
    var vx: Double = Double.NaN
    // Virtual location after rotation
    @Transient
    var vy: Double = Double.NaN
    // Has neighbors
    @Transient
    var valid: Boolean = false

    companion object {
        const val MINIMUM_LUM_DELTA = 255 / 5 // How much brighter than the median to be a star
        const val MINIMUM_DIST_SQ = 4 * 4// only one star allowed within this rage (in pixels sq)
    }
}