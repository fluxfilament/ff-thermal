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

/** The fit behind the calibration screen, on the readings it actually met. */
class CorrectionTest {

    private fun ice(measured: Double) = ReferencePoint(0.0, measured, 0.4, ReferenceKind.ICE)
    private fun boiling(measured: Double) = ReferencePoint(100.0, measured, 0.6, ReferenceKind.BOILING)

    @Test
    fun oneReferenceGivesAShift() {
        val fit = Correction.fit(listOf(ice(0.8)))
        assertTrue(fit is FitResult.Ok)
        val correction = (fit as FitResult.Ok).correction
        assertEquals(1.0, correction.gain, 0.0)
        assertEquals(-0.8, correction.offset, 1e-9)
    }

    @Test
    fun twoReferencesGiveShiftAndSlope() {
        // The second check of 2026-10-04, after the formula was fixed.
        val fit = Correction.fit(listOf(ice(0.8), boiling(101.3)))
        assertTrue(fit is FitResult.Ok)
        val correction = (fit as FitResult.Ok).correction
        assertEquals(0.0, correction.apply(0.8), 1e-9)
        assertEquals(100.0, correction.apply(101.3), 1e-9)
        assertEquals(0.995, correction.gain, 0.001)
    }

    @Test
    fun theCompressedScaleIsRefused() {
        // The first check of 2026-10-04: ice 5.7, boiling 83.7. The slope it asks
        // for is the missing IR window, not something a correction should paper over.
        val fit = Correction.fit(listOf(ice(5.7), boiling(83.7)))
        assertTrue(fit.toString(), fit is FitResult.TooSteep)
    }

    @Test
    fun referencesTooCloseGiveNoSlope() {
        val warm = ReferencePoint(20.0, 20.4, 0.3, ReferenceKind.CUSTOM)
        assertEquals(FitResult.TooClose, Correction.fit(listOf(ice(0.5), warm)))
    }

    @Test
    fun aLargeShiftIsRefused() {
        assertTrue(Correction.fit(listOf(ice(12.0))) is FitResult.TooFar)
    }

    @Test
    fun noPointsNoCorrection() {
        assertEquals(FitResult.NoPoints, Correction.fit(emptyList()))
    }

    @Test
    fun invertUndoesApply() {
        val correction = Correction(gain = 1.04, offset = -2.5)
        for (celsius in listOf(-20.0, 0.0, 36.6, 150.0)) {
            assertEquals(celsius, correction.invert(correction.apply(celsius)), 1e-9)
        }
    }
}
