/*
 * Copyright (C) 2026 bikbov-damir
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.bikbovdamir.flironeviewer

import android.graphics.Matrix

/**
 * A point the user has put on the picture to read the temperature under it.
 *
 * Position is held in the sensor's own frame, as a fraction of the thermal image in
 * each axis, not in screen coordinates. Same reasoning as the parallax setting: a
 * spot is stuck to a place in the scene, and describing it in the sensor's frame
 * means turning the picture or mirroring it cannot quietly change what the spot is
 * measuring.
 */
data class Spot(val u: Float, val v: Float)

/**
 * Up to nine spot meters, numbered in the order they appear.
 *
 * Positions default to a three-by-three grid, laid out as the user sees it: the
 * first in the middle, then round the edge clockwise from the top-left corner. That
 * ordering is stated on screen, but the coordinates are kept in the sensor's frame,
 * so the two have to be reconciled - see [defaultFor].
 */
class SpotMeter {

    /**
     * Replaced wholesale rather than mutated: the camera thread reads this while
     * drawing a frame and the UI thread rewrites it mid-drag, and swapping an
     * immutable list is cheaper and safer than locking around nine points.
     */
    @Volatile
    private var spots: List<Spot> = emptyList()

    var rotation: Int = ViewTransform.DEFAULT_ROTATION
    var mirrored: Boolean = true

    val count: Int get() = spots.size

    fun positions(): List<Spot> = spots

    /** Adds the next spot at its default place. False when already at the maximum. */
    fun add(): Boolean {
        if (spots.size >= MAX) return false
        spots = spots + defaultFor(spots.size)
        return true
    }

    /** Removes the highest-numbered spot. False when none are left. */
    fun remove(): Boolean {
        if (spots.isEmpty()) return false
        spots = spots.dropLast(1)
        return true
    }

    fun setCount(n: Int) {
        while (spots.size < n.coerceIn(0, MAX)) add()
        while (spots.size > n.coerceIn(0, MAX)) remove()
    }

    fun move(index: Int, u: Float, v: Float) {
        if (index !in spots.indices) return
        spots = spots.toMutableList().also {
            it[index] = Spot(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
        }
    }

    /**
     * Where spot number [index] starts, in sensor coordinates.
     *
     * The order is given in what the viewer sees, but the answer has to be in the
     * sensor's frame, so the two must be squared up. It walks the nine sensor cells,
     * puts each one through the display transform, and keeps whichever lands on the
     * wanted screen cell. Slower than solving it, and correct by construction.
     */
    private fun defaultFor(index: Int): Spot {
        val wanted = SCREEN_ORDER[index.coerceIn(0, MAX - 1)]
        for (cell in 0 until 9) {
            val col = cell % 3
            val row = cell / 3
            if (toScreenCell(col, row) == wanted) return Spot(cellCentre(col), cellCentre(row))
        }
        return Spot(0.5f, 0.5f) // unreachable: the mapping is a permutation
    }

    /**
     * Where sensor grid cell (col, row) ends up on screen, as a (col, row) pair.
     *
     * Asks the same transform the picture is drawn through, on a three-by-three
     * viewport standing in for the screen. Writing the quarter turns out by hand
     * looked simpler and was wrong - the numbering came out anticlockwise, because a
     * second description of the same rotation is a second chance to get its
     * direction backwards. This is the fourth coordinate mapping in this project that
     * a hand derivation got wrong and the matrix got right; there is no reason to
     * keep betting against it.
     */
    private fun toScreenCell(col: Int, row: Int): Pair<Int, Int> {
        ViewTransform.matrixFor(
            srcW = 3f,
            srcH = 3f,
            viewW = 3f,
            viewH = 3f,
            rotation = rotation,
            mirrored = mirrored,
            into = scratch,
        )
        cell[0] = col + 0.5f
        cell[1] = row + 0.5f
        scratch.mapPoints(cell)
        return cell[0].toInt().coerceIn(0, 2) to cell[1].toInt().coerceIn(0, 2)
    }

    private val scratch = Matrix()
    private val cell = FloatArray(2)

    private companion object {
        const val MAX = 9

        /**
         * Screen cells for spots one to nine: the middle first, then clockwise from
         * the top-left corner.
         */
        val SCREEN_ORDER = listOf(
            1 to 1, // centre
            0 to 0, // top left
            1 to 0, // top middle
            2 to 0, // top right
            2 to 1, // right middle
            2 to 2, // bottom right
            1 to 2, // bottom middle
            0 to 2, // bottom left
            0 to 1, // left middle
        )

        /** Insets the edge cells so their labels stay on the picture. */
        fun cellCentre(index: Int): Float = 0.15f + index * 0.35f
    }
}
