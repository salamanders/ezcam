package info.benjaminhill.galaxy

import info.benjaminhill.sa.Dimension
import info.benjaminhill.sa.SimulatedAnnealing
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.awaitAll
import kotlinx.coroutines.experimental.coroutineScope
import kotlinx.coroutines.experimental.yield
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
    var distortionK1 = 0.0

    /** New list of captures every time */
    private fun getRawVirtualCaptures(): Sequence<VirtualCapture> {
        return File(STAR_FOLDER).walk()
                .filter { it.isFile && it.canRead() && it.length() > 0 }
                .filter { "jpg" == it.extension.toLowerCase() }
                .map { VirtualCapture(it.name) }
                .onEach { it.loadMetadata() }
                .sortedBy { it.ts }
    }

    /** Preps all captures for use */
    fun getVirtualCaptures(): Sequence<VirtualCapture> {
        println("getVirtualCaptures: Finding stars across all captures with background, then filtering")
        val rawCaptures = getRawVirtualCaptures().toList()

        if (rawCaptures.find { it.stars.isEmpty() } != null) {
            println("At least one capture with no stars, locating stars.")
            val backgroundLum = backgroundLight.getLum()
            rawCaptures.asSequence().withIndex().forEach { (idx, capture) ->
                if (capture.stars.isEmpty()) {
                    capture.findStars(backgroundLum)
                    capture.saveMetadata() // Unchanging aspects of the capture
                    println("Image $idx located ${capture.stars.size} stars.")
                }
            }
        }
        rawCaptures.windowed(3).forEach { (pre, capture, post) ->
            capture.filterStars(pre, post)
        }
        return rawCaptures.asSequence().filter { it.filteredStars.size > 50 }.asSequence()
    }

    private val backgroundLight: SourceImage by lazy {
        if (!File(STAR_FOLDER, "$BACKGROUND_LIGHT_NAME.png").canRead()) {
            println("Cache miss for $BACKGROUND_LIGHT_NAME")
            val captures = getRawVirtualCaptures().toList()
            captures.asSequence().chunked(Math.ceil(captures.size / 21.0).toInt()).map { it.first() }.toList().let { subset ->
                println("Getting background lum from ${subset.size} image median.")
                WritableImage.fromMedian(subset).also {
                    it.save(BACKGROUND_LIGHT_NAME)
                }
            }
        }
        println("Loading backgroundLight from cached median $BACKGROUND_LIGHT_NAME")
        SourceImage(BACKGROUND_LIGHT_NAME)
    }

    suspend fun findCenterOfRotationAndAngle() {
        val maxAttempts = (Runtime.getRuntime().availableProcessors() - 1)
        println("findCenterOfRotationAndAngle using $maxAttempts SA attempts.")

        val throwawayCaptures = getVirtualCaptures() // Lazy load of star locations
        val width = throwawayCaptures.first().width
        val height = throwawayCaptures.first().height
        val ts0 = throwawayCaptures.map { it.ts }.min()!!
        val possibleXRange = (-width * 2)..(width * 3)
        val possibleYRange = (-height * 2)..(height * 3)
        val possibleRadiansPerMsRange = 0.1 * Capture.RADIANS_PER_MS..1.9 * Capture.RADIANS_PER_MS
        val possibleDistortionRange = 0.0..0.2
        val totalSteps = AtomicLong(0)

        println("Launching parallel SA")
        val deferred = coroutineScope {
            (1..maxAttempts).map { attempt ->
                async {

                    // Possible bounds are 1x more in any direction
                    val centerOfRotationX = Dimension(possibleXRange, "x")
                    val centerOfRotationY = Dimension(possibleYRange, "y")
                    val radiansPerMs = Dimension(possibleRadiansPerMsRange, "rpms")
                    val distortionK1 = Dimension(possibleDistortionRange, "k1")

                    // because we are messing with internal state
                    val threadLocalCaptures = getVirtualCaptures().toList()
                    // logToImage("findCenterOfRotationAndAngle", captures[3].stars.map { Pair(it.x, it.y) }, possibleXRange.start, possibleXRange.endInclusive, possibleYRange.start, possibleYRange.endInclusive)

                    val sf = SimulatedAnnealing(
                            centerOfRotationX,
                            centerOfRotationY,
                            radiansPerMs,
                            distortionK1
                    ) {

                        threadLocalCaptures.forEach { cap ->
                            cap.setVirtualState(
                                    centerOfRotationX.candidate,
                                    centerOfRotationY.candidate,
                                    radiansPerMs.candidate,
                                    ts0,
                                    distortionK1.candidate
                            )
                        }

                        val numberOfSamples = 15
                        val windows = threadLocalCaptures.windowed(7)
                        windows.asSequence().chunked(windows.size / numberOfSamples).map { it.first() }
                                .sumByDouble { caps ->
                                    caps[3].virtualStarDistance(caps[0]) +
                                            caps[3].virtualStarDistance(caps[2]) +
                                            caps[3].virtualStarDistance(caps[4]) +
                                            caps[3].virtualStarDistance(caps[6])
                                }
                    }

                    //sf.render2DErrorFunction()
                    val allLocationsTried = mutableListOf<Pair<Int, Int>>()
                    println("attempt:$attempt: launching SA loop.")
                    while (sf.iterate()) {
                        totalSteps.incrementAndGet()
                        allLocationsTried.add(Pair(centerOfRotationX.current.toInt(), centerOfRotationY.current.toInt()))
                        if (sf.iterationCount % 100 == 0) {
                            println(sf)
                        }
                        yield()
                    }

                    logToImage("findCenterOfRotationAndAngle", allLocationsTried, possibleXRange.start, possibleXRange.endInclusive, possibleYRange.start, possibleYRange.endInclusive)
                    println("attempt:$attempt: center of rotation: x:${centerOfRotationX.current}, y:${centerOfRotationY.current}, rms:${radiansPerMs.current}, k1:${distortionK1.current}")

                    sf
                }
            }
        }
        deferred.awaitAll()
        val solutions = deferred.map { it.await() }
        println("Total Steps: ${totalSteps.get()}")

        val bestSA = solutions.minBy { it.currentError }!!
        println("Best SA has minimal error ${bestSA.currentError}")

        rotationAnchorX = bestSA.getCurrent("x")
        rotationAnchorY = bestSA.getCurrent("y")
        radiansPerMs = bestSA.getCurrent("rpms")
        distortionK1 = bestSA.getCurrent("k1")
    }

    fun renderStacked() {
        val captures = getVirtualCaptures().toList()
        val i0 = captures.size / 2
        listOf(200).forEach { imagesUsed ->
            val someCaptures = captures.slice(Math.max(0, i0 - imagesUsed / 2)..Math.min(captures.size, i0 + imagesUsed / 2))
            val ts0 = someCaptures[someCaptures.size / 2].ts
            someCaptures.forEach { cap ->
                cap.setVirtualState(
                        rotationAnchorX,
                        rotationAnchorY,
                        radiansPerMs,
                        ts0,
                        distortionK1
                )
            }
            listOf(20).forEach { mult ->
                WritableImage.fromSum(someCaptures, backgroundLight, mult.toDouble()).save("fromSum_${someCaptures.size}_${mult}_$imagesUsed")
            }
        }
    }

    companion object {
        val STAR_FOLDER = "${System.getProperty("user.home")}${File.separator}Desktop${File.separator}stars"
        const val BACKGROUND_LIGHT_NAME = "backgroundLight"
    }
}