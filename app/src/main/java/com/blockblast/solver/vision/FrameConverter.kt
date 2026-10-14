package com.blockblast.solver.vision

import android.media.Image

/**
 * Converts an Android `ImageReader` frame into a [GrayImage].
 *
 * The catch is `rowStride`: camera/display buffers are frequently padded, and the plane's byte
 * buffer is wider than `width * 4`. The buffer position also usually needs rewinding before reading.
 * Getting this wrong yields a sheared image, so the stride is handled explicitly here and nowhere
 * else.
 */
object FrameConverter {

    fun toGray(image: Image): GrayImage {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height

        val out = IntArray(width * height)
        val rowBytes = ByteArray(rowStride)
        for (y in 0 until height) {
            buffer.get(rowBytes, 0, minOf(rowStride, buffer.remaining()))
            val rowBase = y * width
            for (x in 0 until width) {
                val idx = x * pixelStride
                if (idx + 3 >= rowBytes.size) break
                val r = rowBytes[idx].toInt() and 0xFF
                val g = rowBytes[idx + 1].toInt() and 0xFF
                val b = rowBytes[idx + 2].toInt() and 0xFF
                out[rowBase + x] = (r * 77 + g * 151 + b * 28) shr 8
            }
        }
        return GrayImage(width, height, out)
    }
}
