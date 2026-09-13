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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** One spot as the view needs it: where it sits, its number, and what it reads. */
class SpotLabel(val u: Float, val v: Float, val number: Int, val text: String)

/**
 * Draws the live picture and the spot meters on it, and lets the spots be dragged.
 *
 * A view of its own rather than an ImageView with something layered over it, because
 * the picture and the spots have to agree on exactly one transform. Sharing a matrix
 * between two views is a standing invitation for them to drift apart by a frame or a
 * layout pass, and a spot meter that does not sit where it is drawn is worse than no
 * spot meter.
 */
class ThermalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private var rotation = ViewTransform.DEFAULT_ROTATION
    private var mirrored = true

    var spots: List<SpotLabel> = emptyList()

    /**
     * How far the phone itself is turned, in degrees clockwise from upright.
     *
     * The spot labels are turned back by this much so they stay the right way up for
     * whoever is reading them. The picture is deliberately left alone: the camera is
     * bolted to the phone and turns with it, so the scene is already where it should
     * be, while the reader's head is not attached to either.
     */
    var labelRotation: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Called with the spot's index and its new position in sensor coordinates. */
    var onSpotMoved: ((Int, Float, Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val matrix = Matrix()
    private val inverse = Matrix()
    private val point = FloatArray(2)

    /** Scratch for placing labels in the turned frame; reused to keep onDraw allocation-free. */
    private val labelMatrix = Matrix()
    private val labelBounds = RectF()

    private val imagePaint = Paint().apply {
        // 80x60 blown up to a phone screen: interpolation just smears the pixels, and
        // a wrong de-interleave has to stay visible as hard banding.
        isFilterBitmap = false
    }
    private val glyphs = SpotGlyphs(density)

    private var dragging = -1

    fun setImage(bitmap: Bitmap, rotation: Int, mirrored: Boolean) {
        this.bitmap = bitmap
        this.rotation = rotation
        this.mirrored = mirrored
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        ViewTransform.matrixFor(
            srcW = bmp.width.toFloat(),
            srcH = bmp.height.toFloat(),
            viewW = width.toFloat(),
            viewH = height.toFloat(),
            rotation = rotation,
            mirrored = mirrored,
            into = matrix,
        )
        canvas.drawBitmap(bmp, matrix, imagePaint)
        for (spot in spots) drawSpot(canvas, bmp, spot)
    }

    private fun drawSpot(canvas: Canvas, bmp: Bitmap, spot: SpotLabel) {
        point[0] = spot.u * bmp.width
        point[1] = spot.v * bmp.height
        matrix.mapPoints(point)
        val x = point[0]
        val y = point[1]

        glyphs.drawMarker(canvas, x, y)

        canvas.save()
        canvas.rotate(-labelRotation.toFloat(), x, y)
        boundsInLabelFrame(x, y, labelBounds)
        glyphs.drawLabels(canvas, x, y, spot.number, spot.text, labelBounds)
        canvas.restore()
    }

    /**
     * The view's edges as they fall in the label frame turned about ([x], [y]).
     *
     * Keeping a label on screen means comparing it against the screen, and the two
     * frames only agree while the phone is upright. Turning the view's own rectangle
     * by the same angle puts both back in one frame; at right angles the mapped
     * rectangle is exact rather than a bounding box, so nothing is lost by it.
     */
    private fun boundsInLabelFrame(x: Float, y: Float, into: RectF) {
        into.set(0f, 0f, width.toFloat(), height.toFloat())
        if (labelRotation == 0) return
        labelMatrix.setRotate(labelRotation.toFloat(), x, y)
        labelMatrix.mapRect(into)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = bitmap ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = nearestSpot(bmp, event.x, event.y)
                if (dragging < 0) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging < 0) return false
                reportMove(bmp, event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging < 0) return false
                reportMove(bmp, event.x, event.y)
                dragging = -1
                return true
            }
        }
        return false
    }

    private fun reportMove(bmp: Bitmap, viewX: Float, viewY: Float) {
        if (!matrix.invert(inverse)) return
        point[0] = viewX
        point[1] = viewY
        inverse.mapPoints(point)
        onSpotMoved?.invoke(dragging, point[0] / bmp.width, point[1] / bmp.height)
        invalidate()
    }

    /** Index of the spot under the finger, or -1 if the touch missed them all. */
    private fun nearestSpot(bmp: Bitmap, viewX: Float, viewY: Float): Int {
        // Generous, because a fingertip is far bigger than the marker it is aiming at.
        val reach = 28f * density
        var best = -1
        var bestDistance = reach
        for ((index, spot) in spots.withIndex()) {
            point[0] = spot.u * bmp.width
            point[1] = spot.v * bmp.height
            matrix.mapPoints(point)
            val d = hypot(point[0] - viewX, point[1] - viewY)
            if (d < bestDistance) {
                bestDistance = d
                best = index
            }
        }
        return best
    }
}
