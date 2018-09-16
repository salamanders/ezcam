package info.benjaminhill.deconcamera

import android.os.Handler
import timber.log.Timber


/** Repeating callback that starts in a running state */
class SetInterval(private val delayMs: Long = 1_000L, action: () -> Unit) {
    val handler = Handler()

    private val internalRunnable = object : Runnable {
        override fun run() {
            Timber.i("SetInterval.run")
            handler.postDelayed(this, delayMs) // does not depend on how long the action takes
            action()
        }
    }

    init {
        handler.postDelayed(internalRunnable, delayMs)
    }

    fun stop() {
        Timber.i("SetInterval.stop")
        handler.removeCallbacks(internalRunnable)
    }
}