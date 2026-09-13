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
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.format.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Everything a saved card needs to know, gathered while the shutter is pressed.
 *
 * A snapshot of the state rather than a handle on the live objects: the file is
 * written on a background thread, and the camera keeps running meanwhile.
 */
class CardScene(
    /** The composed picture in sensor orientation, exactly as the live view is handed it. */
    val picture: Bitmap,
    val rotation: Int,
    val mirrored: Boolean,
    val spots: List<SpotLabel>,
    val palette: Palette,
    /** The contrast window in counts, or null before auto-gain has settled. */
    val window: ContrastRange?,
    val planck: Planck,
    val geometry: SledInfo?,
    val blend: BlendMode,
    val rangeFixed: Boolean,
    val takenAt: Date,
)

/**
 * Draws the saved picture together with what it takes to read it: the colour scale,
 * the spot meters, and a line of the settings the numbers depend on.
 *
 * A bare thermal image is not evidence of anything. The same wall photographed twice
 * looks identical whether auto-gain spanned 24-34 C or a pinned window spanned
 * 0-100 C, and the emissivity that turned counts into those degrees is nowhere in the
 * file. Everything a reader would otherwise have to remember goes on the card.
 *
 * Drawn onto its own canvas rather than captured from the screen: a screenshot would
 * carry the phone's own size, its notch, whatever screen happened to be open, and it
 * would bake the interface chrome into a file that is meant to hold a measurement.
 *
 * [SnapshotSaver] still writes the untouched sensor picture beside it. This one is for
 * looking at and sending on; that one is the original.
 */
object SnapshotCard {

    /** The long side of the picture on the card, in pixels. */
    private const val PICTURE_LONG_SIDE = 960

    /**
     * How many drawing units fit across the picture's width on screen.
     *
     * The glyph sizes in [SpotGlyphs] are screen-density figures, tuned against a live
     * view roughly this many units wide. Dividing the card's picture by the same number
     * lands the crosshairs and readings at the size they look right at, instead of at
     * whatever the file's own pixel count would make them.
     */
    private const val UNITS_ACROSS = 345f

    private val RU: Locale = Locale.forLanguageTag("ru")

    fun render(context: Context, scene: CardScene): Bitmap {
        val src = scene.picture
        val turned = scene.rotation % 180 != 0
        val srcW = if (turned) src.height else src.width
        val srcH = if (turned) src.width else src.height
        val scale = PICTURE_LONG_SIDE.toFloat() / maxOf(srcW, srcH)
        val pictureW = (srcW * scale).roundToInt()
        val pictureH = (srcH * scale).roundToInt()

        val unit = pictureW / UNITS_ACROSS
        val pad = 20f * unit
        val gap = 14f * unit
        val barHeight = 14f * unit
        val tickArea = 20f * unit
        val lineHeight = 15f * unit

        val data = dataTokens(context, scene)
        val dataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MUTED
            textSize = 10.5f * unit
            typeface = Typeface.MONOSPACE
        }
        val lines = wrap(data, dataPaint, pictureW.toFloat())

        val width = (pad * 2 + pictureW).roundToInt()
        val height = (
            pad + pictureH + gap + barHeight + tickArea + gap +
                lineHeight * lines.size + pad
            ).roundToInt()

        val card = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(card)
        canvas.drawColor(BACKGROUND)

        canvas.save()
        canvas.translate(pad, pad)
        drawPicture(canvas, scene, pictureW, pictureH, unit)
        canvas.restore()

        canvas.save()
        canvas.translate(pad, pad + pictureH + gap)
        drawScale(canvas, scene, pictureW.toFloat(), barHeight, unit)
        canvas.restore()

        var baseline = pad + pictureH + gap + barHeight + tickArea + gap + dataPaint.textSize
        for (line in lines) {
            canvas.drawText(line, pad, baseline, dataPaint)
            baseline += lineHeight
        }
        return card
    }

    /** The picture, in the well it sits in on screen, with the spot meters over it. */
    private fun drawPicture(
        canvas: Canvas,
        scene: CardScene,
        pictureW: Int,
        pictureH: Int,
        unit: Float,
    ) {
        val src = scene.picture
        val matrix = ViewTransform.matrixFor(
            srcW = src.width.toFloat(),
            srcH = src.height.toFloat(),
            viewW = pictureW.toFloat(),
            viewH = pictureH.toFloat(),
            rotation = scene.rotation,
            mirrored = scene.mirrored,
            into = Matrix(),
        )
        // Same nearest-neighbour blow-up as the live view: at 80x60 every pixel is a
        // measurement, and smoothing them would invent detail the sensor never saw.
        canvas.drawBitmap(src, matrix, Paint().apply { isFilterBitmap = false })

        val glyphs = SpotGlyphs(unit)
        // Held off the edge by a hair. On screen a label may run past the picture onto
        // the letterbox beside it, so butting up against the last column costs nothing;
        // here the picture's edge is the card's frame, and a reading that ends exactly
        // on it reads as cut off whether or not it is.
        val margin = 5f * unit
        val bounds = RectF(margin, margin, pictureW - margin, pictureH - margin)
        val point = FloatArray(2)
        for (spot in scene.spots) {
            point[0] = spot.u * src.width
            point[1] = spot.v * src.height
            matrix.mapPoints(point)
            glyphs.drawMarker(canvas, point[0], point[1])
            // No turning here, unlike on screen: a file has an up of its own, and the
            // reader's head is no longer attached to the phone that took it.
            glyphs.drawLabels(canvas, point[0], point[1], spot.number, spot.text, bounds)
        }

        canvas.drawRect(
            RectF(0f, 0f, pictureW.toFloat(), pictureH.toFloat()),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = unit
                color = BORDER
            },
        )
    }

    /** The palette as a strip, the window's ends written out, and the degrees between. */
    private fun drawScale(
        canvas: Canvas,
        scene: CardScene,
        width: Float,
        barHeight: Float,
        unit: Float,
    ) {
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MUTED
            textSize = 10.5f * unit
            typeface = Typeface.MONOSPACE
        }
        val endPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT
            textSize = 12.5f * unit
            typeface = Typeface.MONOSPACE
        }

        val window = scene.window
        val loText: String
        val hiText: String
        if (window == null) {
            loText = EMPTY
            hiText = EMPTY
        } else {
            loText = celsius(scene.planck.rawToCelsius(window.lo.toInt()))
            hiText = celsius(scene.planck.rawToCelsius(window.hi.toInt()))
        }
        val loWidth = endPaint.measureText(loText)
        val hiWidth = endPaint.measureText(hiText)
        val inset = 8f * unit
        val barLeft = loWidth + inset
        val barRight = width - hiWidth - inset

        val endBaseline = barHeight / 2f + endPaint.textSize / 2f - 2f * unit
        canvas.drawText(loText, 0f, endBaseline, endPaint)
        canvas.drawText(hiText, width - hiWidth, endBaseline, endPaint)

        if (barRight <= barLeft) return
        val bar = RectF(barLeft, 0f, barRight, barHeight)
        val stops = IntArray(STOPS) { scene.palette.lut[it * 255 / (STOPS - 1)] }
        val radius = 2f * unit
        canvas.drawRoundRect(
            bar, radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    bar.left, 0f, bar.right, 0f, stops, null, Shader.TileMode.CLAMP,
                )
            },
        )
        canvas.drawRoundRect(
            bar, radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = unit
                color = BORDER
            },
        )

        if (window == null) return
        val tickPaint = Paint().apply { color = MUTED }
        ScaleTicks.forEach(scene.planck, window.lo, window.hi) { fraction, degrees ->
            val x = bar.left + fraction * bar.width()
            canvas.drawRect(x, barHeight + 2f * unit, x + unit, barHeight + 6f * unit, tickPaint)
            val text = degrees.toString()
            val textWidth = labelPaint.measureText(text)
            val left = (x - textWidth / 2f).coerceIn(bar.left, bar.right - textWidth)
            canvas.drawText(text, left, barHeight + 17f * unit, labelPaint)
        }
    }

    /**
     * The settings the readings depend on, in one line: without them the degrees on
     * the card are a number without a method behind it.
     *
     * The Lepton's serial number is deliberately not among them. It identifies the
     * unit, and a card is made to be sent to people.
     */
    private fun dataTokens(context: Context, scene: CardScene): String {
        val tokens = mutableListOf<String>()
        tokens += DateFormat.format("dd.MM.yyyy HH:mm", scene.takenAt).toString()
        val info = scene.geometry
        tokens += when {
            info == null -> context.getString(R.string.geometry_unknown)
            info.versionLepton != null ->
                context.getString(R.string.geometry, info.width, info.height, info.versionLepton)
            else -> context.getString(R.string.geometry_short, info.width, info.height)
        }
        tokens += context.getString(
            R.string.emissivity_chip, String.format(RU, "%.2f", scene.planck.emissivity),
        )
        tokens += context.getString(
            when (scene.blend) {
                BlendMode.THERMAL -> R.string.card_blend_thermal
                BlendMode.MIX_35 -> R.string.card_blend_mix35
                BlendMode.MIX_60 -> R.string.card_blend_mix60
                BlendMode.VISIBLE -> R.string.card_blend_visible
            },
        )
        if (scene.rangeFixed) tokens += context.getString(R.string.card_range_fixed)
        if (scene.mirrored) tokens += context.getString(R.string.mirror_label)
        return tokens.joinToString(SEPARATOR)
    }

    /**
     * Breaks the data line at its separators so it fits [width], keeping whole tokens
     * together - a date split across two lines reads as two different dates.
     */
    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        if (paint.measureText(text) <= width) return listOf(text)
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (token in text.split(SEPARATOR)) {
            val candidate = if (current.isEmpty()) token else "$current$SEPARATOR$token"
            if (current.isNotEmpty() && paint.measureText(candidate) > width) {
                lines += current.toString()
                current = StringBuilder(token)
            } else {
                current = StringBuilder(candidate)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    private fun celsius(value: Double): String =
        if (value.isNaN()) EMPTY else String.format(RU, "%.1f°C", value)

    /** Shown for a window auto-gain has not settled on yet. */
    /** Stands in for a window auto-gain has not settled on yet. */
    private const val EMPTY = "—"
    private const val SEPARATOR = " · "
    private const val STOPS = 33

    // The F&F palette, matching res/values/colors.xml. Hard-coded rather than looked
    // up, because the card has one look whatever theme the app is showing.
    private const val BACKGROUND = 0xFF211B15.toInt()
    private const val TEXT = 0xFFEFE7D8.toInt()
    private const val MUTED = 0xFFA69A85.toInt()
    private const val BORDER = 0xFF453A2C.toInt()
}
