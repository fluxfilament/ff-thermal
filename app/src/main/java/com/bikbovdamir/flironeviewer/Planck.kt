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
 *
 * The radiometric conversion is the Planck equation as applied by FLIR's
 * own metadata; the arrangement below follows fnoop/flirone-v4l2's
 * plank.h (C) 2015-2016 Thomas <tomas123@EEVblog>, GPL-2.0-or-later.
 * See NOTICE.
 */
package com.bikbovdamir.flironeviewer

import kotlin.math.exp
import kotlin.math.ln

/**
 * Converts raw sensor counts to degrees Celsius.
 *
 * The five Planck coefficients are **per camera unit**, written into every JPEG
 * the official FLIR app saves. Both upstream projects hardcode coefficients read
 * off one author's own camera, with a comment wondering whether they are hardware
 * dependent. They are: measured across three units, R1 came out 16515.2 / 18417.0 /
 * 18666.1 and O came out -4387 / -1656 / -1927. Borrowing someone else's numbers -
 * O especially - shifts every reading.
 *
 * To read them off a camera, save one shot with the official FLIR ONE app and pull
 * the FFF metadata out of the JPEG (`exiftool -Planck*`, or `tools/fff_parse.py`
 * in this repo, which needs no root). Two things to watch: the FFF record index is
 * big-endian while the numbers inside a record are little-endian, and the embedded
 * raw thermal PNG is byte-swapped relative to how an ordinary PNG reader will
 * decode it.
 */
data class Planck(
    val r1: Double,
    val b: Double,
    val f: Double,
    val o: Double,
    val r2: Double,
    /**
     * Emissivity of the target: how much of what the sensor sees the target actually
     * radiated, as opposed to reflected from its surroundings. This is the setting
     * that matters most in practice - the coefficients above are fixed by the
     * hardware, but a wrong emissivity is a wrong reading, and bare metal is off by
     * tens of degrees at the default.
     *
     * The official app's own presets are a decent guide: matte 0.95 (paint, brick,
     * wood, plastic, skin), semi-matte 0.80, semi-glossy 0.60, glossy 0.30. Polished
     * metal runs lower still, which is why it reads far colder than it is.
     */
    val emissivity: Double = 0.95,
    /** Apparent temperature of whatever the target is reflecting, in Kelvin. */
    val reflectedTemperature: Double = 295.15,
) {

    /**
     * Counts arriving over USB are a quarter of the scale the coefficients are
     * calibrated against - upstream applies the same factor and calls it a "mystery
     * correction factor". It is not a mystery once both scales are in view: running
     * the equation over the raw thermal image embedded in a JPEG from this same
     * camera gives sane room temperatures at x1 (29-43 C) and nonsense at x4
     * (246-266 C), while the live USB stream of a comparable scene reads 3729-4214
     * against that JPEG's 15476-17036. Four times, measured from both ends.
     */
    private val usbScale = 4

    /** Radiance the target reflects from its surroundings, in raw counts. */
    private val reflectedRaw: Double =
        r1 / (r2 * (exp(b / reflectedTemperature) - f)) - o

    fun rawToCelsius(raw: Int): Double {
        val scaled = raw.toDouble() * usbScale
        val objectRaw = (scaled - (1 - emissivity) * reflectedRaw) / emissivity
        val ratio = r1 / (r2 * (objectRaw + o)) + f
        if (ratio <= 0) return Double.NaN
        return b / ln(ratio) - 273.15
    }

    /**
     * The inverse of [rawToCelsius]: which count a surface at [celsius] reads as.
     *
     * Needed because a fixed contrast window is set in degrees but applied to counts,
     * and the mapping between the two is not fixed - it moves with [emissivity]. So
     * the window has to be re-derived every time that slider does, or a window set at
     * one emissivity would quietly mean different temperatures at another.
     */
    fun celsiusToRaw(celsius: Double): Double {
        val kelvin = celsius + 273.15
        if (kelvin <= 0) return Double.NaN
        val ratio = exp(b / kelvin)
        if (ratio <= f) return Double.NaN
        val objectRaw = r1 / (r2 * (ratio - f)) - o
        val scaled = objectRaw * emissivity + (1 - emissivity) * reflectedRaw
        return scaled / usbScale
    }

    companion object {
        /**
         * Bounds for the emissivity control. The upper end is the physical limit of a
         * black body; the lower end is where the correction starts amplifying sensor
         * noise faster than it removes error, since it divides by this number.
         */
        const val MIN_EMISSIVITY = 0.10
        const val MAX_EMISSIVITY = 1.00

        /**
         * The FLIR ONE (gen 3) unit this project was developed against, read out of
         * a JPEG its official app saved on 2026-09-10. Anyone building for a
         * different unit should replace these - see the class docs for how - rather
         * than trust them.
         */
        val DEVELOPMENT_UNIT = Planck(
            r1 = 16515.199219,
            b = 1435.0,
            f = 1.0,
            o = -4387.0,
            r2 = 0.0125,
        )
    }
}
