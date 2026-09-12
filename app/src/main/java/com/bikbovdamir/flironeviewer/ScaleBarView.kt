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
import kotlin.math.ceil

/**
 * The colour scale under the picture: the palette as a strip, with the temperatures
 * its colours stand for marked along it.
 *
 * This is what turns colours into a reading. Without it a warm-looking patch could be
 * 30 C or 300 C, and the two would look identical whenever auto-gain had rescaled
 * itself between glances.
 *
 * The ticks are placed by count, not by degree. The palette is stretched linearly over
 * raw sensor counts, and the count-to-degree conversion is the Planck equation, which
 * is not linear - so a scale drawn with evenly spaced degree labels would put every
 * label slightly in the wrong place. Spacing comes out a little uneven instead, which
 * is the honest picture of what the colours are doing.
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
        val loC = calibration.rawToCelsius(rawLo.toInt())
        val hiC = calibration.rawToCelsius(rawHi.toInt())
        if (loC.isNaN() || hiC.isNaN() || hiC <= loC) return

        // At most five labels, whatever the span: more than that and they collide on a
        // phone-width strip.
        val step = STEPS.firstOrNull { (hiC - loC) / it <= 5 } ?: STEPS.last()
        var value = ceil(loC / step).toInt() * step
        val span = (rawHi - rawLo).takeIf { it > 0f } ?: return
        while (value < hiC) {
            val raw = calibration.celsiusToRaw(value.toDouble())
            val fraction = ((raw - rawLo) / span).toFloat()
            // Skipped near the ends, where a label would sit under the MIN/MAX figures
            // that already say the same thing.
            if (fraction > 0.09f && fraction < 0.91f) {
                val x = fraction * width
                canvas.drawRect(x, barHeight + 2f * density, x + 1f, barHeight + 6f * density, tickPaint)
                val text = value.toString()
                val textWidth = labelPaint.measureText(text)
                val left = (x - textWidth / 2f).coerceIn(0f, width - textWidth)
                canvas.drawText(text, left, barHeight + 17f * density, labelPaint)
            }
            value += step
        }
    }

    private companion object {
        const val STOPS = 33
        const val MUTED = 0xFFA69A85.toInt()
        const val BORDER = 0xFF453A2C.toInt()

        /** Label spacings worth using, in whole degrees. */
        val STEPS = listOf(1, 2, 5, 10, 20, 50)
    }
}
