package info.benjaminhill.galaxy

/**
 * Generic point in 2d space.
 * Disallows arbitrary x/y setting, but allows "set to match other point" to enable reuse.
 */
class Point2D() {
    var x: Double = 0.0
        private set
    var y: Double = 0.0
        private set

    constructor(newX: Double, newY: Double) : this() {
        x = newX
        y = newY
    }

    fun distSq(other: Point2D): Double = (x - other.x) * (x - other.x) + (y - other.y) * (y - other.y)

    fun set(other: Point2D): Point2D {
        x = other.x
        y = other.y
        return this
    }

    fun set(otherX: Double, otherY: Double): Point2D {
        x = otherX
        y = otherY
        return this
    }

    fun copy(): Point2D = Point2D(x, y)

    fun scaleAroundAnchor(anchor: Point2D, scale: Double): Point2D {
        x = (x - anchor.x) * scale + anchor.x
        y = (y - anchor.y) * scale + anchor.y
        return this
    }

    fun rotateAroundAnchor(anchor: Point2D, rotationRadians: Double): Point2D {
        // Could be cached
        val cosRotationRadians = Math.cos(rotationRadians)
        val sinRotationRadians = Math.sin(rotationRadians)

        val offsetX = x - anchor.x
        val offsetY = y - anchor.y

        x = (offsetX * cosRotationRadians - offsetY * sinRotationRadians) + anchor.x
        y = (offsetY * cosRotationRadians + offsetX * sinRotationRadians) + anchor.y

        return this
    }

    override fun toString(): String = "{x:$x, y:$y}"
}