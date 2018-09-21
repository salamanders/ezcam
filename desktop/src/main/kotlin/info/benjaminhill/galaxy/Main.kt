package info.benjaminhill.galaxy

import kotlinx.coroutines.experimental.runBlocking


fun main(args: Array<String>) = runBlocking {

    val session = Session()

    /*
    val vcap = session.getVirtualCaptures().toList()[50]
    val wi = WritableImage("starTest", vcap.width, vcap.height)
    vcap.stars.forEach { star ->
        wi.setPixel(star.loc.x.toInt(), star.loc.y.toInt(), 255.toByte(), 0, 0, 9)
    }


    for (k1 in 0.001..0.5 step 0.005) {
        vcap.setVirtualState(0.0, 0.0, 0.000001, vcap.ts, k1)
        vcap.filteredStars.forEach { star ->
            wi.setPixel(star.vLoc.x.toInt(), star.vLoc.y.toInt(), 0, 0, 255.toByte(), 3)
        }
    }

    wi.save()
    */
    session.findCenterOfRotationAndAngle()

    println("x: ${session.rotationAnchorX}, y: ${session.rotationAnchorY}, rpms:${session.radiansPerMs}")
    session.renderStacked()
}


infix fun ClosedRange<Double>.step(step: Double): Iterable<Double> {
    require(start.isFinite())
    require(endInclusive.isFinite())
    require(step > 0.0) { "Step must be positive, was: $step." }
    val sequence = generateSequence(start) { previous ->
        if (previous == Double.POSITIVE_INFINITY) return@generateSequence null
        val next = previous + step
        if (next > endInclusive) null else next
    }
    return sequence.asIterable()
}