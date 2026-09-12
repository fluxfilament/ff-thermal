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
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Faint graph paper behind an empty screen, so it reads as unfinished, not broken. */
class GridBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val spacing = 40f * resources.displayMetrics.density
    private val paint = Paint().apply { color = 0x80453A2C.toInt() }
    private val thickness = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        var x = 0f
        while (x < width) {
            canvas.drawRect(x, 0f, x + thickness, height.toFloat(), paint)
            x += spacing
        }
        var y = 0f
        while (y < height) {
            canvas.drawRect(0f, y, width.toFloat(), y + thickness, paint)
            y += spacing
        }
    }
}
