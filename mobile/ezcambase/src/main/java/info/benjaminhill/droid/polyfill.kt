package info.benjaminhill.droid

fun Throwable.rootCause(): Throwable {
    var rootCause: Throwable? = this
    while (rootCause?.cause != null && rootCause.cause !== rootCause) {
        rootCause = rootCause.cause
    }
    return rootCause!!
}