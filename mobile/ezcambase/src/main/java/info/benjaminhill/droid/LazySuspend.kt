package info.benjaminhill.droid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext


/**
 * From https://stackoverflow.com/questions/51862715/how-would-i-wrap-this-not-quite-by-lazy-result-caching-function-call-in-idio/51877612#51877612
 * @param initializer function to suspend and call once
 *
 * <code>
 * private val cameraDevice = LazySuspend<CameraDevice> {
 *   val foo = otherSuspendingCall()
 *   suspendCoroutine { cont -> cont.resume(foo.use()) }
 * </code>
 */
internal class LazySuspend<out T : Any>(initializer: suspend () -> T) {
    @Volatile
    private var cachedValue: Any? = UninitializedValue
    private var initializer: (suspend () -> T)? = initializer
    private val mutex = Mutex()

    suspend operator fun invoke(): T {
        val v1 = cachedValue
        if (v1 !== UninitializedValue) {
            @Suppress("UNCHECKED_CAST")
            return v1 as T
        }

        return mutex.withLock {
            val v2 = cachedValue
            if (v2 !== UninitializedValue) {
                @Suppress("UNCHECKED_CAST") (v2 as T)
            } else {
                withContext(Dispatchers.Default) {
                    val typedValue = initializer!!()
                    cachedValue = typedValue
                    initializer = null
                    typedValue
                }
            }
        }
    }

    companion object {
        internal object UninitializedValue
    }
}