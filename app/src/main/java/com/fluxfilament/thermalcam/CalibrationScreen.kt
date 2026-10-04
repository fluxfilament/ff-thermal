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

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The calibration screen: measure a reference, collect one or two, and lay the
 * resulting [Correction] over every reading.
 *
 * Kept out of MainActivity, which already carries every other screen. The activity
 * feeds it frames while it is showing and hears back only through [onCorrection];
 * everything here runs on the UI thread except [zoneCelsius], which the camera
 * thread calls to boil a frame down to one number before posting it.
 */
class CalibrationScreen(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    /** The current conversion, correction included. */
    private val planck: () -> Planck,
    private val onCorrection: (Correction) -> Unit,
    private val onConstants: (CameraConstants) -> Unit,
) {
    private val ru: Locale get() = activity.uiLocale()

    private val status: TextView = activity.findViewById(R.id.calibStatus)
    private val statusSub: TextView = activity.findViewById(R.id.calibStatusSub)
    val preview: ThermalView = activity.findViewById(R.id.calibPreview)
    private val live: TextView = activity.findViewById(R.id.calibLive)
    private val refOptions: LinearLayout = activity.findViewById(R.id.calibRefOptions)
    private val customRow: View = activity.findViewById(R.id.calibCustomRow)
    private val customValue: EditText = activity.findViewById(R.id.calibCustomValue)
    private val refNote: TextView = activity.findViewById(R.id.calibRefNote)
    private val measureButton: TextView = activity.findViewById(R.id.calibMeasure)
    private val pointsList: LinearLayout = activity.findViewById(R.id.calibPoints)
    private val message: TextView = activity.findViewById(R.id.calibMessage)
    private val constsStatus: TextView = activity.findViewById(R.id.constsStatus)
    private val constsValues: TextView = activity.findViewById(R.id.constsValues)
    private val constsMessage: TextView = activity.findViewById(R.id.constsMessage)

    private var reference = ReferenceKind.ICE
    private val points = mutableListOf<ReferencePoint>()

    /** Readings gathered by the measurement in progress; null when none is. */
    private var samples: MutableList<Double>? = null

    /** When the last frame arrived, so the button can tell a live camera from none. */
    private var lastFrameAt = 0L

    init {
        // Points from before the IR window went into Planck, under their old key.
        prefs.edit().remove("calibration_points").apply()
        preview.zone = ZONE
        loadPoints()
        measureButton.setOnClickListener { startMeasuring() }
        activity.findViewById<View>(R.id.calibApply).setOnClickListener { apply() }
        activity.findViewById<View>(R.id.calibReset).setOnClickListener { reset() }
        activity.findViewById<View>(R.id.constsImport).setOnClickListener { pickJpeg() }
        activity.findViewById<View>(R.id.constsBuiltIn).setOnClickListener {
            useConstants(CameraConstants.BUILT_IN, imported = false)
        }
        buildReferenceOptions()
        refreshAll()
    }

    /** The correction saved last time, for the activity to start with. */
    fun savedCorrection(): Correction = Correction(
        gain = prefs.getFloat(PREF_GAIN, 1f).toDouble(),
        offset = prefs.getFloat(PREF_OFFSET, 0f).toDouble(),
    ).let { if (it.gain == 1.0 && it.offset == 0.0) Correction.NONE else it }

    /** Constants imported last time, or null for the built-in ones. */
    fun savedConstants(): CameraConstants? =
        prefs.getString(PREF_CONSTANTS, null)?.let { CameraConstants.deserialize(it) }

    fun refreshAll() {
        refreshConstants()
        refreshStatus()
        refreshReference()
        refreshPoints()
        refreshMeasureButton()
    }

    // --- frames -------------------------------------------------------------------

    /**
     * The zone's mean in degrees at water's emissivity and without the user's
     * correction - a reference has to be compared against what the camera itself
     * says, or a second calibration would be measured through the first. Called on
     * the camera thread.
     */
    fun zoneCelsius(frame: ThermalFrame): Double {
        val x0 = (ZONE.left * frame.width).toInt()
        val x1 = (ZONE.right * frame.width).toInt()
        val y0 = (ZONE.top * frame.height).toInt()
        val y1 = (ZONE.bottom * frame.height).toInt()
        var sum = 0L
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            sum += frame.raw[y * frame.width + x]
            n++
        }
        if (n == 0) return Double.NaN
        val measuring = planck().copy(emissivity = WATER_EMISSIVITY, correction = Correction.NONE)
        return measuring.rawToCelsius((sum / n).toInt())
    }

    /** On the UI thread, with the picture and the zone figure for one frame. */
    fun onFrame(picture: Bitmap, rotation: Int, mirrored: Boolean, celsius: Double) {
        lastFrameAt = System.currentTimeMillis()
        preview.setImage(picture, rotation, mirrored)
        live.text = if (celsius.isNaN()) EMPTY else "${fmt(celsius)} °C"
        val gathering = samples
        if (gathering != null && !celsius.isNaN()) {
            gathering += celsius
            if (gathering.size >= SAMPLES) finishMeasuring(gathering)
        }
        refreshMeasureButton()
    }

    /**
     * The camera closed its shutter for a flat-field correction. Readings across it
     * do not belong to one measurement, so one in progress starts over.
     */
    fun onShutter() {
        if (samples == null) return
        samples = mutableListOf()
        message.setText(R.string.calib_msg_restarted)
    }

    // --- measuring ----------------------------------------------------------------

    private fun startMeasuring() {
        if (samples != null || !cameraLive()) return
        if (referenceValue() == null) {
            message.setText(R.string.calib_msg_bad_custom)
            return
        }
        message.text = ""
        samples = mutableListOf()
        refreshMeasureButton()
    }

    private fun finishMeasuring(readings: List<Double>) {
        samples = null
        val ref = referenceValue() ?: return
        val point = ReferencePoint(
            reference = ref,
            measured = readings.average(),
            spread = readings.max() - readings.min(),
            kind = reference,
        )
        // One point per fixed reference, and a custom one replaces a custom one near
        // it: re-measuring the ice should correct the ice, not stack a second copy.
        points.removeAll {
            it.kind == point.kind &&
                (point.kind != ReferenceKind.CUSTOM || kotlin.math.abs(it.reference - ref) < 5)
        }
        points += point
        while (points.size > 2) points.removeAt(0)
        savePoints()
        message.text = if (point.spread > MAX_SPREAD) {
            activity.getString(R.string.calib_msg_unsteady, fmt(point.spread))
        } else ""
        refreshPoints()
        refreshMeasureButton()
    }

    private fun referenceValue(): Double? = when (reference) {
        ReferenceKind.ICE -> 0.0
        ReferenceKind.BOILING -> 100.0
        ReferenceKind.CUSTOM -> customValue.text.toString().replace(',', '.').toDoubleOrNull()
            ?.takeIf { it in -20.0..120.0 }
    }

    private fun cameraLive() = System.currentTimeMillis() - lastFrameAt < LIVE_TIMEOUT_MS

    // --- correction ---------------------------------------------------------------

    private fun apply() {
        when (val result = Correction.fit(points)) {
            is FitResult.Ok -> {
                save(result.correction)
                message.setText(R.string.calib_msg_applied)
            }
            FitResult.NoPoints -> message.setText(R.string.calib_msg_no_points)
            FitResult.TooClose -> message.setText(R.string.calib_msg_too_close)
            is FitResult.TooSteep -> message.text = activity.getString(
                R.string.calib_msg_too_steep, String.format(ru, "%.3f", result.gain),
            )
            is FitResult.TooFar -> message.text = activity.getString(
                R.string.calib_msg_too_far, fmt(result.shift),
            )
        }
    }

    private fun reset() {
        save(Correction.NONE)
        message.setText(R.string.calib_msg_reset)
    }

    private fun save(correction: Correction) {
        prefs.edit()
            .putFloat(PREF_GAIN, correction.gain.toFloat())
            .putFloat(PREF_OFFSET, correction.offset.toFloat())
            .putLong(PREF_AT, if (correction.isIdentity) 0L else System.currentTimeMillis())
            .putString(PREF_SOURCE, if (correction.isIdentity) null else sourceLabel())
            .apply()
        onCorrection(correction)
        refreshStatus()
        refreshPoints()
    }

    private fun sourceLabel(): String =
        points.sortedBy { it.reference }.joinToString(" + ") { kindLabel(it) }

    // --- camera constants ---------------------------------------------------------

    /**
     * The system file picker rather than a storage permission: the user points at
     * one JPEG, and the app never sees anything else on the phone.
     */
    private fun pickJpeg() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/jpeg")
        activity.startActivityForResult(intent, REQUEST_JPEG)
    }

    /** True when the result was this screen's. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_JPEG) return false
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) return true
        // A FLIR JPEG is 1.5-2 MB; reading it is not a job for the UI thread.
        kotlin.concurrent.thread(name = "flir-fff") {
            val outcome = try {
                val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw java.io.IOException("empty")
                Result.success(FffReader.read(bytes))
            } catch (e: Exception) {
                Result.failure(e)
            }
            activity.runOnUiThread {
                outcome.fold(
                    onSuccess = { useConstants(it, imported = true) },
                    onFailure = { e ->
                        showConstantsMessage(
                            if (e is FffReader.NotFlir) activity.getString(R.string.consts_msg_not_flir)
                            else activity.getString(R.string.consts_msg_read_failed, e.message ?: e.javaClass.simpleName),
                        )
                    },
                )
            }
        }
        return true
    }

    private fun useConstants(constants: CameraConstants, imported: Boolean) {
        val current = planck()
        val same = constants.sameAs(CameraConstants.of(current))
        if (imported) {
            prefs.edit()
                .putString(PREF_CONSTANTS, constants.serialize())
                .putLong(PREF_CONSTANTS_AT, System.currentTimeMillis())
                .apply()
        } else {
            prefs.edit().remove(PREF_CONSTANTS).remove(PREF_CONSTANTS_AT).apply()
        }
        if (same) {
            if (imported) showConstantsMessage(activity.getString(R.string.consts_msg_same))
            refreshConstants()
            return
        }
        onConstants(constants)
        // A correction and its points were measured through the old constants; on the
        // new ones they describe a camera that is no longer the one being read.
        val hadCalibration = !planck().correction.isIdentity || points.isNotEmpty()
        if (hadCalibration) {
            points.clear()
            savePoints()
            save(Correction.NONE)
            message.text = ""
        }
        showConstantsMessage(
            activity.getString(
                if (hadCalibration) R.string.consts_msg_applied else R.string.consts_msg_applied_clean,
            ),
        )
        refreshAll()
    }

    private fun showConstantsMessage(text: String) {
        constsMessage.text = text
        constsMessage.visibility = View.VISIBLE
    }

    private fun refreshConstants() {
        val p = planck()
        val saved = savedConstants()
        constsStatus.text = if (saved == null) {
            activity.getString(R.string.consts_built_in_status)
        } else {
            val at = prefs.getLong(PREF_CONSTANTS_AT, 0L)
            activity.getString(
                R.string.consts_imported_status,
                saved.model.ifEmpty { "FLIR" },
                if (at == 0L) EMPTY else DateFormat.getDateInstance(DateFormat.MEDIUM, ru).format(Date(at)),
            )
        }
        constsValues.text = activity.getString(
            R.string.consts_values,
            String.format(ru, "%.3f", p.r1),
            String.format(ru, "%.1f", p.b),
            String.format(ru, "%.3f", p.f),
            String.format(ru, "%.0f", p.o).replace('-', '−'),
            String.format(ru, "%.5f", p.r2),
            String.format(ru, "%.2f", p.irWindowTransmission),
            fmt(p.irWindowTemperature - 273.15),
            fmt(p.atmosphericTemperature - 273.15),
        )
    }

    // --- views --------------------------------------------------------------------

    private fun refreshStatus() {
        val correction = planck().correction
        if (correction.isIdentity) {
            status.setText(R.string.calib_none)
            statusSub.setText(R.string.calib_none_sub)
            return
        }
        status.text = correction.label(ru)
        val at = prefs.getLong(PREF_AT, 0L)
        val date = if (at == 0L) EMPTY
            else DateFormat.getDateInstance(DateFormat.MEDIUM, ru).format(Date(at))
        statusSub.text = activity.getString(
            R.string.calib_applied_sub, prefs.getString(PREF_SOURCE, null) ?: EMPTY, date,
        )
    }

    private fun buildReferenceOptions() {
        refOptions.removeAllViews()
        val kinds = listOf(
            ReferenceKind.ICE to R.string.calib_ref_ice,
            ReferenceKind.BOILING to R.string.calib_ref_boiling,
            ReferenceKind.CUSTOM to R.string.calib_ref_custom,
        )
        kinds.forEachIndexed { index, (kind, label) ->
            val selected = kind == reference
            val option = TextView(activity).apply {
                setText(label)
                gravity = Gravity.CENTER
                minHeight = dp(48)
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(activity.getColor(if (selected) R.color.ff_text else R.color.ff_muted))
                setBackgroundResource(if (selected) R.drawable.bg_opt_on else R.drawable.bg_opt)
                isClickable = true
                setOnClickListener {
                    if (samples != null) return@setOnClickListener
                    reference = kind
                    buildReferenceOptions()
                    refreshReference()
                }
            }
            refOptions.addView(
                option,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(8)
                },
            )
        }
    }

    private fun refreshReference() {
        customRow.visibility = if (reference == ReferenceKind.CUSTOM) View.VISIBLE else View.GONE
        refNote.setText(
            when (reference) {
                ReferenceKind.ICE -> R.string.calib_note_ice
                ReferenceKind.BOILING -> R.string.calib_note_boiling
                ReferenceKind.CUSTOM -> R.string.calib_note_custom
            },
        )
    }

    private fun refreshMeasureButton() {
        val gathering = samples
        val enabled = gathering == null && cameraLive()
        measureButton.text = when {
            gathering != null -> activity.getString(R.string.calib_measuring, gathering.size, SAMPLES)
            !cameraLive() -> activity.getString(R.string.calib_no_camera)
            else -> activity.getString(R.string.calib_measure)
        }
        measureButton.isEnabled = enabled
        measureButton.alpha = if (enabled || gathering != null) 1f else 0.45f
    }

    private fun refreshPoints() {
        pointsList.removeAllViews()
        if (points.isEmpty()) {
            pointsList.addView(TextView(activity).apply {
                setText(R.string.calib_points_empty)
                setTextAppearance(R.style.Ff_Note)
                setPadding(0, dp(12), 0, 0)
            })
            return
        }
        for (point in points.sortedBy { it.reference }) {
            pointsList.addView(pointRow(point))
            pointsList.addView(View(activity).apply {
                setBackgroundColor(activity.getColor(R.color.ff_border))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
        }
    }

    private fun pointRow(point: ReferencePoint): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(activity).apply {
            setTextAppearance(R.style.Ff_RowTitle)
            text = activity.getString(
                R.string.calib_point, kindLabel(point), fmt(point.reference), fmt(point.measured),
            )
        })
        texts.addView(TextView(activity).apply {
            setTextAppearance(R.style.Ff_RowSub)
            text = activity.getString(
                R.string.calib_point_sub,
                signed(point.measured - point.reference),
                fmt(point.spread),
            )
        })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            ImageButton(activity).apply {
                setImageResource(R.drawable.ic_close)
                setBackgroundResource(R.drawable.bg_tool)
                contentDescription = activity.getString(R.string.calib_remove_point)
                setOnClickListener {
                    points.remove(point)
                    savePoints()
                    refreshPoints()
                }
            },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        return row
    }

    private fun kindLabel(point: ReferencePoint): String = activity.getString(
        when (point.kind) {
            ReferenceKind.ICE -> R.string.calib_ref_ice_short
            ReferenceKind.BOILING -> R.string.calib_ref_boiling_short
            ReferenceKind.CUSTOM -> R.string.calib_ref_custom_short
        },
    )

    // --- persistence --------------------------------------------------------------

    /**
     * Points survive leaving the screen and the app: ice and boiling water are rarely
     * both ready at once, and losing the first one to a restart would mean redoing it.
     */
    private fun savePoints() {
        prefs.edit().putString(
            PREF_POINTS,
            points.joinToString(";") { "${it.kind.name}:${it.reference}:${it.measured}:${it.spread}" },
        ).apply()
    }

    private fun loadPoints() {
        points.clear()
        val stored = prefs.getString(PREF_POINTS, null) ?: return
        for (entry in stored.split(';')) {
            val parts = entry.split(':')
            if (parts.size != 4) continue
            val kind = ReferenceKind.entries.firstOrNull { it.name == parts[0] } ?: continue
            val values = parts.drop(1).map { it.toDoubleOrNull() ?: Double.NaN }
            if (values.any { it.isNaN() }) continue
            points += ReferencePoint(values[0], values[1], values[2], kind)
        }
    }

    // --- helpers ------------------------------------------------------------------

    private fun fmt(value: Double) = String.format(ru, "%.1f", value).replace('-', '−')

    private fun signed(value: Double) =
        (if (value < 0) "−" else "+") + String.format(ru, "%.1f", kotlin.math.abs(value))

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        /**
         * The averaged zone: the middle tenth of the frame each way, 8x6 detectors.
         * Big enough that one noisy pixel does not move the figure, small enough to
         * fit inside a cup of water held at arm's length.
         */
        val ZONE = RectF(0.45f, 0.45f, 0.55f, 0.55f)

        /** Water, for both references; also what the article's method prescribes. */
        const val WATER_EMISSIVITY = 0.96

        /** About three seconds at the camera's ~8.7 frames per second. */
        const val SAMPLES = 26

        /** Above this the reading moved during the measurement - worth redoing. */
        const val MAX_SPREAD = 1.0

        const val LIVE_TIMEOUT_MS = 1500L

        const val PREF_CONSTANTS = "camera_constants"
        const val PREF_CONSTANTS_AT = "camera_constants_at"
        const val REQUEST_JPEG = 41

        const val PREF_GAIN = "correction_gain"
        const val PREF_OFFSET = "correction_offset"
        const val PREF_AT = "correction_at"
        const val PREF_SOURCE = "correction_source"
        /**
         * v2 since the IR window went into [Planck] (2026-10-04): points measured
         * before it carry the old, compressed readings and would fit a wrong slope.
         */
        const val PREF_POINTS = "calibration_points_v2"

        private const val EMPTY = "—"
    }
}
