package info.benjaminhill.ezcam

import android.media.Image

open class EasyImage(
        val width: Int,
        val height: Int,
        val plane: ByteArray
) {
    constructor(image: Image) : this(
            image.width,
            image.height,
            image.planes[0].buffer!!.let { buffer ->
                ByteArray(buffer.remaining()).apply { buffer.get(this) }
            })
}