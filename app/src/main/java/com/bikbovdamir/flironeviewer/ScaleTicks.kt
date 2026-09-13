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

import kotlin.math.ceil

/**
 * Where the degree marks fall along a palette strip.
 *
 * Shared by the scale under the live picture and the scale baked into a saved card,
 * because the two have to agree: a snapshot whose ticks sit a pixel off from the ones
 * the user was looking at when they pressed the shutter is a snapshot that quietly
 * contradicts the app.
 *
 * The marks are placed by count, not by degree. The palette is stretched linearly over
 * raw sensor counts, and the count-to-degree conversion is the Planck equation, which
 * is not linear - so evenly spaced degree labels would each sit slightly in the wrong
 * place. Spacing comes out a little uneven instead, which is the honest picture of
 * what the colours are doing.
 */
object ScaleTicks {

    /** Label spacings worth using, in whole degrees. */
    private val STEPS = listOf(1, 2, 5, 10, 20, 50)

    /**
     * At most five labels, whatever the span: more than that and they collide on a
     * phone-width strip.
     */
    private const val MAX_LABELS = 5

    /**
     * The fraction of the strip at either end left clear, where a label would sit
     * under the MIN/MAX figures that already say the same thing.
     */
    @PublishedApi
    internal const val MARGIN = 0.09f

    /**
     * Calls [action] once per mark with its position along the strip, 0 at [rawLo] and
     * 1 at [rawHi], and the whole degrees it stands for. Nothing is emitted if the
     * window is empty or the calibration cannot resolve it.
     *
     * Inline so the live view can call it from onDraw without allocating a lambda per
     * frame.
     */
    inline fun forEach(
        planck: Planck,
        rawLo: Float,
        rawHi: Float,
        action: (fraction: Float, degrees: Int) -> Unit,
    ) {
        val span = rawHi - rawLo
        if (span <= 0f) return
        val loC = planck.rawToCelsius(rawLo.toInt())
        val hiC = planck.rawToCelsius(rawHi.toInt())
        if (loC.isNaN() || hiC.isNaN() || hiC <= loC) return

        val step = stepFor(loC, hiC)
        var value = ceil(loC / step).toInt() * step
        while (value < hiC) {
            val raw = planck.celsiusToRaw(value.toDouble())
            val fraction = ((raw - rawLo) / span).toFloat()
            if (fraction > MARGIN && fraction < 1f - MARGIN) action(fraction, value)
            value += step
        }
    }

    /** Published for [forEach] to inline; not meant to be called directly. */
    fun stepFor(loC: Double, hiC: Double): Int =
        STEPS.firstOrNull { (hiC - loC) / it <= MAX_LABELS } ?: STEPS.last()
}
