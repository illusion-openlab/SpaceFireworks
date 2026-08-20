package tech.illusion.spacefireworks.content

import com.pico.spatial.core.math.Vector3

/**
 * Turns a short word into the throw directions for one shell, so a burst spells it out.
 *
 * ### How this rides on the existing burst
 *
 * A shell's sparks are thrown as `velocity = direction * speed`, and under linear drag a spark's
 * total displacement is `v0/k` -- proportional to that speed. So a **non-unit** direction vector
 * lands its spark proportionally nearer the centre, which is all that is needed to paint a shape
 * instead of a sphere: the glyph is expressed as offsets in the range -1..1 and the existing
 * radius/reach machinery scales it.
 *
 * The letters sit in the X-Y plane with `z = 0` so the word faces the viewer, and they are
 * deliberately thrown *without* the usual per-spark speed jitter, which would otherwise blur the
 * strokes into a cloud.
 *
 * Gravity still applies after the shape forms, so the word holds for a beat and then sags and
 * falls. That is what a real text shell does too.
 */
object FireworkText {

    /** Columns per glyph cell, including the one-column gap between letters. */
    private const val GLYPH_W = 5
    private const val GLYPH_H = 7
    private const val GAP = 1

    /**
     * A 5x7 bitmap for every character the show actually spells.
     *
     * Hand-drawn rather than measured from a real font: at burst resolution a spark is a dot several
     * centimetres across, so anything finer than this grid is lost anyway, and a real font's curves
     * would just sample down to the same handful of points.
     */
    private val GLYPHS: Map<Char, List<String>> =
        mapOf(
            'P' to
                listOf(
                    "####.",
                    "#...#",
                    "#...#",
                    "####.",
                    "#....",
                    "#....",
                    "#....",
                ),
            'S' to
                listOf(
                    ".####",
                    "#....",
                    "#....",
                    ".###.",
                    "....#",
                    "....#",
                    "####.",
                ),
            'i' to
                listOf(
                    "..#..",
                    ".....",
                    ".##..",
                    "..#..",
                    "..#..",
                    "..#..",
                    ".###.",
                ),
            'c' to
                listOf(
                    ".....",
                    ".....",
                    ".###.",
                    "#....",
                    "#....",
                    "#....",
                    ".###.",
                ),
            'o' to
                listOf(
                    ".....",
                    ".....",
                    ".###.",
                    "#...#",
                    "#...#",
                    "#...#",
                    ".###.",
                ),
            't' to
                listOf(
                    ".#...",
                    ".#...",
                    "###..",
                    ".#...",
                    ".#...",
                    ".#...",
                    "..##.",
                ),
            'r' to
                listOf(
                    ".....",
                    ".....",
                    "#.##.",
                    "##...",
                    "#....",
                    "#....",
                    "#....",
                ),
            'y' to
                listOf(
                    ".....",
                    ".....",
                    "#...#",
                    "#...#",
                    ".####",
                    "....#",
                    ".###.",
                ),
        )

    /** True when every character of [word] can be drawn. */
    fun canRender(word: String): Boolean = word.all { GLYPHS.containsKey(it) }

    /**
     * The throw directions that spell [word], centred on the burst and normalised to -1..1.
     *
     * Scaled by *width*, not by the larger axis: a word is much wider than it is tall, and matching
     * its height to the burst radius would put the ends of the word far outside the sky box the
     * show is allowed to use.
     */
    fun directions(word: String): List<Vector3> {
        val cells = word.mapNotNull { GLYPHS[it] }
        if (cells.isEmpty()) return emptyList()

        val totalCols = cells.size * GLYPH_W + (cells.size - 1) * GAP
        val halfCols = totalCols / 2f
        val halfRows = GLYPH_H / 2f
        // One unit of the grid, in burst-radius units.
        val step = 1f / halfCols

        val out = mutableListOf<Vector3>()
        cells.forEachIndexed { index, rows ->
            val originCol = index * (GLYPH_W + GAP)
            rows.forEachIndexed { row, line ->
                line.forEachIndexed { col, ch ->
                    if (ch != '.') {
                        val x = (originCol + col + 0.5f - halfCols) * step
                        // Grid rows run top-down; the scene's +Y is up.
                        val y = (halfRows - (row + 0.5f)) * step
                        out += Vector3(x, y, 0f)
                    }
                }
            }
        }
        return out
    }
}
