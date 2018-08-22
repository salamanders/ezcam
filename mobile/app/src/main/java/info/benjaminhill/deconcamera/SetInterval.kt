package info.benjaminhill.deconcamera

import android.os.Handler
import android.util.Log

/** Repeating callback that starts in a running state */
class SetInterval(private val delayMs: Long = 1_000L, action: () -> Unit) {
    val handler = Handler()
    private val internalRunnable = object : Runnable {
        override fun run() {
            Log.i(MainActivity.TAG, "SetInterval.run")
            handler.postDelayed(this, delayMs)
            action()
        }
    }

    init {
        resume()
    }

    fun pause() {
        Log.i(MainActivity.TAG, "SetInterval.pause")
        handler.removeCallbacks(internalRunnable)
    }

    fun resume() {
        Log.i(MainActivity.TAG, "SetInterval.resume")
        handler.postDelayed(internalRunnable, delayMs)
    }
}