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
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
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

    /** Called with the spot's index and its new position in sensor coordinates. */
    var onSpotMoved: ((Int, Float, Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val matrix = Matrix()
    private val inverse = Matrix()
    private val point = FloatArray(2)

    private val imagePaint = Paint().apply {
        // 80x60 blown up to a phone screen: interpolation just smears the pixels, and
        // a wrong de-interleave has to stay visible as hard banding.
        isFilterBitmap = false
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
    }
    private val markerShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        color = Color.argb(160, 0, 0, 0)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        isFakeBoldText = true
    }
    private val textShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.argb(200, 0, 0, 0)
        textSize = 13f * density
        isFakeBoldText = true
    }

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        isFakeBoldText = true
    }
    private val numberShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.argb(200, 0, 0, 0)
        textSize = 11f * density
        isFakeBoldText = true
    }

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
        val r = 7f * density

        for (paint in arrayOf(markerShadow, markerPaint)) {
            canvas.drawCircle(x, y, r, paint)
            canvas.drawLine(x - r * 1.7f, y, x - r * 0.5f, y, paint)
            canvas.drawLine(x + r * 0.5f, y, x + r * 1.7f, y, paint)
            canvas.drawLine(x, y - r * 1.7f, x, y - r * 0.5f, paint)
            canvas.drawLine(x, y + r * 0.5f, x, y + r * 1.7f, paint)
        }

        // The reading gets the prime spot beside the crosshair, where the eye lands.
        // The number is only there to tell one spot from another, so it goes up and
        // to the left, out of the way of the figure that is actually being read.
        val reading = spot.text
        val readingWidth = textPaint.measureText(reading)
        val left = if (x + r * 2f + readingWidth < width) x + r * 2f else x - r * 2f - readingWidth
        val baseline = (y + 5f * density).coerceIn(textPaint.textSize, height - 4f * density)
        drawLabel(canvas, reading, left, baseline, textPaint, textShadow)

        val number = spot.number.toString()
        val numberX = (x - r * 1.5f - numberPaint.measureText(number))
            .coerceIn(0f, width - numberPaint.measureText(number))
        val numberY = (y - r * 1.5f).coerceAtLeast(numberPaint.textSize)
        drawLabel(canvas, number, numberX, numberY, numberPaint, numberShadow)
    }

    /** Outline first, then the glyphs: legible over a light scene as well as a dark one. */
    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float, fill: Paint, outline: Paint) {
        canvas.drawText(text, x, y, outline)
        canvas.drawText(text, x, y, fill)
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
