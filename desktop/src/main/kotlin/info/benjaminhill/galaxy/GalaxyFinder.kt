package info.benjaminhill.galaxy


import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ThreadLocalRandom
import javax.imageio.ImageIO

const val TRAIL_MAX_GAP_FRAME = 10
const val TRAIL_MIN_DURATION_MS = 5 * 60 * 1000
const val MIN_TRAIL_STARS = 10
const val TRAIL_MIN_DELTA_PX = 100
const val STAR_MAX_HOP_PX = 50

fun main(args: Array<String>) {

    println("Extracting star locations.")
    // Prep by determining star locations using a sequence, then reload with just the metadata (will run out of memory if all in the same pass)
    Frame.sourceImages()
            .take(100)
            .map { FrameWithStars(it) }
            .forEach {
                it.loadMetadata()
            }

    val allFrames = Frame.sourceImages().map { FrameWithStars(it) }.onEach { it.loadMetadata() }.take(20).toList()
    val tlr = ThreadLocalRandom.current()!!
    val sampleFrames = allFrames
    val width = sampleFrames.first().width
    val height = sampleFrames.first().height

    var centerOfRotationX = width / 2
    var centerOfRotationY = height / 2
    var degreesPerMs = FrameWithStars.DEGREES_PER_MS
    // TODO var temperature = 1.0
    var sequentialFails = 0
    var deviation = Double.MAX_VALUE
    for (i in 0 until 50_000) {
        val newX = centerOfRotationX + tlr.nextInt(-50, 50)
        val newY = centerOfRotationY + tlr.nextInt(-50, 50)
        val newDegreesPerMs = degreesPerMs + tlr.nextDouble(-(FrameWithStars.DEGREES_PER_MS / 5), (FrameWithStars.DEGREES_PER_MS / 5))
        val newDeviation = sampleFrames[15].deviation(sampleFrames[16], newX, newY, newDegreesPerMs)
        /*+
                sampleFrames[1].deviation(sampleFrames[2], newX, newY, newDegreesPerMs) +
                sampleFrames[2].deviation(sampleFrames[3], newX, newY, newDegreesPerMs) +
                sampleFrames[3].deviation(sampleFrames[4], newX, newY, newDegreesPerMs)
                */
        if (newDeviation < deviation) {
            centerOfRotationX = newX
            centerOfRotationY = newY
            degreesPerMs = newDegreesPerMs
            deviation = newDeviation
            sequentialFails = 0
        } else {
            sequentialFails++
        }
        if (i % 1_000 == 0) {
            println("$centerOfRotationX, $centerOfRotationY, $degreesPerMs ($deviation)")
            if (sequentialFails >= 3_000) {
                println("Breaking after $i iterations.")
                break
            }
        }
    }

    System.exit(0)

    val starLocations = BufferedImage(allFrames.first().width, allFrames.first().height, BufferedImage.TYPE_INT_RGB)
    starLocations.graphics!!.let { g ->
        allFrames.forEach { f ->
            f.stars.forEach { star ->
                g.drawString(".", star.x, star.y)
            }
        }
        g.dispose()
    }

    println("Bucketing trails...")


    val liveTrails: MutableList<MutableList<TimestampStar>> = mutableListOf()
    val closedTrails: MutableList<MutableList<TimestampStar>> = mutableListOf()



    allFrames.forEachIndexed { frameIdx, starFrame ->
        // trim out expired trails
        val (newLive, newClosed) = liveTrails.partition {
            it.last().frame >= frameIdx - TRAIL_MAX_GAP_FRAME
        }
        liveTrails.retainAll(newLive)
        closedTrails += newClosed

        starFrame.stars.map {
            TimestampStar(it.x, it.y, it.lum, starFrame.ts, frameIdx) // keep track of what time it came from
        }.sortedByDescending { it.lum } // place bright stars first
                .forEach { tls ->
                    val bestTrail = liveTrails.filter { trail ->
                        trail.last().frame < tls.frame &&
                                trail.last().distSq(tls) < STAR_MAX_HOP_PX * STAR_MAX_HOP_PX
                    }.minBy { trail ->
                        trail.last().distSq(tls)
                    }
                    bestTrail?.add(tls) ?: liveTrails.add(mutableListOf(tls))
                }
        println("Frame $frameIdx with live ${liveTrails.size}, closed ${closedTrails.size}")

    }
    closedTrails += liveTrails

    println("Found ${closedTrails.size} potential trails...")

    closedTrails.retainAll { trail ->
        trail.size > MIN_TRAIL_STARS &&
                trail.maxBy { it.ts }!!.ts - trail.minBy { it.ts }!!.ts > TRAIL_MIN_DURATION_MS &&
                trail.maxBy { it.ts }!!.distSq(trail.minBy { it.ts }!!) > TRAIL_MIN_DELTA_PX * TRAIL_MIN_DELTA_PX
    }

    println("Of which ${closedTrails.size} meet the criteria...")

    // Brightest by total lum, which takes into account star brightness and trail length
    val brightestTrails = closedTrails.sortedByDescending { trail ->
        trail.sumBy { it.lum.toInt() }
    }.take(500)

    val time0 = brightestTrails.flatMap { it }.map { it.ts }.min()!!
    println("Winning trails: ${brightestTrails.size}")

    starLocations.graphics!!.let { g ->
        brightestTrails.forEachIndexed { i, trail ->
            trail.forEach { star ->
                g.drawString("↙t$i:s${star.frame}", star.x + 5, star.y)
            }
        }
        g.dispose()
    }
    ImageIO.write(starLocations, "png", File("${System.getProperty("user.home")}${File.separator}Desktop${File.separator}allStars.png"))

    File(Frame.STAR_FOLDER, "trails.tsv").printWriter().use { out ->
        out.println("Trail Number\tOrder in Trail\tTS\tX\tY\tLum")
        brightestTrails.forEachIndexed { trailIdx, trail ->
            trail.forEachIndexed { tlsIdx, star ->
                out.println(listOf(trailIdx, tlsIdx, star.ts - time0, star.x, star.y, star.lum).joinToString("\t"))
            }
        }
    }


}

class TimestampStar(x: Int, y: Int, lum: Short, val ts: Long, val frame: Int) : Star(x, y, lum)
