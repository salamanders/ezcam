package info.benjaminhill.sa

import java.util.concurrent.ThreadLocalRandom


/**
 * The errorFunc should depend on at least one argX.candidate, or else... not much will happen.
 * <code>
 *   val someDouble = Dimension(-50.0..50.0)
 *   val sf = SimulatedAnnealing(someDouble) {
 *       someDouble.candidate // x=y
 *   }
 *   while (!sf.isComplete()) {
 *       sf.iterate()
 *       println(sf)
 *   }
 *   println("${someDouble.current}")
 * </code>
 *
 * https://en.wikipedia.org/wiki/Adaptive_simulated_annealing
 *
 */
class SimulatedAnnealing(private vararg val dimensions: Dimension, val errorFunc: () -> Double) {

    /** All possible outcomes */
    private var outcomeRandomMove = 0
    private var outcomeSuccess = 0
    private var outcomeNoImprovement = 0
    private var sequentialNoImprovement = 0
    private var currentError = Double.MAX_VALUE

    override fun toString(): String = "{error:$currentError, iterations: $iterationCount (${"%.2f".format(pctRemaining)}%), success:$outcomeSuccess, random: $outcomeRandomMove, no_improvement:$outcomeNoImprovement, bad_sequential:$sequentialNoImprovement\n" +
            "  {" + dimensions.joinToString("},\n  ") + "\n}"

    /** How many iterations total */
    val iterationCount: Int
        get() = outcomeRandomMove + outcomeSuccess + outcomeNoImprovement

    /** When we intend to stop */
    val pctRemaining: Double
        get() = Math.max(0.0, 1 - (iterationCount / MAX_ITERATIONS.toDouble()))

    /** Cools off at the half-way point to the MAX_ITERATIONS so it has some time to hill-climb at the end */
    val temp: Double
        get() = Math.max(0.0, 1 - 2 * (iterationCount / MAX_ITERATIONS.toDouble()))

    fun iterate() {
        dimensions.forEach { dim ->
            dim.next() // might spin if on the edge, but low-cost spin
        }

        val candidateError = Math.abs(errorFunc())

        if (temp >= ThreadLocalRandom.current().nextDouble(1.0) || candidateError < currentError) {
            if (candidateError < currentError) {
                outcomeSuccess++
            } else {
                outcomeRandomMove++
            }
            sequentialNoImprovement = 0
            dimensions.forEach { it.commit() }
            currentError = candidateError
            return
        }

        outcomeNoImprovement++
        sequentialNoImprovement++
        dimensions.forEach { it.revert() }
        // Don't update the error

        // TODO early break
        // TODO would decreasing step size help?  Perhaps after a number of sequentialNoImprovement?
        return
    }

    companion object {
        const val MAX_ITERATIONS = 1_000
    }
}

