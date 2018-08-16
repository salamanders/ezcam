package info.benjaminhill.deconcamera

import kotlinx.coroutines.experimental.CompletableDeferred
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.suspendCoroutine

/**
 * From https://stackoverflow.com/questions/51862715/how-would-i-wrap-this-not-quite-by-lazy-result-caching-function-call-in-idio/51877612#51877612
 * May have to
 * <code>
 * private val cameraCaptureSession = LazySuspendFun<CameraCaptureSession> { cont->
 * launch(UI) {
 * cameraDevice()...
 * </code>
 */
class LazySuspendFun<out T>(private val block: (Continuation<T>) -> Unit) {
    private lateinit var deferred: CompletableDeferred<T>

    suspend operator fun invoke(): T {
        if (!::deferred.isInitialized) {
            deferred = CompletableDeferred()
            try {
                deferred.complete(suspendCoroutine(block))
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
            }
        }
        return deferred.await()
    }
}