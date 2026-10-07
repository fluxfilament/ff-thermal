/*
 * Copyright (C) 2026 Damirusnik
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
package com.fluxfilament.thermalcam

import android.graphics.Matrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Where new spot meters appear and how they are numbered. The numbering is promised
 * on screen - the middle first, then clockwise from the top-left - while positions are
 * kept in the sensor's frame, so the check is made on screen: each spot is put through
 * the transform the live view draws with, at a real phone's proportions.
 */
@RunWith(RobolectricTestRunner::class)
class SpotMeterTest {

    /** Screen cells for spots one to nine, as (column, row). */
    private val promised = listOf(1 to 1, 0 to 0, 1 to 0, 2 to 0, 2 to 1, 2 to 2, 1 to 2, 0 to 2, 0 to 1)

    private fun screenCells(rotation: Int, mirrored: Boolean): List<Pair<Int, Int>> {
        val meter = SpotMeter().also { it.rotation = rotation; it.mirrored = mirrored }
        meter.setCount(9)
        val viewW = 1080f
        val viewH = 1500f
        val m = ViewTransform.matrixFor(80f, 60f, viewW, viewH, rotation, mirrored, Matrix())
        val bounds = floatArrayOf(0f, 0f, 80f, 60f).also { m.mapPoints(it) }
        val left = minOf(bounds[0], bounds[2])
        val top = minOf(bounds[1], bounds[3])
        val width = maxOf(bounds[0], bounds[2]) - left
        val height = maxOf(bounds[1], bounds[3]) - top
        return meter.positions().map { spot ->
            val p = floatArrayOf(spot.u * 80f, spot.v * 60f).also { m.mapPoints(it) }
            ((p[0] - left) / width * 3).toInt() to ((p[1] - top) / height * 3).toInt()
        }
    }

    @Test
    fun numberingIsClockwiseOnScreenWhateverTheOrientation() {
        for (rotation in ViewTransform.ROTATIONS) {
            for (mirrored in listOf(false, true)) {
                assertEquals("rotation $rotation, mirrored $mirrored", promised, screenCells(rotation, mirrored))
            }
        }
    }

    @Test
    fun theDefaultOrientationWorkedOutByHand() {
        // A quarter turn clockwise and a mirror is a transpose: across the screen is
        // down the sensor. The one case written out independently of the matrix.
        val meter = SpotMeter()
        meter.setCount(4)
        assertEquals(
            listOf(Spot(0.5f, 0.5f), Spot(0.15f, 0.15f), Spot(0.15f, 0.5f), Spot(0.15f, 0.85f)),
            meter.positions(),
        )
    }

    @Test
    fun spotsStayOnTheSceneWhenThePictureIsTurned() {
        val meter = SpotMeter()
        meter.setCount(3)
        val before = meter.positions()
        meter.rotation = 270
        meter.mirrored = false
        assertEquals(before, meter.positions())
    }

    @Test
    fun nineAtMostAndNoneAtLeast() {
        val meter = SpotMeter()
        repeat(9) { assertTrue(meter.add()) }
        assertFalse(meter.add())
        assertEquals(9, meter.count)
        meter.setCount(42)
        assertEquals(9, meter.count)
        meter.setCount(-1)
        assertEquals(0, meter.count)
        assertFalse(meter.remove())
    }

    @Test
    fun removingTakesTheHighestNumber() {
        val meter = SpotMeter()
        meter.setCount(3)
        val first = meter.positions().take(2)
        assertTrue(meter.remove())
        assertEquals(first, meter.positions())
    }

    @Test
    fun aDraggedSpotStaysOnThePicture() {
        val meter = SpotMeter()
        meter.setCount(2)
        meter.move(1, -0.3f, 1.7f)
        assertEquals(Spot(0f, 1f), meter.positions()[1])
        meter.move(5, 0.2f, 0.2f) // no such spot
        assertEquals(2, meter.count)
        assertEquals(Spot(0.5f, 0.5f), meter.positions()[0])
    }
}
