package info.benjaminhill.galaxy


fun main(args: Array<String>) {

    val session = Session()

    println("findCenterOfRotationAndAngle")
    session.findCenterOfRotationAndAngle()

    println("${session.rotationAnchorX}, ${session.rotationAnchorY}, ${session.radiansPerMs}")
    session.renderStacked()

}




