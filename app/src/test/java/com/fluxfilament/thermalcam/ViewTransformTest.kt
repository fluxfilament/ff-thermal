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

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * How the 80x60 sensor image lands on screen and in a saved file. Runs on Robolectric
 * for a real Matrix and Bitmap: the project's history says hand derivations of these
 * turns go wrong, so the tests ask the same classes the app draws with.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewTransformTest {

    private fun map(m: Matrix, x: Float, y: Float): Pair<Float, Float> {
        val p = floatArrayOf(x, y)
        m.mapPoints(p)
        return p[0] to p[1]
    }

    private fun matrix(viewW: Float, viewH: Float, rotation: Int, mirrored: Boolean) =
        ViewTransform.matrixFor(80f, 60f, viewW, viewH, rotation, mirrored, Matrix())

    private fun assertPoint(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals("x of $actual", expected.first, actual.first, 0.01f)
        assertEquals("y of $actual", expected.second, actual.second, 0.01f)
    }

    @Test
    fun unturnedFillsAViewOfTheSameShape() {
        val m = matrix(800f, 600f, 0, false)
        assertPoint(0f to 0f, map(m, 0f, 0f))
        assertPoint(800f to 600f, map(m, 80f, 60f))
    }

    @Test
    fun aQuarterTurnIsClockwise() {
        // Sensor top-left goes to the top-right of a portrait view the picture fills.
        val m = matrix(600f, 800f, 90, false)
        assertPoint(600f to 0f, map(m, 0f, 0f))
        assertPoint(0f to 800f, map(m, 80f, 60f))
    }

    @Test
    fun mirroringFlipsWhatTheViewerSeesLeftToRight() {
        for (rotation in ViewTransform.ROTATIONS) {
            val plain = matrix(1080f, 1500f, rotation, false)
            val mirrored = matrix(1080f, 1500f, rotation, true)
            for ((x, y) in listOf(0f to 0f, 80f to 0f, 13f to 47f)) {
                val (px, py) = map(plain, x, y)
                val (mx, my) = map(mirrored, x, y)
                assertEquals("rotation $rotation", 1080f - px, mx, 0.01f)
                assertEquals("rotation $rotation", py, my, 0.01f)
            }
        }
    }

    @Test
    fun theDefaultFitsAPhoneScreenWithoutCropping() {
        // The live view is portrait; turned, the 60-wide side runs across it.
        val m = matrix(1080f, 2000f, ViewTransform.DEFAULT_ROTATION, mirrored = true)
        val corners = listOf(0f to 0f, 80f to 0f, 0f to 60f, 80f to 60f).map { (x, y) -> map(m, x, y) }
        assertEquals(0f, corners.minOf { it.first }, 0.01f)
        assertEquals(1080f, corners.maxOf { it.first }, 0.01f)
        // 18x scale: 1440 tall, centred.
        assertEquals(280f, corners.minOf { it.second }, 0.01f)
        assertEquals(1720f, corners.maxOf { it.second }, 0.01f)
    }

    @Test
    fun aSavedFileIsTurnedTheWayTheScreenWas() {
        val source = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLACK)
        source.setPixel(0, 0, Color.RED)

        val turned = ViewTransform.orient(source, 90, mirrored = false)
        assertEquals(60, turned.width)
        assertEquals(80, turned.height)
        assertEquals(Color.RED, turned.getPixel(59, 0))

        // The app's default: turned and mirrored lands sensor top-left on top-left.
        val asShown = ViewTransform.orient(source, ViewTransform.DEFAULT_ROTATION, mirrored = true)
        assertEquals(Color.RED, asShown.getPixel(0, 0))
        // And agrees with where the live view put it.
        val (x, y) = map(matrix(60f, 80f, ViewTransform.DEFAULT_ROTATION, true), 0.5f, 0.5f)
        assertEquals(Color.RED, asShown.getPixel(x.toInt(), y.toInt()))
    }
}
