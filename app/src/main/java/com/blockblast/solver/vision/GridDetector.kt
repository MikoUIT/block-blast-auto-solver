package com.blockblast.solver.vision

/**
 * Locates the 8x8 playfield inside a full-screen frame and derives the geometry needed to read
 * individual cells.
 *
 * The game's board is a large, roughly square, high-contrast region in the upper ~70% of the screen.
 * Rather than keying off any colour (which the themes change), the detector works on *edge energy*:
 *
 *  1. Compute a horizontal and vertical gradient magnitude map.
 *  2. Sum that energy per row and per column (projections).
 *  3. The board's border produces two strong peaks per axis — take the outermost pair that is
 *     roughly square and large enough to hold an 8x8 grid.
 *
 * This is deliberately robust to re-skins and effects, and when it fails the caller falls back to a
 * user-drawn calibration rectangle.
 */
class GridDetector(
    private val config: DetectorConfig = DetectorConfig.DEFAULT,
) {
    /** Geometry of a detected board: an axis-aligned rectangle plus the derived 8x8 cell size. */
    data class GridGeometry(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val cellWidth: Double get() = width / 8.0
        val cellHeight: Double get() = height / 8.0

        /** Centre pixel of cell (row, col), for tapping and for sampling. */
        fun cellCenter(row: Int, col: Int): Pair<Int, Int> {
            val x = (left + (col + 0.5) * cellWidth).toInt()
            val y = (top + (row + 0.5) * cellHeight).toInt()
            return x to y
        }

        /** Cell rectangle in pixel coordinates, inset by [inset] on each side. */
        fun cellBounds(row: Int, col: Int, inset: Int = 0): IntArray {
            val l = (left + col * cellWidth).toInt() + inset
            val t = (top + row * cellHeight).toInt() + inset
            val r = (left + (col + 1) * cellWidth).toInt() - inset
            val b = (top + (row + 1) * cellHeight).toInt() - inset
            return intArrayOf(l, t, r, b)
        }
    }

    /** Finds the board, or null when no plausible 8x8 region exists. */
    fun detect(image: GrayImage): GridGeometry? {
        val hGrad = gradientMagnitudeX(image)
        val vGrad = gradientMagnitudeY(image)
        val rowEnergy = IntArray(image.height)
        val colEnergy = IntArray(image.width)
        for (y in 1 until image.height - 1) {
            val base = y * image.width
            for (x in 1 until image.width - 1) {
                val g = hGrad[base + x] + vGrad[base + x]
                rowEnergy[y] += g
                colEnergy[x] += g
            }
        }

        val topBound = peakBoundary(rowEnergy, fromStart = true)
        val bottomBound = peakBoundary(rowEnergy, fromStart = false)
        val leftBound = peakBoundary(colEnergy, fromStart = true)
        val rightBound = peakBoundary(colEnergy, fromStart = false)

        if (topBound >= bottomBound || leftBound >= rightBound) return null

        val geom = GridGeometry(leftBound, topBound, rightBound, bottomBound)
        if (geom.width < config.minBoardPixels || geom.height < config.minBoardPixels) return null
        val aspect = geom.width.toDouble() / geom.height
        if (aspect < config.minAspect || aspect > config.maxAspect) return null
        return geom
    }

    /**
     * Finds the strongest boundary on one axis. We score each candidate position by how much energy
     * sits on it versus the surrounding rows/cols, and pick the outermost position that clears a
     * fraction of the global maximum. Starting from the edge makes the board border win over the
     * internal grid lines.
     */
    private fun peakBoundary(energy: IntArray, fromStart: Boolean): Int {
        val maxEnergy = energy.maxOrNull() ?: return -1
        if (maxEnergy <= 0) return -1
        val threshold = maxEnergy * config.boundaryFraction
        val indices = if (fromStart) energy.indices else energy.indices.reversed()
        for (i in indices) {
            if (energy[i] >= threshold) return i
        }
        return -1
    }

    private fun gradientMagnitudeX(image: GrayImage): IntArray {
        val out = IntArray(image.width * image.height)
        val px = image.pixels
        for (y in 0 until image.height) {
            val base = y * image.width
            for (x in 1 until image.width) {
                out[base + x] = kotlin.math.abs(px[base + x] - px[base + x - 1])
            }
        }
        return out
    }

    private fun gradientMagnitudeY(image: GrayImage): IntArray {
        val out = IntArray(image.width * image.height)
        val px = image.pixels
        for (y in 1 until image.height) {
            val base = y * image.width
            val prev = (y - 1) * image.width
            for (x in 0 until image.width) {
                out[base + x] = kotlin.math.abs(px[base + x] - px[prev + x])
            }
        }
        return out
    }
}

/** Tuning knobs for [GridDetector]. */
data class DetectorConfig(
    /** Minimum board side in pixels below which the detection is rejected. */
    val minBoardPixels: Int = 240,
    /** Allowed width/height ratio range for the board (it is square-ish). */
    val minAspect: Double = 0.75,
    val maxAspect: Double = 1.35,
    /** A boundary must carry at least this fraction of the strongest line's energy. */
    val boundaryFraction: Double = 0.55,
) {
    companion object {
        val DEFAULT = DetectorConfig()
    }
}
