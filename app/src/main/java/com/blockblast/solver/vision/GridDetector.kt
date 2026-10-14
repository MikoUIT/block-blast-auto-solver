package com.blockblast.solver.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Locates the 8x8 playfield inside a full-screen frame.
 *
 * The first two attempts failed in instructive ways and are worth recording:
 *
 *  - *outermost strong lines*: latched onto the status bar/banners, produced a different rectangle
 *    on nearly every frame;
 *  - *score candidate squares by 8 internal lines*: better, but the strongest lines are the pieces
 *    **inside** the board, not its border, so the score was ambiguous and small squares sometimes
 *    won.
 *
 * Real frames from the phone (720x1560) showed the truth: the board's grid is a strictly periodic
 * pattern — vertical lines at x = 43, 123, 203, 282, 361, 441, 520, 600 (step ≈ 79.5). The pieces
 * sit on top of that period. So the robust discriminator is **periodicity**, not line strength:
 *
 *  1. Build horizontal and vertical edge-energy projections.
 *  2. Find the dominant period of each projection by **autocorrelation** over a plausible
 *     cell-size range. A periodic grid produces a strong autocorrelation peak at the cell pitch;
 *     arbitrary UI does not.
 *  3. Locate the phase (offset) that best aligns the grid lines of that pitch with the projection.
 *  4. The board is 8 cells wide/tall at that pitch, and the two axes must agree on the pitch
 *     (cells are square).
 *
 * This cannot be faked by a button or a banner, because it never looks at how *strong* a line is —
 * only at how regularly the lines repeat.
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

        fun cellCenter(row: Int, col: Int): Pair<Int, Int> {
            val x = (left + (col + 0.5) * cellWidth).toInt()
            val y = (top + (row + 0.5) * cellHeight).toInt()
            return x to y
        }

        fun cellBounds(row: Int, col: Int, inset: Int = 0): IntArray {
            val l = (left + col * cellWidth).toInt() + inset
            val t = (top + row * cellHeight).toInt() + inset
            val r = (left + (col + 1) * cellWidth).toInt() - inset
            val b = (top + (row + 1) * cellHeight).toInt() - inset
            return intArrayOf(l, t, r, b)
        }

        fun isNear(other: GridGeometry, tolerance: Int): Boolean =
            abs(left - other.left) <= tolerance && abs(top - other.top) <= tolerance &&
                abs(right - other.right) <= tolerance && abs(bottom - other.bottom) <= tolerance
    }

    /** The result of a detection, including the grid pitch, for diagnostics. */
    data class Detection(
        val geometry: GridGeometry,
        val cellPitch: Double,
        val confidence: Double,
    )

    fun detect(image: GrayImage): GridGeometry? = detectDetailed(image)?.geometry

    fun detectDetailed(image: GrayImage): Detection? {
        val colEnergy = projectionX(image)
        val rowEnergy = projectionY(image)

        // The X axis carries the vertical grid lines, which are long and unbroken, so its pitch is
        // the reliable one. (Measured on real frames: pitchX is a rock-steady 79 px across frames,
        // while the Y projection is weaker and its autocorrelation picks false periods like 31 or
        // 159 — i.e. pitch/2.5 and pitch×2.) So: find the pitch on X, then confirm it on Y *within a
        // narrow window around it* instead of searching the whole range.
        val pitchX = findPitch(colEnergy) ?: return null
        val pitchY = findPitchNear(rowEnergy, pitchX.pitch) ?: return null

        // Cells are square, so the two pitches must match. When they do (within a small tolerance)
        // we average for sub-pixel accuracy; when Y disagrees we trust X, whose long vertical grid
        // lines make it the more stable signal across frames.
        val agree = abs(pitchX.pitch - pitchY.pitch) <= pitchX.pitch * config.pitchAgreement
        val pitch = if (agree) (pitchX.pitch + pitchY.pitch) / 2.0 else pitchX.pitch
        if (pitch < config.minCellPixels) return null

        val span = pitch * 8
        if (span > min(image.width, image.height)) return null

        // When the axes disagree, don't trust the noisy Y phase — re-derive the top edge purely from
        // the row projection using the X pitch.
        val topPhase = if (agree) pitchY.phase else bestPhaseIndex(rowEnergy, pitch)
        val left = bestPhase(colEnergy, pitch, pitchX.phase, image.width, span) ?: return null
        val top = bestPhase(rowEnergy, pitch, topPhase, image.height, span) ?: return null

        val geom = GridGeometry(
            left = left,
            top = top,
            right = (left + span).toInt(),
            bottom = (top + span).toInt(),
        ).let {
            GridGeometry(
                it.left.coerceIn(0, image.width),
                it.top.coerceIn(0, image.height),
                it.right.coerceIn(0, image.width),
                it.bottom.coerceIn(0, image.height),
            )
        }
        if (geom.width < config.minBoardPixels || geom.height < config.minBoardPixels) return null

        val confidence = pitchX.strength
        if (confidence < config.minConfidence) return null
        return Detection(geom, pitch, confidence)
    }

    /**
     * Finds the pitch near a known one. Used for the axis whose autocorrelation is noisy: we only
     * accept a period within ±25% of the reference, which rules out the half/double false peaks.
     */
    private fun findPitchNear(energy: IntArray, reference: Double): Pitch? {
        val lo = max(config.minCellPixels, (reference * 0.75).toInt())
        val hi = min(min(energy.size / 8, config.maxCellPixels), (reference * 1.25).toInt())
        if (lo > hi) return null
        return findPitch(energy, lo, hi)
    }

    /** A detected pitch (cell size in pixels) plus its best phase and normalised autocorrelation. */
    private data class Pitch(val pitch: Double, val phase: Int, val strength: Double)

    /**
     * Finds the dominant repetition period in an energy projection by autocorrelation.
     *
     * For each candidate lag `p` we sum `energy[i] * energy[i + p]` over overlaps. A grid whose lines
     * repeat every `p` pixels spikes here. The result is normalised by the zero-lag energy so
     * different frames are comparable.
     */
    private fun findPitch(energy: IntArray): Pitch? =
        findPitch(energy, config.minCellPixels, min(energy.size / 8, config.maxCellPixels))

    private fun findPitch(energy: IntArray, minP: Int, maxP: Int): Pitch? {
        val mean = energy.average()
        // Work with the mean-removed signal so a constant offset does not dominate.
        val centred = DoubleArray(energy.size) { energy[it] - mean }

        var bestP = -1
        var bestScore = 0.0
        for (p in minP..maxP) {
            var sum = 0.0
            var count = 0
            var i = 0
            while (i + p < energy.size) {
                sum += centred[i] * centred[i + p]
                count++
                i++
            }
            if (count == 0) continue
            val score = sum / count
            // Bias toward the *smallest* strong pitch: the grid's fundamental period, not its
            // multiples (2p, 3p also correlate, and would give a coarser false grid).
            if (score > bestScore * 1.02) {
                bestScore = score
                bestP = p
            }
        }
        if (bestP < 0) return null

        val norm = autocorrelationNorm(centred)
        val strength = if (norm <= 0.0) 0.0 else bestScore / norm
        val phase = bestPhaseIndex(energy, bestP.toDouble())
        return Pitch(bestP.toDouble(), phase, strength.coerceIn(0.0, 1.0))
    }

    private fun autocorrelationNorm(centred: DoubleArray): Double {
        var sum = 0.0
        for (v in centred) sum += v * v
        return if (centred.isEmpty()) 0.0 else sum / centred.size
    }

    /** Finds the offset in [0, pitch) where grid lines best line up with the projection. */
    private fun bestPhaseIndex(energy: IntArray, pitch: Double): Int {
        var bestPhase = 0
        var bestSum = Long.MIN_VALUE
        val steps = pitch.toInt().coerceAtLeast(1)
        for (offset in 0 until steps) {
            var sum = 0L
            var k = 0
            while (true) {
                val idx = (offset + k * pitch).toInt()
                if (idx >= energy.size) break
                sum += energy[idx]
                k++
            }
            if (sum > bestSum) {
                bestSum = sum
                bestPhase = offset
            }
        }
        return bestPhase
    }

    /**
     * Chooses the board's start coordinate on one axis.
     *
     * A grid is periodic, so every phase that lands the lines on the same repeated pattern scores
     * equally if we just sum line energy — which is how the detector ended up one cell high on the
     * synthetic test (top=49 instead of 120; 49+70=119). The fix is to weight the **outer border**
     * lines (the two ends of the 8-cell span) far more than the interior ones: only the true board
     * edge has a strong line *and* relatively empty space just beyond it.
     */
    private fun bestPhase(
        energy: IntArray,
        pitch: Double,
        phaseHint: Int,
        limit: Int,
        span: Double,
    ): Int? {
        var bestStart = -1
        var bestScore = Double.NEGATIVE_INFINITY
        // Scan the entire axis, stepping half a cell. Restricting to a window around the hint is not
        // enough: the hint itself can sit a full cell (or more) off, because a periodic pattern
        // correlates equally at every repeat.
        val step = (pitch * 0.25).toInt().coerceAtLeast(1)
        var start = 0
        while (start + span <= limit) {
            val firstLine = maxWindow(energy, start - 2, start + 2).toDouble()
            val lastLine = maxWindow(energy, (start + span).toInt() - 2, (start + span).toInt() + 2).toDouble()
            // Just outside the span there should be little grid energy — this distinguishes the
            // true edge from a periodic repeat one cell inward.
            val beforeOut = maxWindow(energy, start - (pitch * 0.4).toInt() - 2, start - (pitch * 0.4).toInt() + 2).toDouble()
            val afterOut = maxWindow(
                energy,
                (start + span + pitch * 0.4).toInt() - 2,
                (start + span + pitch * 0.4).toInt() + 2,
            ).toDouble()

            var interior = 0.0
            for (i in 1..7) {
                val idx = (start + i * pitch).toInt()
                interior += maxWindow(energy, idx - 2, idx + 2)
            }
            // Border strength matters most; interior only breaks ties; empty surroundings confirm it.
            val score = (firstLine + lastLine) * 2.0 + interior * 0.3 - (beforeOut + afterOut) * 0.5
            if (score > bestScore) {
                bestScore = score
                bestStart = start
            }
            start += step
        }
        // Refine to the pixel around the coarse winner.
        if (bestStart < 0) return null
        var refined = bestStart
        var refinedScore = bestScore
        for (s in (bestStart - step)..(bestStart + step)) {
            if (s < 0 || s + span > limit) continue
            val firstLine = maxWindow(energy, s - 2, s + 2).toDouble()
            val lastLine = maxWindow(energy, (s + span).toInt() - 2, (s + span).toInt() + 2).toDouble()
            val beforeOut = maxWindow(energy, s - (pitch * 0.4).toInt() - 2, s - (pitch * 0.4).toInt() + 2).toDouble()
            val afterOut = maxWindow(energy, (s + span + pitch * 0.4).toInt() - 2, (s + span + pitch * 0.4).toInt() + 2).toDouble()
            var interior = 0.0
            for (i in 1..7) interior += maxWindow(energy, (s + i * pitch).toInt() - 2, (s + i * pitch).toInt() + 2)
            val score = (firstLine + lastLine) * 2.0 + interior * 0.3 - (beforeOut + afterOut) * 0.5
            if (score > refinedScore) {
                refinedScore = score
                refined = s
            }
        }
        return refined
    }

    private fun maxWindow(energy: IntArray, from: Int, to: Int): Int {
        var m = 0
        for (i in max(0, from)..min(energy.size - 1, to)) if (energy[i] > m) m = energy[i]
        return m
    }

    private fun projectionX(image: GrayImage): IntArray {
        val out = IntArray(image.width)
        val px = image.pixels
        for (y in 1 until image.height) {
            val base = y * image.width
            val prev = (y - 1) * image.width
            for (x in 1 until image.width) {
                out[x] += abs(px[base + x] - px[base + x - 1]) + abs(px[base + x] - px[prev + x])
            }
        }
        return out
    }

    private fun projectionY(image: GrayImage): IntArray {
        val out = IntArray(image.height)
        val px = image.pixels
        for (y in 1 until image.height) {
            val base = y * image.width
            val prev = (y - 1) * image.width
            var sum = 0
            for (x in 1 until image.width) {
                sum += abs(px[base + x] - px[base + x - 1]) + abs(px[base + x] - px[prev + x])
            }
            out[y] = sum
        }
        return out
    }
}

/** Tuning knobs for [GridDetector]. */
data class DetectorConfig(
    /** Minimum board side in pixels. */
    val minBoardPixels: Int = 320,
    /** Plausible cell size range in pixels (board spans 8 cells). */
    val minCellPixels: Int = 24,
    val maxCellPixels: Int = 200,
    /** The two axes must agree on the cell pitch within this fraction. */
    val pitchAgreement: Double = 0.12,
    /** Minimum normalised autocorrelation to trust the pitch. */
    val minConfidence: Double = 0.05,
) {
    companion object {
        val DEFAULT = DetectorConfig()
    }
}
