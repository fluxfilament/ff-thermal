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
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * The colour scale under the picture: the palette as a strip, with the temperatures
 * its colours stand for marked along it.
 *
 * This is what turns colours into a reading. Without it a warm-looking patch could be
 * 30 C or 300 C, and the two would look identical whenever auto-gain had rescaled
 * itself between glances.
 *
 * Where the ticks fall is [ScaleTicks]' business, shared with the scale drawn into a
 * saved card.
 */
class ScaleBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private var palette: Palette = Palette.IRON
    private var rawLo = 0f
    private var rawHi = 1f
    private var planck: Planck? = null

    private val barRect = RectF()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = BORDER
    }
    private val tickPaint = Paint().apply { color = MUTED }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED
        textSize = 10.5f * density
        typeface = Typeface.MONOSPACE
    }

    /** Rebuilt only when it would actually differ: a new shader per frame is waste. */
    private var gradientFor: Pair<Palette, Int>? = null

    fun show(palette: Palette, rawLo: Float, rawHi: Float, planck: Planck) {
        this.palette = palette
        this.rawLo = rawLo
        this.rawHi = rawHi
        this.planck = planck
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        val barHeight = 14f * density
        barRect.set(0f, 0f, width.toFloat(), barHeight)

        if (gradientFor != palette to width) {
            val stops = IntArray(STOPS) { palette.lut[it * 255 / (STOPS - 1)] }
            barPaint.shader = LinearGradient(
                0f, 0f, width.toFloat(), 0f, stops, null, Shader.TileMode.CLAMP,
            )
            gradientFor = palette to width
        }
        val radius = 2f * density
        canvas.drawRoundRect(barRect, radius, radius, barPaint)
        canvas.drawRoundRect(barRect, radius, radius, barBorder)

        val calibration = planck ?: return
        ScaleTicks.forEach(calibration, rawLo, rawHi) { fraction, degrees ->
            val x = fraction * width
            canvas.drawRect(x, barHeight + 2f * density, x + 1f, barHeight + 6f * density, tickPaint)
            val text = degrees.toString()
            val textWidth = labelPaint.measureText(text)
            val left = (x - textWidth / 2f).coerceIn(0f, width - textWidth)
            canvas.drawText(text, left, barHeight + 17f * density, labelPaint)
        }
    }

    private companion object {
        const val STOPS = 33
        const val MUTED = 0xFFA69A85.toInt()
        const val BORDER = 0xFF453A2C.toInt()
    }
}
