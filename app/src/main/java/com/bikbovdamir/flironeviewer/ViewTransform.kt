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
 * Two separate things are going on:
 *
 *  - **Mounting.** The camera plugs into the USB-C port, so its sensor sits at a
 *    fixed angle to the phone body - [MOUNT_ROTATION] undoes that. It is a property
 *    of the hardware, not a preference.
 *  - **Gravity.** The camera is bolted to the phone, so turning the phone turns the
 *    scene with it. Since the window is locked to portrait, the picture has to be
 *    turned back by however far the phone has been turned, which is why the sign is
 *    negative: rotate the phone clockwise and the scene needs winding back the other
 *    way to stay upright for the person holding it.
 *
 * Mirroring is applied after the rotation, in what the viewer sees, so the toggle
 * always flips left and right on screen no matter which way the phone is held. That
 * is the point of it: pointed at its owner the camera behaves like a front camera,
 * and a front camera that is not mirrored feels wrong to everyone.
 */
object ViewTransform {

    /**
     * Rotation, clockwise, that makes the sensor image upright with the phone held
     * upright. Fixed by where the connector is.
     */
    const val MOUNT_ROTATION = 90

    /** Combined rotation for a phone turned [deviceRotation] degrees clockwise. */
    fun rotationFor(deviceRotation: Int): Int =
        ((MOUNT_ROTATION - deviceRotation) % 360 + 360) % 360

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
