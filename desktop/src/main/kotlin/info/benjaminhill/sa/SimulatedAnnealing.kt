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

    override fun toString(): String = "{error:$currentError, iterations: $iterationCount (${"%.2f".format(temp)}%), success:$outcomeSuccess, random: $outcomeRandomMove, no_improvement:$outcomeNoImprovement, bad_sequential:$sequentialNoImprovement\n" +
            "  {" + dimensions.joinToString("},\n  ") + "\n}"

    /** How many iterations total */
    val iterationCount: Int
        get() = outcomeRandomMove + outcomeSuccess + outcomeNoImprovement


    /** Cools off at the half-way point to the MAX_ITERATIONS so it has some time to hill-climb at the end */
    val temp: Double
        get() = Math.max(0.0, 1 - (iterationCount / ITERATIONS_TO_COOL))

    /**
     * outputs a value between [0, 1)
     * close to 1 when the temp is high
     * close to 0 when the temp is low
     * closer to 0 when candidateError is much worse than currentError.
     */
    fun saMagic(candidateError: Double, currentError: Double): Double {
        val adjustForBadStep = currentError / candidateError // lower value for higher candidate error
        return temp * Math.min(1.0, Math.max(0.0, adjustForBadStep))
    }

    fun iterate(): Boolean {
        dimensions.forEach { dim ->
            dim.temp = temp
            dim.next() // might spin if on the edge, but low-cost spin
        }

        val candidateError = Math.abs(errorFunc())

        // Instant success!
        if (candidateError < currentError) {
            outcomeSuccess++
            sequentialNoImprovement = 0
            dimensions.forEach { it.commit() }
            currentError = candidateError
            return true
        }

        // Might do it anyway randomly based on (temp is high and not a horrible move).
        if (ThreadLocalRandom.current().nextDouble(1.0) <= saMagic(candidateError, currentError)) {
            outcomeRandomMove++
            sequentialNoImprovement = 0
            dimensions.forEach { it.commit() }
            currentError = candidateError
            return true
        }

        outcomeNoImprovement++
        sequentialNoImprovement++
        dimensions.forEach { it.revert() }
        // Don't update the currentError

        return sequentialNoImprovement < MAX_SEQUENTIAL_FAILS
    }

    companion object {
        private const val MAX_SEQUENTIAL_FAILS = 400
        private const val ITERATIONS_TO_COOL = 500.0
    }
}

