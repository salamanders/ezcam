package info.benjaminhill.galaxy

fun main(args: Array<String>) {


    val session = Session()
    println("Total of ${session.captures.size} captures loaded.")


    /*
    println("findAllStars")
    session.findAllStars()

    logToImage("allStars", session.captures.windowed(3).flatMap { (_, capture, _) ->
        capture.stars.map { star ->
            Pair(star.x, star.y)
        }
    })

    println("findCenterOfRotationAndAngle")
    session.findCenterOfRotationAndAngle()
    */

    session.radiansPerMs = 6.22E-08
    session.rotationAnchorX = 4708.159728
    session.rotationAnchorY = 2158.863488
    session.renderStacked()
}




