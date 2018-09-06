package info.benjaminhill.galaxy

import info.benjaminhill.sa.Dimension
import info.benjaminhill.sa.SimulatedAnnealing
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.runBlocking
import org.nield.kotlinstatistics.median
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

    val captures by lazy {
        File(STAR_FOLDER).walk()
                .filter { it.isFile && it.canRead() && it.length() > 0 }
                .filter { "jpg" == it.extension.toLowerCase() }
                .map { Capture(it.name) }
                .onEach { it.loadMetadata() }
                .toList()
                .sortedBy { it.ts }
    }

    fun findAllStars() {
        println("Finding stars across ${captures.size} with background ${backgroundLight.getName()}")
        captures.forEach { capture ->
            // capture.stars.clear()
            if (capture.stars.isEmpty()) {
                capture.findStars()
                capture.saveMetadata() // save before filtering so we can play with the filter size.
                logToImage("allStars", capture.stars.map { star -> Pair(star.x, star.y) })
            }
        }
    }

    /** Clear out isolated stars that don't have any close friends. Skips first and last.  */
    fun filterStars() {
        println("Filtering stars")
        captures.windowed(3).forEach { (pre, capture, post) ->
            // capture.filteredStars.clear()
            if (capture.filteredStars.isEmpty()) {
                capture.filteredStars.addAll(capture.stars.filter { star ->
                    pre.stars.find { star.distSq(it) <= Star.MINIMUM_DIST_SQ * 2 } != null &&
                            post.stars.find { star.distSq(it) < Star.MINIMUM_DIST_SQ * 2 } != null
                })
                capture.saveMetadata()
                println("Filtered from ${capture.stars.size} to ${capture.filteredStars.size}")
                logToImage("filteredStars", capture.filteredStars.map { star -> Pair(star.x, star.y) })
            }
        }
    }

    private val backgroundLight: SourceImage by lazy {
        if (!File(STAR_FOLDER, "$BACKGROUND_LIGHT_NAME.png").canRead()) {
            println("Cache miss for $BACKGROUND_LIGHT_NAME")
            captures.chunked(Math.ceil(captures.size / 15.0).toInt()).map { it.first() }.let { subset ->
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

        val width = captures.first().width
        val height = captures.first().height
        val possibleXRange = (-width)..(width * 2)
        val possibleYRange = (-height)..(height * 2)
        val possibleRadiansPerMsRange = 0.0..2 * Capture.RADIANS_PER_MS
        val totalSteps = AtomicLong(0)

        val deferred = (1..maxAttempts).map { attempt ->
            async {

                // Possible bounds are 1x more in any direction
                val centerOfRotationX = Dimension(possibleXRange, "x")
                val centerOfRotationY = Dimension(possibleYRange, "y")
                val radiansPerMs = Dimension(possibleRadiansPerMsRange, "rms")
                val threadLocalCaptures = captures.map { it.copy() }.filter { it.filteredStars.isNotEmpty() } // because we are messing with internal state

                // logToImage("findCenterOfRotationAndAngle", captures[3].stars.map { Pair(it.x, it.y) }, possibleXRange.start, possibleXRange.endInclusive, possibleYRange.start, possibleYRange.endInclusive)

                val sf = SimulatedAnnealing(centerOfRotationX, centerOfRotationY, radiansPerMs) {
                    threadLocalCaptures.windowed(7).sumByDouble { caps ->
                        // Set the candidate rotation anchor, and the offset-from-middle rotation amounts
                        caps.forEach { cap ->
                            cap.rotationAnchorX = centerOfRotationX.candidate
                            cap.rotationAnchorY = centerOfRotationY.candidate
                            cap.rotationRadians = if (cap.ts == caps[3].ts) Double.NaN else radiansPerMs.candidate * (cap.ts - caps[3].ts)
                        }

                        caps[3].starDistance(caps[0]) +
                                caps[3].starDistance(caps[2]) +
                                caps[3].starDistance(caps[4]) +
                                caps[3].starDistance(caps[6])
                    }
                }

                val allLocationsTried = mutableListOf<Pair<Int, Int>>()


                while (sf.iterate()) {
                    totalSteps.incrementAndGet()
                    allLocationsTried.add(Pair(centerOfRotationX.current.toInt(), centerOfRotationY.current.toInt()))
                    if (sf.iterationCount % 100 == 0) {
                        println(sf)
                    }
                }

                logToImage("findCenterOfRotationAndAngle", allLocationsTried, possibleXRange.start, possibleXRange.endInclusive, possibleYRange.start, possibleYRange.endInclusive)
                println("Attempt: $attempt, center of rotation: x:${centerOfRotationX.current}, y:${centerOfRotationY.current}, rms:${radiansPerMs.current}")
                Triple(radiansPerMs.current, centerOfRotationX.current, centerOfRotationY.current)
            }
        }
        val allRadPerMs = mutableListOf<Double>()
        val allX = mutableListOf<Double>()
        val allY = mutableListOf<Double>()
        runBlocking {
            deferred.forEach {
                val (radPerMs, x, y) = it.await()
                allRadPerMs.add(radPerMs)
                allX.add(x)
                allY.add(y)
            }
            println("Total Steps: ${totalSteps.get()}")
        }
        radiansPerMs = allRadPerMs.median()
        rotationAnchorX = allX.median()
        rotationAnchorY = allY.median()
    }

    fun renderStacked() {
        val i0 = captures.size / 2
        val someCaptures = captures.slice(i0 - 40..i0 + 40)
        val t0 = someCaptures[someCaptures.size / 2].ts
        someCaptures.forEach { cap ->
            cap.rotationAnchorX = rotationAnchorX
            cap.rotationAnchorY = rotationAnchorY
            cap.rotationRadians = (cap.ts - t0) * radiansPerMs
        }
        WritableImage.fromSum(someCaptures, backgroundLight, 20.0).save("fromSum${someCaptures.size}_20")
        //WritableImage.fromMaxLum(someCaptures).save("fromMaxLum${someCaptures.size}" )
        //WritableImage.fromMedian(someCaptures).save("fromMedian${someCaptures.size}")
    }

    companion object {
        val STAR_FOLDER = "${System.getProperty("user.home")}${File.separator}Desktop${File.separator}stars"
        const val BACKGROUND_LIGHT_NAME = "backgroundLight"
    }
}