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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Thermal on one side of a line, visible light on the other, with the line draggable.
 *
 * Blending the two at 50% would hide the very thing being set here: when the field of
 * view is wrong, a blend looks soft and a split looks broken, and only one of those is
 * actionable. The user drags the line onto an edge of the subject and turns the crop
 * until the edge meets itself across it.
 *
 * Both layers go through the same display transform as the live view, so what lines up
 * here lines up there.
 */
class SplitPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private var thermal: Bitmap? = null
    private var visible: Bitmap? = null

    /** Maps the visible frame onto the thermal pixel grid; supplied by the Compositor. */
    private val visToThermal = Matrix()
    private var haveVisible = false

    private var rotation = ViewTransform.DEFAULT_ROTATION
    private var mirrored = true

    /** Where the dividing line sits, as a fraction of the view's width. */
    private var split = 0.5f

    private val display = Matrix()
    private val combined = Matrix()
    private val imagePaint = Paint().apply { isFilterBitmap = false }
    private val smoothPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dividerPaint = Paint().apply { color = ACCENT2 }
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT2 }
    private val gripLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ON_ACCENT
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val tagBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TAG_BG }
    private val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT
        textSize = 10.5f * density
        typeface = Typeface.MONOSPACE
    }
    private val gripRect = RectF()
    private val tagRect = RectF()

    fun show(
        thermal: Bitmap,
        visible: Bitmap?,
        visToThermal: Matrix?,
        rotation: Int,
        mirrored: Boolean,
    ) {
        this.thermal = thermal
        this.visible = visible
        this.rotation = rotation
        this.mirrored = mirrored
        haveVisible = visible != null && visToThermal != null
        if (visToThermal != null) this.visToThermal.set(visToThermal)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val thermalBitmap = thermal ?: return
        if (width == 0 || height == 0) return
        ViewTransform.matrixFor(
            srcW = thermalBitmap.width.toFloat(),
            srcH = thermalBitmap.height.toFloat(),
            viewW = width.toFloat(),
            viewH = height.toFloat(),
            rotation = rotation,
            mirrored = mirrored,
            into = display,
        )
        canvas.drawBitmap(thermalBitmap, display, imagePaint)

        val dividerX = split * width
        val visibleBitmap = visible
        if (haveVisible && visibleBitmap != null) {
            canvas.save()
            canvas.clipRect(dividerX, 0f, width.toFloat(), height.toFloat())
            combined.set(visToThermal)
            combined.postConcat(display)
            canvas.drawBitmap(visibleBitmap, combined, smoothPaint)
            canvas.restore()
        }

        canvas.drawRect(dividerX - density, 0f, dividerX + density, height.toFloat(), dividerPaint)
        gripRect.set(
            dividerX - 10f * density,
            height / 2f - 22f * density,
            dividerX + 10f * density,
            height / 2f + 22f * density,
        )
        canvas.drawRoundRect(gripRect, 2f * density, 2f * density, gripPaint)
        canvas.drawLine(dividerX - 2f * density, height / 2f - 6f * density, dividerX - 2f * density, height / 2f + 6f * density, gripLine)
        canvas.drawLine(dividerX + 2f * density, height / 2f - 6f * density, dividerX + 2f * density, height / 2f + 6f * density, gripLine)

        drawTag(canvas, context.getString(R.string.fov_tag_thermal), 8f * density, true)
        drawTag(canvas, context.getString(R.string.fov_tag_visible), width - 8f * density, false)
    }

    private fun drawTag(canvas: Canvas, text: String, edge: Float, leftAligned: Boolean) {
        val textWidth = tagText.measureText(text)
        val padding = 7f * density
        val left = if (leftAligned) edge else edge - textWidth - 2 * padding
        tagRect.set(left, 8f * density, left + textWidth + 2 * padding, 8f * density + 20f * density)
        canvas.drawRoundRect(tagRect, density, density, tagBackground)
        canvas.drawText(text, left + padding, tagRect.bottom - 6f * density, tagText)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                split = (event.x / width).coerceIn(0f, 1f)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private companion object {
        const val ACCENT2 = 0xFF4FC3E0.toInt()
        const val ON_ACCENT = 0xFF0B1A1F.toInt()
        const val TEXT = 0xFFEFE7D8.toInt()
        const val TAG_BG = 0xB8140B07.toInt()
    }
}
