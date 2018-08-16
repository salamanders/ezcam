package info.benjaminhill.deconcamera

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Work out the dangerous permissions listed in the AndroidManifest.xml (dynamically)
 * before diving into the app: `runAfterAllPermissionsGranted { yourCode }`
 *
 * TODO: Seems to double up the code when opening a camera?
 */
abstract class EZPermissionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Don't ignore exceptions in coroutines https://github.com/Kotlin/kotlinx.coroutines/issues/148#issuecomment-338101986
        val baseUEH = Thread.getDefaultUncaughtExceptionHandler()!!
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // this may double log the error on older versions of android
            Log.w(TAG, "FATAL EXCEPTION: ${thread.name} $error")
            Log.w(TAG, error)
            baseUEH.uncaughtException(thread, error)
            throw error
        }
    }

    /** Block your code in onResume with this */
    protected fun hasAllRequiredPermissions() = missingPermissions.isEmpty()

    override fun onStart() {
        super.onStart()

        if (hasAllRequiredPermissions()) {
            Log.i(TAG, "We already have all the permissions we needed, no need to get involved")
        } else {
            // This is a prototype so we skip the right way to do permissions (with reasons given first, fallback plan, etc)
            Log.i(TAG, "Requesting permissions, be back soon.")
            logPermissions()
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), SIMPLE_PERMISSION_ID)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, grantPermissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == SIMPLE_PERMISSION_ID) {
            Log.i(TAG, "Permission grant result: ${grantPermissions.joinToString()}=${grantResults.joinToString()}")
            logPermissions()
            if (hasAllRequiredPermissions()) {
                Log.i(TAG, "User granted all permissions that we requested, the next onResume should work")
            } else {
                Log.w(TAG, "User declined required permissions: ${missingPermissions.joinToString()}")
                Toast.makeText(this, "Please restart the app after allowing access to: ${missingPermissions.joinToString()}", Toast.LENGTH_LONG).show()
            }
        } else {
            super.onRequestPermissionsResult(requestCode, grantPermissions, grantResults)
        }
    }

    private fun logPermissions() = requiredPermissions.forEach {
        Log.i(TAG, "Permission: $it; missing: ${it in missingPermissions}")
    }

    private val requiredPermissions
        get() = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
                .filterNotNull().toSet()

    private val missingPermissions
        get() = requiredPermissions
                .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
                .toSet()

    companion object {
        private const val SIMPLE_PERMISSION_ID = 42
        private const val TAG = "ezpermissions"
    }
}