package info.benjaminhill.ezcam

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.Image
import android.net.Uri
import android.provider.MediaStore
import androidx.core.app.ActivityCompat
import com.github.ajalt.timberkt.i
import java.io.File
import java.nio.channels.Channels

fun Image.saveToMediaStore(context: Context, mimeType: String = "image/jpeg"): String {
    val imageUri = generateUri(mimeType, context)
    context.contentResolver.openOutputStream(imageUri)!!.use { out ->
        Channels.newChannel(out).write(planes[0].buffer!!)
        i { "Finished writing $mimeType image to $imageUri, notifying MediaStore" }
    }
    markDone(imageUri, context)
    return imageUri.toString()
}

fun Image.saveToFileSystem(fileName: String, context: Context): String {
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
        throw IllegalStateException("You don't have the required permission WRITE_EXTERNAL_STORAGE, try guarding with EZPermission.")
    }
    return File(context.filesDir, fileName).apply {
        val buffer = planes[0].buffer!!
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        writeBytes(bytes)
    }.absolutePath
}

fun Bitmap.saveToMediaStore(context: Context, mimeType: String = "image/png"): String {
    val imageUri = generateUri(mimeType, context)
    context.contentResolver.openOutputStream(imageUri)!!.use { out ->
        compress(Bitmap.CompressFormat.PNG, 100, out)
        i { "Finished writing $mimeType image to $imageUri, notifying MediaStore" }
    }
    markDone(imageUri, context)
    return imageUri.toString()
}

private fun markDone(imageUri: Uri, context: Context) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.IS_PENDING, 0)
    }
    context.contentResolver.update(imageUri, values, null, null)
}

private fun generateUri(mimeType: String, context: Context): Uri {
    val values = ContentValues().apply {
        val name = "image_${System.currentTimeMillis()}.${mimeType.split("/")[1]}"
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ezcam/")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }

    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)!!
    return context.contentResolver.insert(collection, values)!!
}

