package info.benjaminhill.sa

import java.util.concurrent.ThreadLocalRandom

/**
 * Dimensions wander on their own, starting randomly from within the range.
 * Functions should reference the `candidate` or `candidate.toInt()` value
 * If you like the result, you can commit().  If not, revert()
 * Final readouts should use the `current` value
 */
class Dimension(private val range: ClosedRange<Double>, val name: String) {

    /** Ints are treated internally as Doubles */
    constructor(range: IntRange, name: String) : this(range.start.toDouble()..range.endInclusive.toDouble(), name)

    /** May be set to a specific value if you freeze along with setPctOfMaxStepSize(0.0) */
    var current: Double = ThreadLocalRandom.current().nextDouble(range.start, range.endInclusive)// where you are at now

    var candidate: Double = current  // next potential value
        private set

    /** Current to candidate can be anywhere in -maxStepSize to maxStepSize */
    private var maxStepSize: Double = 1.0 * (range.endInclusive - range.start) / MAX_START_WOBBLE

    /** Will loop until produced candidates in-range. nextGaussian because why not! */
    fun next() {
        do {
            ThreadLocalRandom.current().nextGaussian()
            candidate = current + if (maxStepSize > 0.0) ThreadLocalRandom.current().nextGaussian() * maxStepSize else 0.0
        } while (candidate !in range)
    }

    /** That was great, things improved, save your candidate */
    fun commit() {
        current = candidate
    }

    /** Whoa that was bad, take a step back */
    fun revert() {
        candidate = current
    }

    /** You are getting close, time to chill out and do smaller steps by this percent */

    fun setPctOfMaxStepSize(pctOfStep: Double) {
        maxStepSize = pctOfStep * ((range.endInclusive - range.start) / MAX_START_WOBBLE)
    }

    override fun toString(): String = "{$name=$current, ${range.start}..${range.endInclusive} by $maxStepSize}"

    companion object {
        const val MAX_START_WOBBLE = 20 // A step size of 1/20th of the range is a pretty big wobble!
    }
}