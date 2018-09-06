package info.benjaminhill.galaxy


fun main(args: Array<String>) {

    val session = Session()
    println("Total of ${session.captures.size} captures loaded.")

    println("findAllStars")
    session.findAllStars()
    session.filterStars()

    println("findCenterOfRotationAndAngle")
    session.findCenterOfRotationAndAngle()

    println("${session.rotationAnchorX}, ${session.rotationAnchorY}, ${session.radiansPerMs}")
    session.renderStacked()
}




