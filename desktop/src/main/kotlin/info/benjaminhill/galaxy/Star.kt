package info.benjaminhill.galaxy

import java.lang.Math.cos
import java.lang.Math.sin

open class Star(val x: Int, val y: Int, val lum: Short) {
    fun distSq(other: Star): Int = (x - other.x) * (x - other.x) + (y - other.y) * (y - other.y)

    fun rotate(theta: Double, rotationCenterX: Int, rotationCenterY: Int): Star {
        //val px = cos(theta) * (otherStar.x - rotationCenterX) - sin(theta) * (otherStar.y - rotationCenterY) + rotationCenterX
        //val py = sin(theta) * (px - rotationCenterX) + cos(theta) * (otherStar.y - rotationCenterY) + rotationCenterY

        val cosAlpha = cos(theta)
        val sinAlpha = sin(theta)

        val dX = x - rotationCenterX
        val dY = y - rotationCenterY

        val px = rotationCenterX + cosAlpha * dX - sinAlpha * dY
        val py = rotationCenterY + sinAlpha * dX + cosAlpha * dY

        return Star(px.toInt(), py.toInt(), lum)
    }

    companion object {
        const val MINIMUM_LUM = 125
        const val MINIMUM_DIST_SQ = 16
    }
}