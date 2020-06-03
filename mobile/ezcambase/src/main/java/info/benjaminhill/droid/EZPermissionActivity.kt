package info.benjaminhill.droid

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.ajalt.timberkt.e
import com.github.ajalt.timberkt.i
import com.github.ajalt.timberkt.w
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * Work out the dangerous permissions listed in the AndroidManifest.xml (dynamically)
 * before diving into the onResume: `if (!hasAllRequiredPermissions()) { complain loudly and bail }`
 *
 */
abstract class EZPermissionActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    override fun onDestroy() {
        super.onDestroy()
        cancel()
    }

    /** Block your code in onResume with this */
    protected fun hasAllRequiredPermissions() = missingPermissions().isEmpty()

    override fun onStart() {
        super.onStart()

        if (hasAllRequiredPermissions()) {
            i { "We already have all the permissions we needed, no need to get involved" }
        } else {
            // This is a prototype so we skip the right way to do permissions (with reasons given first, fallback plan, etc)
            i { "Requesting permissions, be back soon." }
            logPermissions()
            ActivityCompat.requestPermissions(this, missingPermissions().toTypedArray(), SIMPLE_PERMISSION_ID)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, grantPermissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != SIMPLE_PERMISSION_ID) {
            super.onRequestPermissionsResult(requestCode, grantPermissions, grantResults)
            return
        }

        i { "Permission grant result: ${grantPermissions.joinToString()}=${grantResults.joinToString()}" }
        logPermissions()

        if (!hasAllRequiredPermissions()) {
            e { "User declined required permissions: ${missingPermissions().joinToString()}" }
            Toast.makeText(this, "Please restart the app after allowing access to: ${missingPermissions().joinToString()}", Toast.LENGTH_LONG).show()
            return
        }

        i { "User granted all permissions that we requested, the next onResume should work" }
    }

    private fun logPermissions() = requiredPermissions.forEach {
        w { "Permission: $it; missing: ${it in missingPermissions()}" }
    }

    private val requiredPermissions by lazy {
        packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
                .filterNotNull().toSet()
    }

    private fun missingPermissions() = requiredPermissions
            .asSequence()
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            .toSet()

    companion object {
        private const val SIMPLE_PERMISSION_ID = 4242
    }
}