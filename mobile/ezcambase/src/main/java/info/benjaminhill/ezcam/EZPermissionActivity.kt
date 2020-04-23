package info.benjaminhill.ezcam

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.ajalt.timberkt.w

/**
 * Work out the dangerous permissions listed in the AndroidManifest.xml (dynamically)
 * before diving into the onResume: `if (!hasAllRequiredPermissions()) { complain loudly and bail }`
 *
 */
abstract class EZPermissionActivity : ScopedAppActivity() {

    /** Block your code in onResume with this */
    protected fun hasAllRequiredPermissions() = missingPermissions.isEmpty()

    override fun onStart() {
        super.onStart()

        if (hasAllRequiredPermissions()) {
            w { "We already have all the permissions we needed, no need to get involved" }
        } else {
            // This is a prototype so we skip the right way to do permissions (with reasons given first, fallback plan, etc)
            w { "Requesting permissions, be back soon." }
            logPermissions()
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), SIMPLE_PERMISSION_ID)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, grantPermissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == SIMPLE_PERMISSION_ID) {
            w { "Permission grant result: ${grantPermissions.joinToString()}=${grantResults.joinToString()}" }
            logPermissions()
            if (hasAllRequiredPermissions()) {
                w { "User granted all permissions that we requested, the next onResume should work" }
            } else {
                w { "User declined required permissions: ${missingPermissions.joinToString()}" }
                Toast.makeText(this, "Please restart the app after allowing access to: ${missingPermissions.joinToString()}", Toast.LENGTH_LONG).show()
            }
        } else {
            super.onRequestPermissionsResult(requestCode, grantPermissions, grantResults)
        }
    }

    private fun logPermissions() = requiredPermissions.forEach {
        w { "Permission: $it; missing: ${it in missingPermissions}" }
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