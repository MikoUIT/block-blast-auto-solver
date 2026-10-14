package com.blockblast.solver.vision

/**
 * A single-channel (luminance) image: the working representation for all recognition.
 *
 * Colour is deliberately thrown away the moment a frame arrives. Block Blast re-skins itself with
 * wildly different palettes, but a filled cell is always *brighter or darker* than the empty well
 * around it in a locally consistent way — so we keep only brightness and let the geometry do the
 * rest. This class is pure Kotlin (no Android types) so the recogniser can be unit-tested on
 * synthetic images without an emulator.
 */
class GrayImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(pixels.size == width * height) {
            "pixel buffer ${pixels.size} does not match ${width}x$height"
        }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    fun sample(x: Int, y: Int): Int {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, height - 1)
        return pixels[cy * width + cx]
    }

    /**
     * Mean luminance of an axis-aligned rectangle, clipped to the image. Returns 0 for an empty
     * rectangle so callers never divide by zero.
     */
    fun meanRect(left: Int, top: Int, right: Int, bottom: Int): Double {
        val l = left.coerceIn(0, width - 1)
        val t = top.coerceIn(0, height - 1)
        val r = right.coerceIn(l + 1, width)
        val b = bottom.coerceIn(t + 1, height)
        var sum = 0L
        var count = 0
        for (y in t until b) {
            val base = y * width
            for (x in l until r) {
                sum += pixels[base + x]
                count++
            }
        }
        return if (count == 0) 0.0 else sum.toDouble() / count
    }

    /**
     * Standard deviation of luminance inside a rectangle — a cheap "is this region uniform?" probe.
     */
    fun stdDevRect(left: Int, top: Int, right: Int, bottom: Int): Double {
        val l = left.coerceIn(0, width - 1)
        val t = top.coerceIn(0, height - 1)
        val r = right.coerceIn(l + 1, width)
        val b = bottom.coerceIn(t + 1, height)
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in t until b) {
            val base = y * width
            for (x in l until r) {
                val v = pixels[base + x].toDouble()
                sum += v
                sumSq += v * v
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        val variance = (sumSq / count) - mean * mean
        return if (variance <= 0.0) 0.0 else kotlin.math.sqrt(variance)
    }

    companion object {
        /** Builds a grey image from an ARGB_8888 int buffer, using Rec. 601 luma weights. */
        fun fromArgb(argb: IntArray, width: Int, height: Int): GrayImage {
            val out = IntArray(width * height)
            for (i in out.indices) {
                val c = argb[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // Integer luma, biased to keep the whole pipeline in ints.
                out[i] = (r * 77 + g * 151 + b * 28) shr 8
            }
            return GrayImage(width, height, out)
        }
    }
}
