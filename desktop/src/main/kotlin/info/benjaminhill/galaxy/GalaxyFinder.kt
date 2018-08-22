package info.benjaminhill.galaxy

import java.io.File
import kotlin.system.measureTimeMillis


fun main(args: Array<String>) {
    println("go!")
    val ms = measureTimeMillis {

        val maxLum = File("/Users/benhill/Downloads/Photos/").walk()

                .filter { it.isImage() }.sortedBy { it.name }
                // .filterIndexed { index, _ -> index % 5 == 0 }
                //.take(50)
                .chunked(3) { forSum ->
                    println("Chunk size for Sum: ${forSum.size}")
                    frameSum(forSum.map { Frame.load(it) })
                            //.also { it.save("temp_sum") } // extend the exposure
                }.chunked(5) {forMedian->
                    println("Chunk size for Median: ${forMedian.size}")
                    frameMedian(forMedian)
                            //.also { it.save("temp_median") }
                }.reduce { acc, frame ->
                    frameMaxLum(listOf(acc, frame)).also {
                        it.save("temp_maxlum")
                    }
                }
                
        maxLum.save("maxLum")
    }
    println("Total time: $ms / 1_000} seconds")
}

fun frameMaxLum(others: Collection<Frame>): Frame {
    require(others.isNotEmpty())
    val width = others.first().width
    val height = others.first().height
    val f = Frame(width, height, "max")
    for (i in 0 until width * height) {
        others.maxBy { it.lum[i] }!!.let { maxFrame ->
            f.red[i] = maxFrame.red[i]
            f.green[i] = maxFrame.green[i]
            f.blue[i] = maxFrame.blue[i]
        }
    }
    f.updateLums()
    return f
}

fun frameSum(others: Collection<Frame>): Frame {
    require(others.isNotEmpty())
    val width = others.first().width
    val height = others.first().height
    val f = Frame(width, height, "sum")
    for (i in 0 until width * height) {
        var r = 0
        var g = 0
        var b = 0
        others.forEach {
            r += it.red[i].toInt() and 0xFF
            g += it.green[i].toInt() and 0xFF
            b += it.blue[i].toInt() and 0xFF
        }
        f.red[i] = minOf(r, 255).toByte()
        f.green[i] = minOf(g, 255).toByte()
        f.blue[i] = minOf(b, 255).toByte()
    }
    f.updateLums()
    return f
}

fun frameMedian(others: Collection<Frame>): Frame {
    require(others.isNotEmpty())
    val width = others.first().width
    val height = others.first().height
    val f = Frame(width, height, "median")
    val isEven = others.size % 2 == 0
    if (isEven) {
        println("Warning: must do averaging when median and even size: ${others.size}")
    }
    val half = Math.floor((others.size - 1) / 2.0).toInt()
    for (i in 0 until width * height) {
        others.sortedBy { it.lum[i] }.let { sortedFrames ->
            val halfFrame = sortedFrames.elementAt(half)
            if (isEven) {
                val halfFramePlus = sortedFrames.elementAt(half + 1)
                f.red[i] = byteAvg(halfFrame.red[i], halfFramePlus.red[i])
                f.green[i] = byteAvg(halfFrame.green[i], halfFramePlus.green[i])
                f.blue[i] = byteAvg(halfFrame.blue[i], halfFramePlus.blue[i])
            } else {
                f.red[i] = halfFrame.red[i]
                f.green[i] = halfFrame.green[i]
                f.blue[i] = halfFrame.blue[i]
            }
        }
    }
    f.updateLums()
    return f
}

fun File.isImage(): Boolean = this.isFile && this.length() > 0 && setOf("png", "jpg", "jpeg", "bmp", "tif", "tiff").contains(this.extension.toLowerCase())
fun byteAvg(vararg others: Byte): Byte = (others.map { it.toInt() and 0xFF }.sum() / others.size).toByte()
