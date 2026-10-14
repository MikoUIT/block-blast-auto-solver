package com.blockblast.solver.model

/**
 * Generates and holds every piece shape that can appear in Block Blast, plus a lookup that maps a
 * raw recognised mask back to a canonical catalogue entry.
 *
 * Two things matter here:
 *
 *  1. **Orientation is significant.** The player cannot rotate pieces, so a horizontal domino and
 *     a vertical domino are distinct game objects even though they are the same polyomino. The
 *     catalogue therefore stores every distinct orientation as its own entry.
 *  2. **Recognition must be tolerant.** The look-up canonicalises whatever mask the vision layer
 *     produced (trimming empty borders, then comparing across the full orientation set) so a shape
 *     detected one cell off still resolves to the right piece.
 */
object PieceCatalog {

    // Declared first: Kotlin initialises object properties in declaration order, and `all` below
    // depends on this table. Moving it after `all` would leave it null during `all`'s construction.
    private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    /** All distinct orientations of every polyomino up to 5 cells, plus the 3x3 square and its caps. */
    val all: List<Piece> = buildList {
        // Every free polyomino with 1..5 cells, expanded to all of its orientations.
        for (size in 1..5) {
            for (shape in polyominoes(size)) {
                for (orientation in orientations(shape)) {
                    add(orientation)
                }
            }
        }
        // Block Blast also ships flat 3x3 blocks and 3x3 shapes missing a corner / edge.
        addSquareFamily()
    }.distinctBy { shapeKey(it) }
        .mapIndexed { index, piece -> piece.copy(sourceId = "P$index") }

    private val canonicalMasks: Set<String> = all.map { shapeKey(it) }.toSet()

    /** A game-piece identity: size + mask + cell count, ignoring the display id. */
    fun shapeKey(piece: Piece): String = "${piece.rows}x${piece.cols}:${piece.mask}:${piece.cellCount}"

    /**
     * Resolves a recognised mask (as produced from a screenshot) to a catalogue entry.
     *
     * Accepts the rectangle the vision layer cut out — borders are trimmed automatically. Returns
     * null only when no catalogue shape matches, which in practice means the recognition was wrong.
     */
    fun match(rows: Int, cols: Int, cells: List<Pair<Int, Int>>): Piece? {
        if (cells.isEmpty()) return null
        val trimmed = Piece.fromGrid("vision", gridOf(rows, cols, cells))
        return all.firstOrNull { shapeKey(it) == shapeKey(trimmed) }
    }

    fun match(piece: Piece): Piece? = all.firstOrNull { shapeKey(it) == shapeKey(piece) }

    fun isKnown(piece: Piece): Boolean = canonicalMasks.contains(shapeKey(piece))

    private fun gridOf(rows: Int, cols: Int, cells: List<Pair<Int, Int>>): List<List<Boolean>> {
        val grid = MutableList(rows) { MutableList(cols) { false } }
        for ((r, c) in cells) {
            if (r in 0 until rows && c in 0 until cols) grid[r][c] = true
        }
        return grid
    }

    // --- polyomino generation ------------------------------------------------------------------

    private fun polyominoes(size: Int): List<Set<Int>> {
        if (size <= 0) return emptyList()
        // Grow from a single cell by repeatedly attaching neighbours, then de-duplicate by
        // translation and by full orientation so only free polyominoes survive.
        var current: List<Set<Pair<Int, Int>>> = listOf(setOf(0 to 0))
        repeat(size - 1) {
            val grown = mutableListOf<Set<Pair<Int, Int>>>()
            for (shape in current) {
                for ((r, c) in shape) {
                    for ((dr, dc) in NEIGHBOURS) {
                        val next = r + dr to c + dc
                        if (next in shape) continue
                        grown.add(normalizeCells(shape + next))
                    }
                }
            }
            current = grown.distinctBy { cellKey(it) }
        }
        return current.map { shape -> shape.map { (r, c) -> r * 16 + c }.toSet() }
    }

    private fun normalizeCells(cells: Set<Pair<Int, Int>>): Set<Pair<Int, Int>> {
        val minR = cells.minOf { it.first }
        val minC = cells.minOf { it.second }
        return cells.map { (r, c) -> (r - minR) to (c - minC) }.toSet()
    }

    private fun cellKey(cells: Set<Pair<Int, Int>>): String =
        cells.sortedWith(compareBy({ it.first }, { it.second })).joinToString(",") { "${it.first},${it.second}" }

    private fun orientations(cells: Set<Int>): List<Piece> {
        // Decode the packed polyomino, then emit each unique rotation/reflection as a Piece.
        val points = cells.map { it / 16 to it % 16 }.toSet()
        val variants = mutableSetOf<Set<Pair<Int, Int>>>()
        var working = points
        repeat(4) {
            variants.add(normalizeCells(working))
            variants.add(normalizeCells(working.map { (r, c) -> r to -c }.toSet()))
            working = normalizeCells(working.map { (r, c) -> c to -r }.toSet())
        }
        return variants.map { variant ->
            val maxR = variant.maxOf { it.first }
            val maxC = variant.maxOf { it.second }
            val grid = MutableList(maxR + 1) { MutableList(maxC + 1) { false } }
            for ((r, c) in variant) grid[r][c] = true
            Piece.fromGrid("poly", grid)
        }
    }

    private fun MutableList<Piece>.addSquareFamily() {
        // Full 3x3 square.
        add(Piece.fromAscii("sq3", "###\n###\n###"))
        // 3x3 minus a corner (four distinct corners).
        val corners = listOf(0 to 0, 0 to 2, 2 to 0, 2 to 2)
        for ((cr, cc) in corners) {
            val grid = MutableList(3) { r -> MutableList(3) { c -> !(r == cr && c == cc) } }
            add(Piece.fromGrid("sq3corner", grid))
        }
        // 3x3 plus-shape and 2x3 / 3x2 slabs are already produced by the polyomino generator; the
        // square is the only shape the 5-cell cap would otherwise exclude.
    }
}
