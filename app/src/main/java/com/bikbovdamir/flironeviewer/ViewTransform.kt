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

import android.graphics.Bitmap
import android.graphics.Matrix
import kotlin.math.min

/**
 * How the sensor image is turned before anyone looks at it.
 *
 * Both the rotation and the mirroring are settings rather than constants, and the
 * reason is the connector: USB-C plugs in either way up. Turn the dongle over and
 * the sensor turns with it - the picture arrives 180 degrees round, and the camera
 * now looks the other way, so what was a front camera wanting a mirror becomes a
 * rear one that must not have it. No amount of code can tell which way it was
 * pushed in, so the user says.
 *
 * There is deliberately no compensation for how the phone is being *held*. The
 * camera is bolted to the phone and the window is locked to portrait, so turning the
 * phone turns the camera and the screen together and their relationship never
 * changes - an accelerometer term here turns the picture away from correct, not
 * towards it. It was tried both ways round before the reasoning caught up: neither
 * sign worked, because the term should not exist at all.
 *
 * Mirroring is applied after the rotation, in what the viewer sees, so the toggle
 * flips left and right on screen whichever way the picture has been turned.
 */
object ViewTransform {

    /**
     * Where the rotation starts before the user touches it. Which way up the picture
     * lands depends on which way the dongle was pushed in, so no default is right for
     * everyone: this is the one that suits the connector the usual way round, and the
     * button covers the other way in a tap.
     */
    const val DEFAULT_ROTATION = 90

    val ROTATIONS = listOf(0, 90, 180, 270)

    /**
     * Matrix mapping a [srcW] x [srcH] image into a [viewW] x [viewH] view: turned,
     * optionally mirrored, scaled to fit without cropping, and centred.
     */
    fun matrixFor(
        srcW: Float,
        srcH: Float,
        viewW: Float,
        viewH: Float,
        rotation: Int,
        mirrored: Boolean,
        into: Matrix,
    ): Matrix {
        into.reset()
        into.postTranslate(-srcW / 2f, -srcH / 2f)
        into.postRotate(rotation.toFloat())
        if (mirrored) into.postScale(-1f, 1f)
        // Turning by a quarter swaps which side has to fit which.
        val turnedW = if (rotation % 180 == 0) srcW else srcH
        val turnedH = if (rotation % 180 == 0) srcH else srcW
        val scale = min(viewW / turnedW, viewH / turnedH)
        into.postScale(scale, scale)
        into.postTranslate(viewW / 2f, viewH / 2f)
        return into
    }

    /**
     * The same rotation and mirroring baked into a new bitmap, for saving a file that
     * matches what was on screen.
     */
    fun orient(source: Bitmap, rotation: Int, mirrored: Boolean): Bitmap {
        if (rotation == 0 && !mirrored) return source
        val m = Matrix()
        m.postRotate(rotation.toFloat())
        if (mirrored) m.postScale(-1f, 1f)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
    }
}
