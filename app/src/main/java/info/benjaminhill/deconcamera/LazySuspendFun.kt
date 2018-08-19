package info.benjaminhill.deconcamera

/**
 * From https://stackoverflow.com/questions/51862715/how-would-i-wrap-this-not-quite-by-lazy-result-caching-function-call-in-idio/51877612#51877612
 * <code>
 * private val cameraDevice = LazySuspendFun<CameraDevice> {
 *   val foo = otherSuspendingCall()
 *   suspendCoroutine { cont -> cont.resume(foo.use()) }
 * </code>
 */
class LazySuspendFun<out T : Any>(private val f: suspend () -> T) {
    private lateinit var cachedValue: T

    // @Synchronized throws "IllegalMonitorStateException: did not unlock monitor on object of type"
    suspend operator fun invoke(): T {
        if (!::cachedValue.isInitialized) {
            //synchronized(cachedValue) {
            if (!::cachedValue.isInitialized) {
                cachedValue = f()
            }
            //}

        }
        return cachedValue
    }
}