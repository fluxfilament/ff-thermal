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
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect

/**
 * How much of the visible-light image to mix over the thermal one.
 *
 * This is alpha blending, not FLIR's MSX. MSX is a specific patented technique -
 * embossing edges from the visible image into the thermal one without disturbing
 * the thermal values. Blending two images is a generic decades-old operation and
 * is nobody's property; it also visibly dilutes the thermal data, which is the
 * honest trade being made here and the reason the mix is adjustable rather than
 * fixed on.
 */
enum class BlendMode(val label: String, val visibleAlpha: Int) {
    THERMAL("Thermal", 0),
    MIX_35("Mix 35%", 89),
    MIX_60("Mix 60%", 153),
    VISIBLE("Visible", 255);

    fun next(): BlendMode = entries[(ordinal + 1) % entries.size]
}

/**
 * Combines the thermal image with the camera's visible-light frame.
 *
 * The two sensors sit behind separate lenses, so the pictures do not line up by
 * themselves. Two corrections are needed and they are different in kind:
 *
 *  - **Field of view** is a fixed property of the optics. The visible lens sees a
 *    wider scene, so a centre crop of it covers the same angle as the whole thermal
 *    frame. [fovRatio] is that crop, as a fraction of the visible frame.
 * - **Parallax** is not fixed: the lenses are offset from each other, so how far
 *    the two images slide apart depends on how far away the subject is. Confirmed on
 *    the device across four shots of the same mug at different distances - one
 *    setting cannot serve them all. That is why [parallax] stays a user control, and
 *    why every commercial thermal app has a "distance" or "alignment" slider doing
 *    exactly this.
 */
class Compositor {

    /**
     * Width of the visible-frame crop that matches the thermal field of view, as a
     * fraction of the visible frame's width. Below 1 because the visible lens is the
     * wider of the two.
     */
    var fovRatio: Float = DEFAULT_FOV_RATIO

    /**
     * Parallax correction, as a fraction of the visible frame's width, applied along
     * the source frame's own x axis - the direction the two lenses are separated in.
     *
     * Kept in the sensor's coordinates on purpose. An earlier version expressed it in
     * what the viewer sees and turned it into source coordinates through the display
     * transform, which meant every change to the rotation or the mirror silently
     * redefined what the slider did, and getting that mapping right took three
     * attempts. The lens separation is a fact about the camera body; describing it in
     * the camera's own frame makes it immune to how the picture is later turned.
     *
     * Calibration on this unit put it at [DEFAULT_PARALLAX] with the subject about an
     * arm away. It is a control and not a constant because it moves with distance:
     * the same setting that lines up a mug on the desk is visibly off for a wall
     * across the room.
     */
    var parallax: Float = DEFAULT_PARALLAX

    private var output: Bitmap? = null
    private var visible: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val placement = Matrix()
    private val decodeOptions = BitmapFactory.Options().apply {
        // Half resolution is still far more detail than the 80x60 thermal frame it is
        // being mixed with, at a quarter of the decode cost per frame.
        inSampleSize = 2
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    /**
     * Draws [thermal] and the frame's visible image into one bitmap, or returns null
     * if the visible frame is unusable and the caller should fall back to thermal.
     */
    fun compose(frame: ThermalFrame, thermal: Bitmap, mode: BlendMode): Bitmap? {
        if (mode == BlendMode.THERMAL) return null
        val jpeg = frame.jpeg ?: return null
        val vis = decode(jpeg) ?: return null

        val out = outputFor(thermal.width * OUTPUT_SCALE, thermal.height * OUTPUT_SCALE)
        val canvas = Canvas(out)

        // Thermal first, smoothed on the way up: the visible layer is about to add the
        // detail, so nearest-neighbour blocks underneath would only fight it.
        paint.alpha = 255
        canvas.drawBitmap(thermal, null, Rect(0, 0, out.width, out.height), paint)

        if (mode.visibleAlpha > 0) {
            paint.alpha = mode.visibleAlpha
            canvas.drawBitmap(vis, placeVisible(vis, out.width, out.height), paint)
        }
        return out
    }

    /**
     * Maps the region of the visible frame that matches the thermal field of view
     * onto the whole output.
     *
     * Drawn through a matrix rather than by handing Canvas a source rectangle,
     * because the region is not required to lie inside the frame. It leaves the frame
     * whenever the field-of-view ratio goes above 1 - meaning the thermal lens sees
     * wider than the visible one - or whenever the alignment is pushed far enough.
     * Clamping the rectangle instead used to throw the moment the ratio passed 1,
     * which killed the camera loop outright. With a matrix, whatever falls outside
     * simply is not painted and the thermal layer shows through at that edge, which
     * is also the honest picture: there is no visible data there.
     */
    private fun placeVisible(vis: Bitmap, outW: Int, outH: Int): Matrix {
        val cropW = vis.width * fovRatio
        val cropH = vis.height * fovRatio
        val left = vis.width / 2f + parallax * vis.width - cropW / 2f
        val top = vis.height / 2f - cropH / 2f
        return placement.apply {
            reset()
            postTranslate(-left, -top)
            postScale(outW / cropW, outH / cropH)
        }
    }

    private fun decode(jpeg: ByteArray): Bitmap? {
        // inBitmap would recycle the previous frame's allocation, but only when the
        // dimensions match exactly; the camera's JPEG is a constant size, so hand the
        // decoder the old bitmap and let it reuse the memory.
        decodeOptions.inBitmap = visible
        decodeOptions.inMutable = true
        val decoded = try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, decodeOptions)
        } catch (e: IllegalArgumentException) {
            // Thrown when the cached bitmap cannot be reused after all. Drop it and
            // let the next frame allocate fresh rather than failing the overlay.
            decodeOptions.inBitmap = null
            visible = null
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, decodeOptions)
        }
        visible = decoded
        return decoded
    }

    private fun outputFor(width: Int, height: Int): Bitmap {
        val existing = output
        if (existing != null && existing.width == width && existing.height == height) {
            return existing
        }
        val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output = created
        return created
    }

    companion object {
        /**
         * Output is a multiple of the thermal frame so the visible layer has somewhere
         * to put its detail: compositing at 80x60 would throw away the very thing the
         * visible channel is being mixed in for.
         */
        const val OUTPUT_SCALE = 8

        /**
         * The field-of-view crop, settled by lining the two layers up on the device
         * against a real subject. Below 1 because the visible lens is the wider of the
         * two, which is what the FLIR One's published figures say as well. Unlike the
         * alignment below, this one really is a constant: it is fixed by the optics
         * and does not move with distance.
         */
        const val DEFAULT_FOV_RATIO = 0.77f

        /**
         * Alignment measured on this camera against a mug at roughly arm's length.
         * A starting point, not a fixed truth - see [parallax].
         */
        const val DEFAULT_PARALLAX = 0.034f
    }
}
