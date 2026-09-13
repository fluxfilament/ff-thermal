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

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * Draws one spot meter: the crosshair, the temperature beside it, and the number that
 * tells it from the other eight.
 *
 * Pulled out of [ThermalView] so a saved card can draw its spots with the same code
 * rather than a lookalike. The two draw at wildly different scales - screen density on
 * one side, a file's own pixels on the other - so the scale comes in as [unit], the
 * length everything here is measured in, instead of being read off the display.
 */
class SpotGlyphs(private val unit: Float) {

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * unit
        color = Color.WHITE
    }
    private val markerShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * unit
        color = Color.argb(160, 0, 0, 0)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * unit
        isFakeBoldText = true
    }
    private val textShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * unit
        color = Color.argb(200, 0, 0, 0)
        textSize = 13f * unit
        isFakeBoldText = true
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * unit
        isFakeBoldText = true
    }
    private val numberShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * unit
        color = Color.argb(200, 0, 0, 0)
        textSize = 11f * unit
        isFakeBoldText = true
    }

    /** Half the crosshair's ring, in the same units; the caller needs it to lay out around. */
    val radius: Float get() = 7f * unit

    /**
     * The crosshair at ([x], [y]).
     *
     * Kept apart from [drawLabels] because only the labels turn with the reader: a ring
     * with four ticks at right angles looks the same whichever way up it is, so turning
     * it would cost a save/restore to change nothing.
     */
    fun drawMarker(canvas: Canvas, x: Float, y: Float) {
        val r = radius
        for (paint in arrayOf(markerShadow, markerPaint)) {
            canvas.drawCircle(x, y, r, paint)
            canvas.drawLine(x - r * 1.7f, y, x - r * 0.5f, y, paint)
            canvas.drawLine(x + r * 0.5f, y, x + r * 1.7f, y, paint)
            canvas.drawLine(x, y - r * 1.7f, x, y - r * 0.5f, paint)
            canvas.drawLine(x, y + r * 0.5f, x, y + r * 1.7f, paint)
        }
    }

    /**
     * The reading and the number for the crosshair at ([x], [y]), kept inside [bounds].
     *
     * The reading gets the prime spot beside the crosshair, where the eye lands. The
     * number is only there to tell one spot from another, so it goes up and to the
     * left, out of the way of the figure that is actually being read. "Beside" and
     * "up" mean from the reader's side, so [bounds] is the area to stay within as seen
     * from there - which is not the same rectangle as the view's own once the phone is
     * turned.
     */
    fun drawLabels(canvas: Canvas, x: Float, y: Float, number: Int, reading: String, bounds: RectF) {
        val r = radius
        val readingWidth = textPaint.measureText(reading)
        val left = if (x + r * 2f + readingWidth < bounds.right) x + r * 2f
        else x - r * 2f - readingWidth
        val baseline = (y + 5f * unit).coerceIn(
            bounds.top + textPaint.textSize,
            bounds.bottom - 4f * unit,
        )
        draw(canvas, reading, left, baseline, textPaint, textShadow)

        val text = number.toString()
        val numberWidth = numberPaint.measureText(text)
        val numberX = (x - r * 1.5f - numberWidth)
            .coerceIn(bounds.left, bounds.right - numberWidth)
        val numberY = (y - r * 1.5f).coerceAtLeast(bounds.top + numberPaint.textSize)
        draw(canvas, text, numberX, numberY, numberPaint, numberShadow)
    }

    /** Outline first, then the glyphs: legible over a light scene as well as a dark one. */
    private fun draw(canvas: Canvas, text: String, x: Float, y: Float, fill: Paint, outline: Paint) {
        canvas.drawText(text, x, y, outline)
        canvas.drawText(text, x, y, fill)
    }
}
