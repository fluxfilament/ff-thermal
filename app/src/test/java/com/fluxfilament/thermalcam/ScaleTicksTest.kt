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
 * The degree marks under the picture. They are placed by sensor count, because that is
 * what the palette is stretched over, so the check is that each mark sits where the
 * palette really shows that temperature.
 */
class ScaleTicksTest {

    private val unit = Planck.DEVELOPMENT_UNIT

    private fun ticks(planck: Planck, loC: Double, hiC: Double): List<Pair<Float, Int>> {
        val lo = planck.celsiusToRaw(loC).toFloat()
        val hi = planck.celsiusToRaw(hiC).toFloat()
        val out = mutableListOf<Pair<Float, Int>>()
        ScaleTicks.forEach(planck, lo, hi) { fraction, degrees -> out += fraction to degrees }
        return out
    }

    /** What the palette shows at [fraction] of the way along the strip. */
    private fun celsiusAt(planck: Planck, loC: Double, hiC: Double, fraction: Float): Double {
        val lo = planck.celsiusToRaw(loC)
        val hi = planck.celsiusToRaw(hiC)
        return planck.countsToCelsius((lo + fraction * (hi - lo)) * 4)
    }

    @Test
    fun aRoomScaleGetsRoundLabelsInside() {
        assertEquals(listOf(25, 30, 35), ticks(unit, 20.0, 40.0).map { it.second })
    }

    @Test
    fun eachMarkSitsWhereThePaletteShowsItsTemperature() {
        for ((lo, hi) in listOf(20.0 to 40.0, -20.0 to 150.0, 0.0 to 100.0, 31.0 to 36.5)) {
            for (planck in listOf(unit, unit.copy(emissivity = 0.30))) {
                val marks = ticks(planck, lo, hi)
                assertTrue("no marks for $lo..$hi", marks.isNotEmpty())
                for ((fraction, degrees) in marks) {
                    assertEquals("$degrees on $lo..$hi", degrees.toDouble(), celsiusAt(planck, lo, hi, fraction), 0.05)
                }
            }
        }
    }

    @Test
    fun marksStayInOrderAndClearOfTheEnds() {
        for ((lo, hi) in listOf(20.0 to 40.0, -20.0 to 150.0, 33.0 to 35.0)) {
            val marks = ticks(unit, lo, hi)
            assertTrue("${marks.size} marks on $lo..$hi", marks.size in 1..5)
            for ((fraction, _) in marks) assertTrue(fraction > 0.09f && fraction < 0.91f)
            assertEquals(marks.sortedBy { it.first }, marks)
            assertEquals(marks.sortedBy { it.second }, marks)
        }
    }

    @Test
    fun spacingWidensTowardsTheHotEnd() {
        // Radiance climbs faster than temperature, so each degree is worth more counts
        // the hotter it is, and evenly spaced degrees spread out along the strip.
        val marks = ticks(unit, 0.0, 100.0)
        assertEquals(listOf(20, 40, 60, 80), marks.map { it.second })
        val gaps = marks.zipWithNext { a, b -> b.first - a.first }
        for ((narrow, wide) in gaps.zipWithNext()) assertTrue("gaps $gaps", wide > narrow * 1.1f)
    }

    @Test
    fun theFullRangeLeavesTheColdEndUnlabelled() {
        // 0 C lands 6 % in on a -20..150 strip, inside the margin under the MIN figure.
        assertEquals(listOf(50, 100), ticks(unit, -20.0, 150.0).map { it.second })
    }

    @Test
    fun belowFreezingRoundsTheRightWay() {
        assertEquals(listOf(-15, -10, -5), ticks(unit, -20.0, 0.0).map { it.second })
    }

    @Test
    fun stepGrowsWithTheSpan() {
        assertEquals(1, ScaleTicks.stepFor(30.0, 34.0))
        assertEquals(1, ScaleTicks.stepFor(30.0, 35.0))
        assertEquals(2, ScaleTicks.stepFor(30.0, 36.0))
        assertEquals(5, ScaleTicks.stepFor(20.0, 40.0))
        assertEquals(10, ScaleTicks.stepFor(0.0, 50.0))
        assertEquals(50, ScaleTicks.stepFor(-20.0, 150.0))
        // Past the largest step the labels are allowed to thin out to it, not vanish.
        assertEquals(50, ScaleTicks.stepFor(-20.0, 1000.0))
    }

    @Test
    fun anEmptyWindowDrawsNothing() {
        val raw = unit.celsiusToRaw(30.0).toFloat()
        var calls = 0
        ScaleTicks.forEach(unit, raw, raw) { _, _ -> calls++ }
        ScaleTicks.forEach(unit, raw, raw - 100f) { _, _ -> calls++ }
        assertEquals(0, calls)
    }
}
