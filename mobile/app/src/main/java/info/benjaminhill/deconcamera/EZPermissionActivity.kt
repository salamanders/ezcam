package info.benjaminhill.deconcamera

import android.content.pm.PackageManager
import android.os.Bundle

import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * Work out the dangerous permissions listed in the AndroidManifest.xml (dynamically)
 * before diving into the onResume: `if (!hasAllRequiredPermissions()) { complain loudly and bail }`
 *
 */
abstract class EZPermissionActivity : ScopedAppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Don't ignore exceptions in coroutines https://github.com/Kotlin/kotlinx.coroutines/issues/148#issuecomment-338101986
        val baseUEH = Thread.getDefaultUncaughtExceptionHandler()!!
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // this may double log the error on older versions of android
            Timber.w("FATAL EXCEPTION: ${thread.name} $error")
            baseUEH.uncaughtException(thread, error)
            throw error
        }
    }

    /** Block your code in onResume with this */
    protected fun hasAllRequiredPermissions() = missingPermissions.isEmpty()

    override fun onStart() {
        super.onStart()

        if (hasAllRequiredPermissions()) {
            Timber.w("We already have all the permissions we needed, no need to get involved")
        } else {
            // This is a prototype so we skip the right way to do permissions (with reasons given first, fallback plan, etc)
            Timber.w("Requesting permissions, be back soon.")
            logPermissions()
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), SIMPLE_PERMISSION_ID)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, grantPermissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == SIMPLE_PERMISSION_ID) {
            Timber.w("Permission grant result: ${grantPermissions.joinToString()}=${grantResults.joinToString()}")
            logPermissions()
            if (hasAllRequiredPermissions()) {
                Timber.w("User granted all permissions that we requested, the next onResume should work")
            } else {
                Timber.w("User declined required permissions: ${missingPermissions.joinToString()}")
                Toast.makeText(this, "Please restart the app after allowing access to: ${missingPermissions.joinToString()}", Toast.LENGTH_LONG).show()
            }
        } else {
            super.onRequestPermissionsResult(requestCode, grantPermissions, grantResults)
        }
    }

    private fun logPermissions() = requiredPermissions.forEach {
        Timber.w("Permission: $it; missing: ${it in missingPermissions}")
    }

    private val requiredPermissions
        get() = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
                .filterNotNull().toSet()

    private val missingPermissions
        get() = requiredPermissions
                .asSequence()
                .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
                .toSet()

    companion object {
        private const val SIMPLE_PERMISSION_ID = 4242
    }
}