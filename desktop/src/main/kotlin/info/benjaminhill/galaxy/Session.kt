package info.benjaminhill.galaxy

import info.benjaminhill.sa.Dimension
import info.benjaminhill.sa.SimulatedAnnealing
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Series of images captured in a sequence with no camera movement
 * Contains the constant info like lens characteristics, rate of sky rotation from that POV, etc.
 */
class Session {

    var radiansPerMs = 0.0
    var rotationAnchorX = 0.0
    var rotationAnchorY = 0.0

    /** New list of captures every time */
    private fun getCaptures(): List<Capture> {
        return File(STAR_FOLDER).walk()
                .filter { it.isFile && it.canRead() && it.length() > 0 }
                .filter { "jpg" == it.extension.toLowerCase() }
                .map { Capture(it.name) }
                .onEach { it.loadMetadata() }
                .toList()
                .sortedBy { it.ts }
    }

    /** Preps all captures for use */
    private fun findAllStarsAndFilter(captures: Collection<Capture>): Collection<Capture> {
        println("Finding stars across ${captures.size} with background ${backgroundLight.getName()}, then filtering")
        val backgroundLum = backgroundLight.getLum()
        captures.onEach { capture ->
            // capture.stars.clear()
            if (capture.getStarsSize() == 0) {
                capture.findStars(backgroundLum)
                capture.saveMetadata() // Unchanging aspects of the capture
                println("Located ${capture.getStarsSize()} stars.")
            }
        }.windowed(3).onEach { (pre, capture, post) ->
            capture.filterStars(pre, post)
        }

        return captures.filter { it.getFilteredStarsSize() > 50 }
    }

    private val backgroundLight: SourceImage by lazy {
        if (!File(STAR_FOLDER, "$BACKGROUND_LIGHT_NAME.png").canRead()) {
            println("Cache miss for $BACKGROUND_LIGHT_NAME")
            val captures = getCaptures()
            captures.chunked(Math.ceil(captures.size / 21.0).toInt()).map { it.first() }.let { subset ->
                println("Getting background lum from ${subset.size} image median.")
                WritableImage.fromMedian(subset).also {
                    it.save(BACKGROUND_LIGHT_NAME)
                }
            }
        }
        println("Loading backgroundLight from cached median $BACKGROUND_LIGHT_NAME")
        SourceImage(BACKGROUND_LIGHT_NAME)
    }

    fun findCenterOfRotationAndAngle() {
        val maxAttempts = (Runtime.getRuntime().availableProcessors() - 1)
        println("Extracting star locations using $maxAttempts SA attempts.")

        val throwawayCaptures = findAllStarsAndFilter(getCaptures())
        val width = throwawayCaptures.first().width
        val height = throwawayCaptures.first().height
        val ts0 = throwawayCaptures.map { it.ts }.min()!!
        val possibleXRange = (-width * 2)..(width * 3)
        val possibleYRange = (-height * 2)..(height * 3)
        val possibleRadiansPerMsRange = 0.5 * Capture.RADIANS_PER_MS..1.5 * Capture.RADIANS_PER_MS
        val totalSteps = AtomicLong(0)

        val deferred = (1..maxAttempts).map { attempt ->
            async {

                // Possible bounds are 1x more in any direction
                val centerOfRotationX = Dimension(possibleXRange, "x")
                val centerOfRotationY = Dimension(possibleYRange, "y")
                val radiansPerMs = Dimension(possibleRadiansPerMsRange, "rpms")

                // because we are messing with internal state
                val threadLocalCaptures = findAllStarsAndFilter(getCaptures())
                // logToImage("findCenterOfRotationAndAngle", captures[3].stars.map { Pair(it.x, it.y) }, possibleXRange.start, possibleXRange.endInclusive, possibleYRange.start, possibleYRange.endInclusive)

                val sf = SimulatedAnnealing(centerOfRotationX, centerOfRotationY, radiansPerMs) {

                    threadLocalCaptures.forEach { cap ->
                        cap.updateVirtualStarLocations(
                                centerOfRotationX.candidate,
                                centerOfRotationY.candidate,
                                radiansPerMs.candidate,
                                ts0
                        )
                    }

                    val numberOfSamples = 15
                    val windows = threadLocalCaptures.windowed(7)
                    windows.chunked(windows.size / numberOfSamples).map { it.first() }
                            .sumByDouble { caps ->
                                caps[3].starDistance(caps[0]) +
                                        caps[3].starDistance(caps[2]) +
                                        caps[3].starDistance(caps[4]) +
                                        caps[3].starDistance(caps[6])
                            }
                }

                //sf.render2DErrorFunction()


                val allLocationsTried = mutableListOf<Pair<Int, Int>>()

                while (sf.iterate()) {
                    totalSteps.incrementAndGet()
                    allLocationsTried.add(Pair(centerOfRotationX.current.toInt(), centerOfRotationY.current.toInt()))
                    if (sf.iterationCount % 100 == 0) {
                        println(sf)
                    }
                }

                logToImage("findCenterOfRotationAndAngle", allLocationsTried, possibleXRange.start, possibleXRange.endInclusive, possibleYRange.start, possibleYRange.endInclusive)
                println("Attempt Results: $attempt, center of rotation: x:${centerOfRotationX.current}, y:${centerOfRotationY.current}, rms:${radiansPerMs.current}")

                sf
            }
        }
        val solutions = mutableListOf<SimulatedAnnealing>()

        println("Launching parallel SA")
        runBlocking {
            deferred.forEach {
                solutions.add(it.await())
            }
            println("Total Steps: ${totalSteps.get()}")
        }

        val bestSA = solutions.minBy { it.currentError }!!
        println("Best SA has an error ${bestSA.currentError}")

        rotationAnchorX = bestSA.getCurrent("x")
        rotationAnchorY = bestSA.getCurrent("y")
        radiansPerMs = bestSA.getCurrent("rpms")
    }

    fun renderStacked() {
        val captures = getCaptures()
        val i0 = captures.size / 2
        listOf(5, 10, 40, 100).forEach { imagesUsed ->
            val someCaptures = captures.slice(Math.max(0, i0 - imagesUsed / 2)..Math.min(captures.size, i0 + imagesUsed / 2))
            val t0 = someCaptures[someCaptures.size / 2].ts
            someCaptures.forEach { cap ->
                cap.rotationAnchorX = rotationAnchorX
                cap.rotationAnchorY = rotationAnchorY
                cap.rotationRadians = (cap.ts - t0) * radiansPerMs
            }
            listOf(5, 10, 20, 40).forEach { mult ->
                WritableImage.fromSum(someCaptures, backgroundLight, mult.toDouble()).save("fromSum_${someCaptures.size}_${mult}_$imagesUsed")
            }
        }

    }

    companion object {
        val STAR_FOLDER = "${System.getProperty("user.home")}${File.separator}Desktop${File.separator}stars"
        const val BACKGROUND_LIGHT_NAME = "backgroundLight"
    }
}