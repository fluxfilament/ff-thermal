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

import android.graphics.Bitmap

/**
 * Colour lookup tables, 256 entries of packed ARGB each. Iron is built by
 * interpolating control points rather than shipping a binary palette file, so the
 * app carries no data dependency on the upstream raw palette files.
 */
enum class Palette(val label: String) {
    GRAYSCALE("Grayscale"),
    IRON("Iron");

    val lut: IntArray by lazy {
        when (this) {
            GRAYSCALE -> IntArray(256) { v -> 0xFF shl 24 or (v shl 16) or (v shl 8) or v }
            IRON -> ramp(
                0.00f to intArrayOf(0, 0, 0),
                0.15f to intArrayOf(25, 0, 70),
                0.30f to intArrayOf(85, 0, 130),
                0.45f to intArrayOf(150, 20, 110),
                0.60f to intArrayOf(215, 60, 50),
                0.75f to intArrayOf(245, 130, 0),
                0.90f to intArrayOf(255, 215, 60),
                1.00f to intArrayOf(255, 255, 255),
            )
        }
    }

    private fun ramp(vararg stops: Pair<Float, IntArray>): IntArray = IntArray(256) { i ->
        val t = i / 255f
        var lo = stops.first()
        var hi = stops.last()
        for (k in 0 until stops.size - 1) {
            if (t >= stops[k].first && t <= stops[k + 1].first) {
                lo = stops[k]
                hi = stops[k + 1]
                break
            }
        }
        val span = hi.first - lo.first
        val f = if (span <= 0f) 0f else (t - lo.first) / span
        val r = (lo.second[0] + f * (hi.second[0] - lo.second[0])).toInt().coerceIn(0, 255)
        val g = (lo.second[1] + f * (hi.second[1] - lo.second[1])).toInt().coerceIn(0, 255)
        val b = (lo.second[2] + f * (hi.second[2] - lo.second[2])).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/** A contrast window in raw sensor counts: the two ends of the palette. */
data class ContrastRange(val lo: Float, val hi: Float)

/**
 * Turns raw sensor counts into a displayable bitmap. By default contrast is linear
 * auto-gain over the frame's own min/max, same as upstream; set [fixedRange] to pin
 * it instead.
 *
 * Reuses one [Bitmap] and one pixel array between frames: at ~9 fps, allocating
 * both per frame is pure GC pressure.
 */
class ThermalRenderer {

    var palette: Palette = Palette.IRON

    /**
     * When set, the palette is stretched over these counts on every frame instead of
     * over each frame's own extremes.
     *
     * Auto-gain makes the better-looking single picture and the incomparable series:
     * the same wall is the same shade whether or not something hot is in shot, so two
     * frames cannot be read against each other, and a subject slowly warming up looks
     * unchanged. Pinning the window is what turns the colours into a scale.
     *
     * Written from the UI thread, read on the camera thread.
     */
    @Volatile
    var fixedRange: ContrastRange? = null

    /**
     * The window auto-gain has currently settled on, or null before the first frame.
     * Exposed so a fixed window can be seeded from it rather than from nothing.
     */
    val autoRange: ContrastRange?
        get() = if (haveBounds) ContrastRange(smoothedMin, smoothedMax) else null

    private var bitmap: Bitmap? = null
    private var argb = IntArray(0)

    /** Rolling contrast bounds, so the image does not flicker on a single hot pixel. */
    private var smoothedMin = 0f
    private var smoothedMax = 0f
    private var haveBounds = false

    /** Weight of the incoming frame's bounds; the rest carries over from the last one. */
    private val boundsAdapt = 0.25f

    fun render(frame: ThermalFrame): Bitmap {
        val bmp = bitmapFor(frame.width, frame.height)

        if (!haveBounds) {
            smoothedMin = frame.min.toFloat()
            smoothedMax = frame.max.toFloat()
            haveBounds = true
        } else {
            smoothedMin += boundsAdapt * (frame.min - smoothedMin)
            smoothedMax += boundsAdapt * (frame.max - smoothedMax)
        }

        colourise(frame, argb)
        bmp.setPixels(argb, 0, frame.width, 0, 0, frame.width, frame.height)
        return bmp
    }

    /**
     * Renders [frame] into a bitmap of its own, leaving the live one alone.
     *
     * A snapshot cannot share the display bitmap: the camera thread keeps drawing
     * into it, so compressing it would race the next frame and could save a torn
     * image. Contrast bounds are read but not advanced, so the file matches what
     * was on screen when the shutter was pressed.
     */
    fun snapshot(frame: ThermalFrame): Bitmap {
        val pixels = IntArray(frame.width * frame.height)
        colourise(frame, pixels)
        return Bitmap.createBitmap(pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
    }

    private fun colourise(frame: ThermalFrame, into: IntArray) {
        // Auto-gain keeps tracking even while pinned, so that unpinning - and seeding
        // the next pinned window - picks up the scene as it is now, not as it was
        // whenever the window was last fixed.
        val fixed = fixedRange
        val lo = fixed?.lo ?: if (haveBounds) smoothedMin else frame.min.toFloat()
        val hi = fixed?.hi ?: if (haveBounds) smoothedMax else frame.max.toFloat()
        val span = (hi - lo).coerceAtLeast(1f)
        val lut = palette.lut
        val raw = frame.raw
        for (i in into.indices) {
            val v = (((raw[i] - lo) / span) * 255f).toInt().coerceIn(0, 255)
            into[i] = lut[v]
        }
    }

    /** Forgets the rolling contrast bounds, e.g. after the shutter recalibrates. */
    fun resetBounds() {
        haveBounds = false
    }

    private fun bitmapFor(width: Int, height: Int): Bitmap {
        val existing = bitmap
        if (existing != null && existing.width == width && existing.height == height) return existing
        val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap = created
        argb = IntArray(width * height)
        return created
    }
}
