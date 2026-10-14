package com.blockblast.solver.vision

import com.blockblast.solver.model.Board

/**
 * Reads which of the 64 board cells are occupied.
 *
 * The hard requirement is theme independence: the recogniser must never assume a particular block
 * colour, because Block Blast re-skins constantly. It therefore decides occupancy *locally* — a cell
 * is "filled" when its interior is consistently different from the board's own background, where
 * that background is estimated from the cells themselves rather than hard-coded.
 *
 * Algorithm per cell:
 *  1. Estimate the board background as the median interior luminance across all 64 cell centres.
 *     Most boards have more empty than filled cells, so the median lands on the empty tone.
 *  2. For each cell, compare its interior mean against the background. A filled cell deviates by
 *     more than an adaptive margin derived from the board's own spread.
 *  3. Guard with an edge/ uniformity check so grid lines and rounded corners do not read as fills.
 */
class BoardRecognizer(
    private val config: RecognizerConfig = RecognizerConfig.DEFAULT,
) {
    /** Occupancy plus a per-cell confidence, so the debug overlay can show how sure we are. */
    data class RecognizedBoard(
        val board: Board,
        val background: Double,
        val threshold: Double,
        val cellMeans: DoubleArray,
        val cellConfidence: DoubleArray,
    )

    fun recognize(image: GrayImage, geometry: GridDetector.GridGeometry): RecognizedBoard {
        val means = DoubleArray(Board.CELL_COUNT)
        for (r in 0 until Board.SIZE) {
            for (c in 0 until Board.SIZE) {
                means[r * Board.SIZE + c] = interiorMean(image, geometry, r, c)
            }
        }
        val background = median(means)
        val spread = robustSpread(means, background)
        val threshold = maxOf(config.minAbsoluteMargin, spread * config.marginSpreadFactor)

        var mask = 0L
        val confidence = DoubleArray(Board.CELL_COUNT)
        for (i in means.indices) {
            val deviation = kotlin.math.abs(means[i] - background)
            val filled = deviation > threshold
            if (filled) mask = mask or (1L shl i)
            confidence[i] = (deviation / (threshold * 2.0)).coerceIn(0.0, 1.0)
        }
        return RecognizedBoard(
            board = Board.fromMask(mask),
            background = background,
            threshold = threshold,
            cellMeans = means,
            cellConfidence = confidence,
        )
    }

    /**
     * Mean luminance of a cell's interior, with a margin trimmed off so the grid lines and the
     * rounded block borders do not pollute the reading.
     */
    private fun interiorMean(image: GrayImage, g: GridDetector.GridGeometry, row: Int, col: Int): Double {
        val bounds = g.cellBounds(row, col)
        val insetX = ((bounds[2] - bounds[0]) * config.interiorInset).toInt()
        val insetY = ((bounds[3] - bounds[1]) * config.interiorInset).toInt()
        return image.meanRect(
            left = bounds[0] + insetX,
            top = bounds[1] + insetY,
            right = bounds[2] - insetX,
            bottom = bounds[3] - insetY,
        )
    }

    private fun median(values: DoubleArray): Double {
        val sorted = values.sortedArray()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** A spread estimate that ignores the tails, using the inter-quartile range. */
    private fun robustSpread(values: DoubleArray, center: Double): Double {
        val sorted = values.sortedArray()
        if (sorted.isEmpty()) return 0.0
        val q1 = sorted[(sorted.size * 0.25).toInt().coerceIn(0, sorted.size - 1)]
        val q3 = sorted[(sorted.size * 0.75).toInt().coerceIn(0, sorted.size - 1)]
        return maxOf(q3 - q1, kotlin.math.abs(center) * 0.02)
    }

    companion object {
        /** Convenience for callers that only need the crate mask. */
        fun occupancyOnly(image: GrayImage, geometry: GridDetector.GridGeometry): Board =
            BoardRecognizer().recognize(image, geometry).board
    }
}

/** Thresholding parameters for [BoardRecognizer]. */
data class RecognizerConfig(
    /** Fraction of a cell trimmed from each side before sampling its interior. */
    val interiorInset: Double = 0.22,
    /** Absolute floor on the fill/empty margin, guarding against near-uniform boards. */
    val minAbsoluteMargin: Double = 18.0,
    /** How many spreads of separation count as a fill. */
    val marginSpreadFactor: Double = 0.6,
) {
    companion object {
        val DEFAULT = RecognizerConfig()
    }
}
