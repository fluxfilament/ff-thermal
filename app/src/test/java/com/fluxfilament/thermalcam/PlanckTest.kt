/*
 * Copyright (C) 2026 Damirusnik
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
package com.fluxfilament.thermalcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The radiometric chain against the official FLIR ONE app, on this project's
 * development unit.
 *
 * Each case is a JPEG that app saved on 2026-10-04, of the same cups used for the
 * ice and boiling-water check. The count is the raw thermal image embedded in the
 * JPEG (byte-swapped, as the [Planck] docs warn), taken at the app's own spot - its
 * coordinates are in the JPEG's MeasInfo record, and they are the frame centre.
 * The expected figure is what the app showed for that spot. Emissivity, reflected
 * temperature, window and air are all the JPEG's own, which are also [Planck]'s
 * defaults.
 *
 * Before the IR window went into the equation, these same counts read 5.3 and
 * 86.9 C for ice and boiling water. The formula lived a month like that because
 * nothing checked it against a reference; this test is that check.
 */
class PlanckTest {

    private class Shot(val what: String, val emissivity: Double, val counts: Double, val flirShowed: Double)

    private val shots = listOf(
        Shot("melting ice at emissivity 0.60", 0.60, 12160.0, -18.0),
        Shot("melting ice", 0.95, 12188.0, -0.6),
        Shot("boiling water", 0.95, 28628.0, 99.2),
    )

    private val unit = Planck.DEVELOPMENT_UNIT

    @Test
    fun matchesTheOfficialAppOnItsOwnJpegs() {
        for (shot in shots) {
            val celsius = unit.copy(emissivity = shot.emissivity).countsToCelsius(shot.counts)
            // The app shows one decimal; the model lands within 0.2 C of all three.
            assertEquals(shot.what, shot.flirShowed, celsius, 0.25)
        }
    }

    @Test
    fun theseShotsWouldHaveCaughtTheMissingWindow() {
        // Planck plus emissivity only, as the app had it until 2026-10-04. If this
        // ever stops failing the comparison above by a wide margin, the fixture has
        // lost its teeth.
        val old = unit.copy(irWindowTransmission = 1.0, atmosphericTransmission = 1.0)
        val ice = old.copy(emissivity = 0.95).countsToCelsius(12188.0)
        val boiling = old.copy(emissivity = 0.95).countsToCelsius(28628.0)
        assertTrue("ice read $ice", ice > 4.0)
        assertTrue("boiling water read $boiling", boiling < 90.0)
    }

    @Test
    fun usbCountsAreAQuarterOfTheJpegScale() {
        for (raw in listOf(3040, 3047, 7157)) {
            assertEquals(unit.countsToCelsius(raw * 4.0), unit.rawToCelsius(raw), 1e-9)
        }
    }

    @Test
    fun celsiusToRawInvertsRawToCelsius() {
        val correction = Correction(gain = 0.995, offset = -0.8)
        for (emissivity in listOf(0.30, 0.60, 0.95, 1.00)) {
            for (planck in listOf(unit, unit.copy(correction = correction))) {
                val p = planck.copy(emissivity = emissivity)
                for (raw in 2500..8000 step 250) {
                    val celsius = p.rawToCelsius(raw)
                    if (celsius.isNaN()) continue
                    assertEquals("e=$emissivity raw=$raw", raw.toDouble(), p.celsiusToRaw(celsius), 1e-6)
                }
            }
        }
    }

    @Test
    fun theCorrectionAppliesToEveryReading() {
        val corrected = unit.copy(correction = Correction(gain = 0.995, offset = -0.8))
        for (raw in listOf(3047, 4000, 7157)) {
            assertEquals(0.995 * unit.rawToCelsius(raw) - 0.8, corrected.rawToCelsius(raw), 1e-9)
        }
    }

    @Test
    fun emissivityChangesTheReadingNotTheCounts() {
        // Same counts, but the user says the surface emits less: it must be hotter
        // to send the sensor that much. Bare metal reading cold is this, backwards.
        val matte = unit.copy(emissivity = 0.95).countsToCelsius(28628.0)
        val glossy = unit.copy(emissivity = 0.60).countsToCelsius(28628.0)
        assertTrue("matte $matte, glossy $glossy", glossy > matte)
    }
}
