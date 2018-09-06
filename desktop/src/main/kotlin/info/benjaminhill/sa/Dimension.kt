package info.benjaminhill.sa

import java.util.concurrent.ThreadLocalRandom

/**
 * Dimensions wander on their own, starting randomly from within the range.
 * Functions should reference the `candidate` or `candidate.toInt()` value
 * If you like the result, you can `commit()`.  If not, `revert()`
 * Final readouts should use the `current` value
 */
data class Dimension(private val range: ClosedRange<Double>, val name: String) {

    /** Ints are treated internally as Doubles */
    constructor(range: IntRange, name: String) : this(range.start.toDouble()..range.endInclusive.toDouble(), name)

    /** May be set to a specific value if you freeze along with setPctOfMaxStepSize(0.0) */
    var current: Double = ThreadLocalRandom.current().nextDouble(range.start, range.endInclusive)// where you are at now

    var locked: Boolean = false

    /** Locked-aware random step from -1.0..1.0 */
    private fun rnd(): Double = if (locked) 0.0 else ThreadLocalRandom.current().nextDouble(2.0) - 1.0

    /** When temp is low, can cut down the step size by up to 50% */
    private val tempAdjustedStep
        get() = ((range.endInclusive - range.start) / MAX_START_WOBBLE) * (0.5 + temp / 2)

    /**
     * You are getting close, time to chill out and do smaller steps.  Set by the SA.
     * TODO: Does this help any given that step size is random?
     */
    var temp: Double = 1.0
        set(value) {
            require(value in 0.0..MAX_START_WOBBLE)
            field = value
        }

    var candidate: Double = current  // next potential value
        private set

    /** Will loop until produced candidates in-range.  Respects temp, locked */
    fun next() {
        do {
            candidate = current + rnd() * tempAdjustedStep
        } while (candidate !in range)
    }

    /** Congratulations you like the new state better: save your candidate */
    fun commit() {
        current = candidate
    }

    /** Whoa that was bad, back the truck up. */
    fun revert() {
        candidate = current
    }

    override fun toString(): String = "{$name=$current, ${range.start}..${range.endInclusive} by $tempAdjustedStep}"

    companion object {
        const val MAX_START_WOBBLE: Double = 20.0 // A step size of 1/20th of the range is a pretty big wobble!
    }
}