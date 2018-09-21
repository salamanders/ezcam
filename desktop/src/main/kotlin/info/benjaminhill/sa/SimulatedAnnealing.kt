package info.benjaminhill.sa

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ThreadLocalRandom
import javax.imageio.ImageIO


/**
 * The errorFunc should depend on at least one argX.candidate, or else... not much will happen.
 * <code>
 *   val someDouble = Dimension(-50.0..50.0)
 *   val sf = SimulatedAnnealing(someDouble) {
 *       someDouble.candidate // x=y
 *   }
 *   while (sf.iterate()) {
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
    var currentError = Double.MAX_VALUE

    override fun toString(): String = "{error:$currentError, iterations: $iterationCount (${"%.2f".format(temp)}%), outcomeSuccess:$outcomeSuccess, outcomeRandomMove: $outcomeRandomMove, outcomeNoImprovement:$outcomeNoImprovement, sequentialNoImprovement:$sequentialNoImprovement\n" +
            "  {" + dimensions.joinToString("},\n  ") + "\n}"

    /** How many iterations total */
    val iterationCount: Int
        get() = outcomeRandomMove + outcomeSuccess + outcomeNoImprovement


    /** Based on percent of the way towards a dynamically set goal based on number of dimensions */
    private val temp: Double
        get() = Math.max(0.0, 1 - (iterationCount / (ITERATIONS_TO_COOL_PER_DIMENSION * dimensions.size.toDouble())))

    /**
     * outputs a value between [0, 1)
     * close to 1 when the temp is high
     * close to 0 when the temp is low
     * closer to 0 when candidateError is much worse than currentError.
     */
    private fun saMagic(candidateError: Double, currentError: Double): Double {
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

        return sequentialNoImprovement < (MAX_SEQUENTIAL_FAILS_PER_DIMENSION * dimensions.size)
    }

    /** Renders the error for the first 2 dimensions over the entire search space. */
    fun render2DErrorFunction() {
        val res = 20 // pixels in each dimension

        dimensions.forEach { d ->
            d.locked = true
        }

        val fileName = "sa_" + dimensions.drop(2).joinToString("_") {
            it.name + "_" + if (it.current in 0.00001..10_000.0)
                it.current.toString()
            else
                "%6.3e".format(it.current)
        } + ".png"
        println("Rendering error function based on dimensions[>2] to $fileName")

        val minX = dimensions[0].range.start
        val maxX = dimensions[0].range.endInclusive
        val minY = dimensions[1].range.start
        val maxY = dimensions[1].range.endInclusive

        val errors = DoubleArray(res * res)
        for (y in 0 until res) {
            println("$y of $res")
            for (x in 0 until res) {
                dimensions[0].current = (x / res.toDouble()) * (maxX - minX) + minX
                dimensions[1].current = (y / res.toDouble()) * (maxY - minY) + minY
                dimensions.forEach { d ->
                    d.revert() // force the candidate to the current
                }
                errors[y * res + x] = errorFunc()
            }
        }
        // Normalize from 0..1
        val maxError = errors.max()!!
        val minError = errors.min()!!
        val normalizedErrors = errors.map {
            ((it - minError) / (maxError - minError)).toFloat()
        }
        println(normalizedErrors.toList().shuffled().take(50))

        val bi = BufferedImage(res, res, BufferedImage.TYPE_INT_RGB)
        bi.createGraphics()!!.let { g ->

            for (x in 0 until res) {
                for (y in 0 until res) {
                    g.color = Color.getHSBColor(0.0f, 0.0f, normalizedErrors[y * res + x])
                    g.fillRect(x, y, 1, 1)
                }
            }
            g.dispose()
        }
        ImageIO.write(bi, "png", File("${System.getProperty("user.home")}${File.separator}Desktop", fileName))
    }


    fun getCurrent(name: String): Double = dimensions.find { it.name == name }!!.current

    companion object {
        /** Termination criteria */
        private const val MAX_SEQUENTIAL_FAILS_PER_DIMENSION = 100
        /** Transition from SA to hill-climbing */
        private const val ITERATIONS_TO_COOL_PER_DIMENSION = 50
    }
}

