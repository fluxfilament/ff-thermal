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

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The whole interface: a live view and the five screens behind it.
 *
 * Screens are views in one window rather than separate activities. The reason is the
 * camera: this activity owns the USB connection and the one-shot sledInformation
 * message that carries the sensor geometry, and handing the window to another activity
 * puts both at risk for no gain. It also means the field-of-view screen shows live
 * frames, which is the only way to line two lenses up.
 */
class MainActivity : Activity(), FlirOneCamera.Listener {

    /** Which screen is in front. Read on the camera thread to skip hidden work. */
    @Volatile
    private var screen = Screen.LIVE

    private enum class Screen { LIVE, SETTINGS, EMISSIVITY, FOV, CALIBRATION, LOG }

    /** Which end of the scale the sheet is editing, or null when it is closed. */
    private enum class SheetTarget { LOW, HIGH }

    private class GlossPreset(
        val nameRes: Int,
        val examplesRes: Int,
        val emissivity: Double,
        val gloss: Float,
        val isDefault: Boolean = false,
    )

    private companion object {
        const val TAG = "FlirOneViewer"
        const val ACTION_USB_PERMISSION = "com.bikbovdamir.flironeviewer.USB_PERMISSION"
        const val MAX_LOG_LINES = 400
        const val PREFS = "flir-one-viewer"
        const val PREF_EMISSIVITY = "emissivity"
        const val PREF_FOV_RATIO = "fovRatio"
        const val PREF_PARALLAX = "parallax"
        const val PREF_ROTATION = "rotation"
        const val PREF_SPOTS = "spots"
        const val PREF_MIRRORED = "mirrored"
        const val PREF_RANGE_FIXED = "rangeFixed"
        const val PREF_RANGE_LOW = "rangeLow"
        const val PREF_RANGE_HIGH = "rangeHigh"
        const val PREF_PALETTE = "palette"

        /** Alignment slider covers +/- this fraction of the visible frame's width. */
        const val ALIGN_RANGE = 0.10f

        /**
         * Ends of the fixed-contrast scale, in whole degrees. Roughly the span this
         * camera is specified for, widened a little at the top: readings above it are
         * increasingly meaningless, but a window that cannot reach a soldering iron is
         * more annoying than one that can be set somewhere useless.
         */
        const val RANGE_MIN_C = -20
        const val RANGE_MAX_C = 150

        /** The window never closes completely; below a degree everything saturates. */
        const val MIN_SPAN_C = 1

        /** Where the window sits before it has ever been set: indoor scenes. */
        const val DEFAULT_RANGE_LOW_C = 15
        const val DEFAULT_RANGE_HIGH_C = 40

        /**
         * How far past halfway the phone has to be turned before the labels follow.
         *
         * Rounding at exactly 45 degrees makes them flip back and forth while the
         * phone is held on a diagonal, which is far more distracting than being a
         * little late to turn.
         */
        const val ROTATION_HYSTERESIS = 60

        /** How close to a preset counts as being on it. */
        const val PRESET_TOLERANCE = 0.025

        /** Gap between the sheet and the bottom of the window, over the bars below it. */
        const val SHEET_LIFT_DP = 240

        /**
         * Numbers are written the way the interface reads them: a decimal comma. The
         * phone's own locale is not asked, because the strings around the numbers are
         * not translated either - see the note in strings.xml.
         */
        val RU: Locale = Locale.forLanguageTag("ru")

        val MATCH: Int = ViewGroup.LayoutParams.MATCH_PARENT
        val WRAP: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    /**
     * The official app's own surface presets, which are a decent guide and worth
     * matching: someone used to that app should find the same four choices here.
     */
    private val glossPresets = listOf(
        GlossPreset(R.string.preset_matte, R.string.preset_matte_examples, 0.95, 0f, isDefault = true),
        GlossPreset(R.string.preset_semi_matte, R.string.preset_semi_matte_examples, 0.80, 0.22f),
        GlossPreset(R.string.preset_semi_gloss, R.string.preset_semi_gloss_examples, 0.60, 0.50f),
        GlossPreset(R.string.preset_gloss, R.string.preset_gloss_examples, 0.30, 0.85f),
    )

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private lateinit var usbManager: UsbManager
    private lateinit var camera: FlirOneCamera
    private val renderer = ThermalRenderer()
    private val compositor = Compositor()

    /** Snapshots get their own, so composing a file cannot race the live view. */
    private val snapshotCompositor = Compositor()

    /** And so does the field-of-view preview: the decoder reuses one bitmap. */
    private val previewCompositor = Compositor()

    private lateinit var root: FrameLayout

    // live screen
    private lateinit var screenLive: View
    private lateinit var topBar: View
    private lateinit var stateStamp: TextView
    private lateinit var geometryView: TextView
    private lateinit var thermalView: ThermalView
    private lateinit var flashView: View
    private lateinit var alignRow: View
    private lateinit var alignLabel: TextView
    private lateinit var alignSeek: SeekBar
    private lateinit var scaleBar: ScaleBarView
    private lateinit var rangeLowValue: TextView
    private lateinit var rangeHighValue: TextView
    private lateinit var lockButton: ImageButton
    private lateinit var emissivityChip: TextView
    private lateinit var bottomBar: View
    private lateinit var blendTool: View
    private lateinit var blendIcon: ImageView
    private lateinit var blendLabel: TextView
    private lateinit var mirrorTool: View
    private lateinit var mirrorIcon: ImageView
    private lateinit var mirrorLabel: TextView
    private lateinit var spotMinusButton: ImageButton
    private lateinit var spotPlusButton: ImageButton
    private lateinit var spotCountValue: TextView

    // settings screen
    private lateinit var screenSettings: View
    private lateinit var settingsHeader: View
    private lateinit var paletteOptions: LinearLayout
    private lateinit var rotationOptions: LinearLayout
    private lateinit var emissivitySummary: TextView
    private lateinit var fovSummary: TextView
    private lateinit var logSummary: TextView

    // emissivity screen
    private lateinit var screenEmissivity: View
    private lateinit var emissivityHeader: View
    private lateinit var emissivityBig: TextView
    private lateinit var emissivitySurface: TextView
    private lateinit var emissivitySeek: SeekBar
    private lateinit var emissivityMarks: FrameLayout
    private lateinit var presetRows: LinearLayout
    private lateinit var metalSwatch: View

    // field of view screen
    private lateinit var screenFov: View
    private lateinit var fovHeader: View
    private lateinit var splitPreview: SplitPreviewView
    private lateinit var thumbThermal: ImageView
    private lateinit var thumbVisible: ImageView
    private lateinit var cropRect: View
    private lateinit var fovValue: TextView
    private lateinit var fovSeek: SeekBar

    // calibration and log screens
    private lateinit var screenCalibration: View
    private lateinit var calibrationHeader: View
    private lateinit var screenLog: View
    private lateinit var logHeader: View
    private lateinit var logScroll: ScrollView
    private lateinit var logView: TextView
    private lateinit var statFrameValue: TextView
    private lateinit var statLeptonValue: TextView
    private lateinit var statStreamValue: TextView
    private lateinit var statFfcValue: TextView
    private lateinit var statResyncValue: TextView
    private lateinit var statSceneValue: TextView

    // scale sheet
    private lateinit var sheetLayer: View
    private lateinit var sheetRange: View
    private lateinit var sheetTitle: TextView
    private lateinit var sheetValue: TextView
    private lateinit var sheetStamp: TextView
    private lateinit var sheetHint: TextView
    private lateinit var sheetSeek: SeekBar
    private lateinit var sheetOther: TextView

    private val screenViews by lazy {
        mapOf(
            Screen.LIVE to screenLive,
            Screen.SETTINGS to screenSettings,
            Screen.EMISSIVITY to screenEmissivity,
            Screen.FOV to screenFov,
            Screen.CALIBRATION to screenCalibration,
            Screen.LOG to screenLog,
        )
    }

    /** Where "back" goes, innermost last. */
    private val backStack = ArrayDeque<Screen>()

    private var sheetTarget: SheetTarget? = null

    /** Mixing the visible layer costs a JPEG decode per frame, so it starts off. */
    private var blendMode = BlendMode.THERMAL

    /**
     * Mirrored by default: plugged into the phone the camera points back at whoever
     * is holding it, and an unmirrored front camera reads as wrong to everyone.
     */
    private var mirrored = true

    /**
     * Which way the picture has to be turned. A setting, not a constant: USB-C goes
     * in either way up, and flipping the dongle turns the sensor with it.
     */
    private var rotation = ViewTransform.DEFAULT_ROTATION

    private val spotMeter = SpotMeter()

    /**
     * How far the phone itself is turned, in degrees clockwise from upright.
     *
     * The window is locked to portrait so that it cannot fight the camera bolted to
     * it, which means turning the phone turns everything drawn in it - the spot
     * readings included - and leaves them sideways to the person reading them. The
     * accelerometer is the only thing left that still knows which way is up, and it
     * is used for this and nothing else: the picture stays where the camera put it.
     */
    private var deviceRotation = 0

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                // Reported when the phone is lying flat, where "up" has no meaning.
                if (degrees == ORIENTATION_UNKNOWN) return
                val snapped = snapRotation(degrees, deviceRotation)
                if (snapped == deviceRotation) return
                deviceRotation = snapped
                thermalView.labelRotation = snapped
            }
        }
    }

    /**
     * The fixed contrast window, held in degrees rather than in the counts the
     * renderer wants. Degrees are what the user set and what survives a change of
     * emissivity; the counts are re-derived from them whenever either moves.
     */
    private var rangeFixed = false
    private var rangeLowC = DEFAULT_RANGE_LOW_C
    private var rangeHighC = DEFAULT_RANGE_HIGH_C

    /**
     * Coefficients for the unit this was developed on, with the user's emissivity
     * applied on top. Readings from another camera will be off - see [Planck] for how
     * to read its own out of a saved JPEG.
     */
    @Volatile
    private var planck = Planck.DEVELOPMENT_UNIT

    private val logLines = ArrayDeque<String>()
    private val repaintPending = AtomicBoolean(false)

    private var geometry: SledInfo? = null
    private var lastStats: FlirOneCamera.Stats? = null

    /** Written from the camera thread, read on the UI thread. */
    @Volatile
    private var calibrating = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.usbDeviceExtra()
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { camera.start(it) }
                    } else {
                        onLog("USB permission denied by user for device: $device")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                    intent.usbDeviceExtra()?.let { requestPermissionOrStart(it) }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    onLog("Camera detached.")
                    camera.stop()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setUpInsets()
        setUpBackHandling()

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        camera = FlirOneCamera(usbManager, this)

        renderer.palette = prefs.getString(PREF_PALETTE, null)
            ?.let { saved -> Palette.entries.firstOrNull { it.name == saved } }
            ?: Palette.IRON
        mirrored = prefs.getBoolean(PREF_MIRRORED, true)
        rotation = prefs.getInt(PREF_ROTATION, ViewTransform.DEFAULT_ROTATION)
        // Defaults are laid out as the viewer sees them, so the meter has to know
        // which way the picture is turned before it places any.
        spotMeter.rotation = rotation
        spotMeter.mirrored = mirrored
        spotMeter.setCount(prefs.getInt(PREF_SPOTS, 1))

        setUpLiveScreen()
        setUpNavigation()
        setUpEmissivity()
        setUpRange()
        setUpOverlayControls()
        buildPaletteOptions()
        buildRotationOptions()
        buildPresetRows()
        metalSwatch.background = metalSwatchDrawable()

        updateBlendTool()
        updateMirrorTool()
        updateSpotCount()
        refreshTopBar()
        refreshLegend()
        refreshSummaries()
        refreshStats()

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }

        onLog("FLIR One Viewer - live view")
        findFlirDevice()?.let { requestPermissionOrStart(it) }
            ?: onLog(
                "Camera not attached. Plug in the FLIR One and re-open the app, or it " +
                        "should auto-launch via the USB_DEVICE_ATTACHED intent filter."
            )
    }

    private fun bindViews() {
        root = findViewById(R.id.root)

        screenLive = findViewById(R.id.screenLive)
        topBar = findViewById(R.id.topBar)
        stateStamp = findViewById(R.id.stateStamp)
        geometryView = findViewById(R.id.geometryView)
        thermalView = findViewById(R.id.thermalView)
        flashView = findViewById(R.id.flashView)
        alignRow = findViewById(R.id.alignRow)
        alignLabel = findViewById(R.id.alignLabel)
        alignSeek = findViewById(R.id.alignSeek)
        scaleBar = findViewById(R.id.scaleBar)
        rangeLowValue = findViewById(R.id.rangeLowValue)
        rangeHighValue = findViewById(R.id.rangeHighValue)
        lockButton = findViewById(R.id.lockButton)
        emissivityChip = findViewById(R.id.emissivityChip)
        bottomBar = findViewById(R.id.bottomBar)
        blendTool = findViewById(R.id.blendTool)
        blendIcon = findViewById(R.id.blendIcon)
        blendLabel = findViewById(R.id.blendLabel)
        mirrorTool = findViewById(R.id.mirrorTool)
        mirrorIcon = findViewById(R.id.mirrorIcon)
        mirrorLabel = findViewById(R.id.mirrorLabel)
        spotMinusButton = findViewById(R.id.spotMinusButton)
        spotPlusButton = findViewById(R.id.spotPlusButton)
        spotCountValue = findViewById(R.id.spotCountValue)

        screenSettings = findViewById(R.id.screenSettings)
        settingsHeader = findViewById(R.id.settingsHeader)
        paletteOptions = findViewById(R.id.paletteOptions)
        rotationOptions = findViewById(R.id.rotationOptions)
        emissivitySummary = findViewById(R.id.emissivitySummary)
        fovSummary = findViewById(R.id.fovSummary)
        logSummary = findViewById(R.id.logSummary)

        screenEmissivity = findViewById(R.id.screenEmissivity)
        emissivityHeader = findViewById(R.id.emissivityHeader)
        emissivityBig = findViewById(R.id.emissivityBig)
        emissivitySurface = findViewById(R.id.emissivitySurface)
        emissivitySeek = findViewById(R.id.emissivitySeek)
        emissivityMarks = findViewById(R.id.emissivityMarks)
        presetRows = findViewById(R.id.presetRows)
        metalSwatch = findViewById(R.id.metalSwatch)

        screenFov = findViewById(R.id.screenFov)
        fovHeader = findViewById(R.id.fovHeader)
        splitPreview = findViewById(R.id.splitPreview)
        thumbThermal = findViewById(R.id.thumbThermal)
        thumbVisible = findViewById(R.id.thumbVisible)
        cropRect = findViewById(R.id.cropRect)
        fovValue = findViewById(R.id.fovValue)
        fovSeek = findViewById(R.id.fovSeek)

        screenCalibration = findViewById(R.id.screenCalibration)
        calibrationHeader = findViewById(R.id.calibrationHeader)
        screenLog = findViewById(R.id.screenLog)
        logHeader = findViewById(R.id.logHeader)
        logScroll = findViewById(R.id.logScroll)
        logView = findViewById(R.id.logView)
        statFrameValue = findViewById(R.id.statFrameValue)
        statLeptonValue = findViewById(R.id.statLeptonValue)
        statStreamValue = findViewById(R.id.statStreamValue)
        statFfcValue = findViewById(R.id.statFfcValue)
        statResyncValue = findViewById(R.id.statResyncValue)
        statSceneValue = findViewById(R.id.statSceneValue)

        sheetLayer = findViewById(R.id.sheetLayer)
        sheetRange = findViewById(R.id.sheetRange)
        sheetTitle = findViewById(R.id.sheetTitle)
        sheetValue = findViewById(R.id.sheetValue)
        sheetStamp = findViewById(R.id.sheetStamp)
        sheetHint = findViewById(R.id.sheetHint)
        sheetSeek = findViewById(R.id.sheetSeek)
        sheetOther = findViewById(R.id.sheetOther)
    }

    // --- window edges -------------------------------------------------------------

    /**
     * Pushes the system bars' own space into the views that sit against them.
     *
     * The window is edge-to-edge, which is not optional from targetSdk 35 on. Before
     * this, the status line at the top of the screen was underneath the clock and
     * simply could not be read, and the button row at the bottom ran into the
     * home-gesture strip. Measured insets rather than the 32 dp the design was drawn
     * against: that figure is this phone's, and the next phone's will differ.
     */
    private fun setUpInsets() {
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            for (header in listOf(
                topBar, settingsHeader, emissivityHeader, fovHeader, calibrationHeader, logHeader,
            )) {
                header.setPadding(header.paddingLeft, top, header.paddingRight, header.paddingBottom)
            }
            bottomBar.setPadding(
                bottomBar.paddingLeft, bottomBar.paddingTop, bottomBar.paddingRight, bottom,
            )
            // The scrolling screens end above the gesture strip rather than under it.
            for (view in listOf(
                screenSettings, screenEmissivity, screenFov, screenCalibration, screenLog,
            )) {
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottom)
            }
            (sheetRange.layoutParams as FrameLayout.LayoutParams).let {
                it.bottomMargin = dp(SHEET_LIFT_DP) + bottom
                sheetRange.layoutParams = it
            }
            insets
        }
        // Light glyphs in the status bar: everything behind them is dark.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            )
        }
    }

    // --- navigation ---------------------------------------------------------------

    private fun setUpNavigation() {
        findViewById<View>(R.id.settingsButton).setOnClickListener { go(Screen.SETTINGS) }
        findViewById<View>(R.id.settingsBack).setOnClickListener { handleBack() }
        findViewById<View>(R.id.emissivityBack).setOnClickListener { handleBack() }
        findViewById<View>(R.id.fovBack).setOnClickListener { handleBack() }
        findViewById<View>(R.id.calibrationBack).setOnClickListener { handleBack() }
        findViewById<View>(R.id.logBack).setOnClickListener { handleBack() }
        findViewById<View>(R.id.rowEmissivity).setOnClickListener { go(Screen.EMISSIVITY) }
        findViewById<View>(R.id.rowFov).setOnClickListener { go(Screen.FOV) }
        findViewById<View>(R.id.rowCalibration).setOnClickListener { go(Screen.CALIBRATION) }
        findViewById<View>(R.id.rowLog).setOnClickListener { go(Screen.LOG) }
        // Straight from the picture to the one setting that changes what it measures.
        emissivityChip.setOnClickListener { go(Screen.EMISSIVITY) }
    }

    private fun go(target: Screen) {
        if (target == screen) return
        backStack.addLast(screen)
        show(target)
    }

    private fun show(target: Screen) {
        closeSheet()
        for ((key, view) in screenViews) {
            view.visibility = if (key == target) View.VISIBLE else View.GONE
        }
        screen = target
        when (target) {
            Screen.SETTINGS -> refreshSummaries()
            Screen.EMISSIVITY -> refreshEmissivityScreen()
            Screen.FOV -> refreshFovScreen()
            Screen.LOG -> refreshStats()
            else -> Unit
        }
    }

    /** True when the press was consumed here rather than leaving the app. */
    private fun handleBack(): Boolean {
        if (sheetLayer.visibility == View.VISIBLE) {
            closeSheet()
            return true
        }
        val previous = backStack.removeLastOrNull() ?: return false
        show(previous)
        return true
    }

    private fun setUpBackHandling() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { if (!handleBack()) finish() }
        }
    }

    /** Pre-33 path; from 33 on the dispatcher callback above is what runs. */
    @Deprecated("Superseded by OnBackInvokedCallback on API 33+, still needed below it")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!handleBack()) super.onBackPressed()
    }

    // --- live screen --------------------------------------------------------------

    private fun setUpLiveScreen() {
        findViewById<View>(R.id.shutterButton).setOnClickListener { saveSnapshot() }
        blendTool.setOnClickListener { cycleBlend() }
        mirrorTool.setOnClickListener { toggleMirror() }
        spotMinusButton.setOnClickListener { if (spotMeter.remove()) updateSpotCount() }
        spotPlusButton.setOnClickListener { if (spotMeter.add()) updateSpotCount() }
        thermalView.onSpotMoved = { index, u, v -> spotMeter.move(index, u, v) }
        findViewById<View>(R.id.rangeLowButton).setOnClickListener { openSheet(SheetTarget.LOW) }
        findViewById<View>(R.id.rangeHighButton).setOnClickListener { openSheet(SheetTarget.HIGH) }
        lockButton.setOnClickListener { toggleLock() }
    }

    private fun refreshTopBar() {
        val info = geometry
        val state: Int
        val stamp: Int
        val colour: Int
        when {
            calibrating -> {
                state = R.string.state_calibrating
                stamp = R.drawable.bg_stamp_done
                colour = R.color.ff_accent
            }
            !camera.isRunning() -> {
                state = R.string.state_no_camera
                stamp = R.drawable.bg_stamp_quiet
                colour = R.color.ff_muted
            }
            info == null -> {
                state = R.string.state_waiting
                stamp = R.drawable.bg_stamp_quiet
                colour = R.color.ff_muted
            }
            else -> {
                state = R.string.state_live
                stamp = R.drawable.bg_stamp_wip
                colour = R.color.ff_accent2
            }
        }
        stateStamp.setText(state)
        stateStamp.setBackgroundResource(stamp)
        stateStamp.setTextColor(getColor(colour))

        geometryView.text = when {
            info == null -> getString(R.string.geometry_unknown)
            info.versionLepton != null ->
                getString(R.string.geometry, info.width, info.height, info.versionLepton)
            else -> getString(R.string.geometry_short, info.width, info.height)
        }
    }

    /** The two figures beside the scale, and the scale itself. */
    private fun refreshLegend() {
        // Always, not only when there is a window: the value is what the readings mean,
        // and an empty chip is worse than one that says what it is set to.
        emissivityChip.text = getString(R.string.emissivity_chip, dec(planck.emissivity, 2))
        val window = currentWindow()
        if (window == null) {
            rangeLowValue.text = getString(R.string.stat_none)
            rangeHighValue.text = getString(R.string.stat_none)
            scaleBar.show(renderer.palette, 0f, 0f, planck)
            return
        }
        scaleBar.show(renderer.palette, window.lo, window.hi, planck)
        if (rangeFixed) {
            rangeLowValue.text = getString(R.string.degrees_short, rangeLowC.toString())
            rangeHighValue.text = getString(R.string.degrees_short, rangeHighC.toString())
        } else {
            val lo = planck.rawToCelsius(window.lo.roundToInt())
            val hi = planck.rawToCelsius(window.hi.roundToInt())
            rangeLowValue.text = getString(R.string.degrees_short, dec(lo))
            rangeHighValue.text = getString(R.string.degrees_short, dec(hi))
        }
    }

    /**
     * The contrast window as counts: what the user pinned, or what auto-gain is
     * currently showing. Null until the first frame has set the automatic bounds.
     */
    private fun currentWindow(): ContrastRange? {
        if (rangeFixed) {
            val lo = planck.celsiusToRaw(rangeLowC.toDouble())
            val hi = planck.celsiusToRaw(rangeHighC.toDouble())
            if (!lo.isNaN() && !hi.isNaN()) return ContrastRange(lo.toFloat(), hi.toFloat())
        }
        return renderer.autoRange
    }

    private fun cycleBlend() {
        blendMode = blendMode.next()
        updateBlendTool()
    }

    private fun updateBlendTool() {
        val on = blendMode != BlendMode.THERMAL
        blendLabel.setText(
            when (blendMode) {
                BlendMode.THERMAL -> R.string.blend_thermal
                BlendMode.MIX_35 -> R.string.blend_mix35
                BlendMode.MIX_60 -> R.string.blend_mix60
                BlendMode.VISIBLE -> R.string.blend_visible
            }
        )
        tintTool(blendTool, blendIcon, blendLabel, on)
        alignRow.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun toggleMirror() {
        mirrored = !mirrored
        spotMeter.mirrored = mirrored
        prefs.edit().putBoolean(PREF_MIRRORED, mirrored).apply()
        updateMirrorTool()
    }

    private fun updateMirrorTool() = tintTool(mirrorTool, mirrorIcon, mirrorLabel, mirrored)

    /** A tool that is doing something says so in the accent colour, frame included. */
    private fun tintTool(tool: View, icon: ImageView, label: TextView, on: Boolean) {
        tool.setBackgroundResource(if (on) R.drawable.bg_tool_on else R.drawable.bg_tool)
        val colour = getColor(if (on) R.color.ff_accent2 else R.color.ff_text)
        icon.imageTintList = ColorStateList.valueOf(colour)
        label.setTextColor(getColor(if (on) R.color.ff_accent2 else R.color.ff_muted))
    }

    private fun updateSpotCount() {
        prefs.edit().putInt(PREF_SPOTS, spotMeter.count).apply()
        spotCountValue.text = spotMeter.count.toString()
        setEnabled(spotMinusButton, spotMeter.count > 0)
        setEnabled(spotPlusButton, spotMeter.count < 9)
    }

    private fun setEnabled(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.4f
    }

    // --- the scale: automatic, or pinned to a temperature window -------------------

    private fun setUpRange() {
        rangeFixed = prefs.getBoolean(PREF_RANGE_FIXED, false)
        rangeLowC = prefs.getInt(PREF_RANGE_LOW, DEFAULT_RANGE_LOW_C)
        rangeHighC = prefs.getInt(PREF_RANGE_HIGH, DEFAULT_RANGE_HIGH_C)
        clampRange()
        applyRange()
        updateLockButton()

        findViewById<View>(R.id.sheetScrim).setOnClickListener { closeSheet() }
        findViewById<View>(R.id.sheetClose).setOnClickListener { closeSheet() }
        findViewById<View>(R.id.sheetDone).setOnClickListener { closeSheet() }
        sheetSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                // Moving an end is what pins the scale: the user has just said which
                // temperature a colour should mean, and auto-gain would overrule them
                // on the next frame.
                rangeFixed = true
                val value = progress + RANGE_MIN_C
                if (sheetTarget == SheetTarget.HIGH) {
                    rangeHighC = value
                    // Push rather than block: a slider that stops dead under the finger
                    // reads as broken.
                    if (rangeHighC - rangeLowC < MIN_SPAN_C) rangeLowC = rangeHighC - MIN_SPAN_C
                } else {
                    rangeLowC = value
                    if (rangeHighC - rangeLowC < MIN_SPAN_C) rangeHighC = rangeLowC + MIN_SPAN_C
                }
                clampRange()
                applyRange()
                updateLockButton()
                updateSheet()
                refreshLegend()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) = saveRange()
        })
    }

    /**
     * Opens the sheet on one end of the scale.
     *
     * While the scale is automatic, the window is first seeded from whatever auto-gain
     * is showing, widened to whole degrees - but not yet pinned. Dropping the user into
     * a window left over from a previous scene would blank the picture to one flat
     * colour at the exact moment they asked for control over it.
     */
    private fun openSheet(target: SheetTarget) {
        if (!rangeFixed) seedRange()
        sheetTarget = target
        sheetLayer.visibility = View.VISIBLE
        updateSheet()
    }

    private fun closeSheet() {
        sheetTarget = null
        sheetLayer.visibility = View.GONE
    }

    private fun updateSheet() {
        val high = sheetTarget == SheetTarget.HIGH
        val value = if (high) rangeHighC else rangeLowC
        sheetTitle.setText(if (high) R.string.sheet_high else R.string.sheet_low)
        sheetValue.text = getString(R.string.sheet_celsius, value)
        sheetSeek.progress = value - RANGE_MIN_C
        sheetOther.text = if (high) {
            getString(R.string.sheet_other_low, rangeLowC)
        } else {
            getString(R.string.sheet_other_high, rangeHighC)
        }
        sheetStamp.visibility = if (rangeFixed) View.VISIBLE else View.GONE
        sheetHint.visibility = if (rangeFixed) View.GONE else View.VISIBLE
    }

    private fun toggleLock() {
        rangeFixed = !rangeFixed
        if (rangeFixed) seedRange() else closeSheet()
        saveRange()
        applyRange()
        updateLockButton()
        refreshLegend()
        if (sheetLayer.visibility == View.VISIBLE) updateSheet()
    }

    private fun updateLockButton() {
        lockButton.setImageResource(
            if (rangeFixed) R.drawable.ic_lock_closed else R.drawable.ic_lock_open
        )
        lockButton.setBackgroundResource(
            if (rangeFixed) R.drawable.bg_lock_on else R.drawable.bg_tool
        )
        lockButton.imageTintList = ColorStateList.valueOf(
            getColor(if (rangeFixed) R.color.ff_accent else R.color.ff_muted)
        )
        lockButton.contentDescription = getString(
            if (rangeFixed) R.string.lock_locked_description else R.string.lock_auto_description
        )
    }

    /** Takes the window auto-gain is showing, rounded outwards to whole degrees. */
    private fun seedRange() {
        val auto = renderer.autoRange ?: return
        val lo = planck.rawToCelsius(auto.lo.roundToInt())
        val hi = planck.rawToCelsius(auto.hi.roundToInt())
        if (lo.isNaN() || hi.isNaN()) return
        rangeLowC = floor(lo).toInt()
        rangeHighC = ceil(hi).toInt()
        clampRange()
    }

    /** Keeps the window inside the slider's reach and at least [MIN_SPAN_C] wide. */
    private fun clampRange() {
        rangeLowC = rangeLowC.coerceIn(RANGE_MIN_C, RANGE_MAX_C - MIN_SPAN_C)
        rangeHighC = rangeHighC.coerceIn(rangeLowC + MIN_SPAN_C, RANGE_MAX_C)
    }

    private fun saveRange() {
        prefs.edit()
            .putBoolean(PREF_RANGE_FIXED, rangeFixed)
            .putInt(PREF_RANGE_LOW, rangeLowC)
            .putInt(PREF_RANGE_HIGH, rangeHighC)
            .apply()
    }

    /** Pushes the window down to the renderer as counts. */
    private fun applyRange() {
        val lo = planck.celsiusToRaw(rangeLowC.toDouble())
        val hi = planck.celsiusToRaw(rangeHighC.toDouble())
        // Falling back to auto-gain on a window the equation cannot express beats
        // rendering against NaN, which would paint the whole frame one colour with
        // nothing on screen to say why.
        renderer.fixedRange =
            if (rangeFixed && !lo.isNaN() && !hi.isNaN()) {
                ContrastRange(lo.toFloat(), hi.toFloat())
            } else {
                null
            }
    }

    // --- emissivity ---------------------------------------------------------------

    /**
     * Wires up the emissivity slider and restores the last value used.
     *
     * It is remembered across launches deliberately: someone measuring the same
     * thing repeatedly should not silently fall back to 0.95 every time they reopen
     * the app, which would quietly change their readings between sessions.
     */
    private fun setUpEmissivity() {
        emissivitySeek.min = (Planck.MIN_EMISSIVITY * 100).roundToInt()
        val saved = prefs.getFloat(PREF_EMISSIVITY, planck.emissivity.toFloat()).toDouble()
        applyEmissivity(saved)
        emissivitySeek.progress = (saved * 100).roundToInt()
        emissivitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                applyEmissivity(progress / 100.0)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            /** Written only when the finger lifts - not on every pixel of the drag. */
            override fun onStopTrackingTouch(bar: SeekBar) {
                prefs.edit().putFloat(PREF_EMISSIVITY, planck.emissivity.toFloat()).apply()
            }
        })
    }

    private fun applyEmissivity(value: Double) {
        val clamped = value.coerceIn(Planck.MIN_EMISSIVITY, Planck.MAX_EMISSIVITY)
        planck = planck.copy(emissivity = clamped)
        // A fixed window is stored in degrees, and emissivity just moved which counts
        // those degrees correspond to. Without this the picture would keep the old
        // window while the numbers beside it moved.
        applyRange()
        refreshLegend()
        refreshEmissivityScreen()
        refreshSummaries()
    }

    private fun refreshEmissivityScreen() {
        val eps = planck.emissivity
        emissivityBig.text = getString(R.string.emissivity_big, dec(eps, 2))
        val nearest = nearestPreset(eps)
        emissivitySurface.text = if (nearest != null) {
            getString(R.string.emissivity_surface, getString(nearest.nameRes))
        } else {
            getString(R.string.emissivity_own)
        }
        buildPresetRows()
        placeEmissivityMarks()
    }

    private fun nearestPreset(eps: Double): GlossPreset? = glossPresets
        .filter { abs(it.emissivity - eps) <= PRESET_TOLERANCE }
        .minByOrNull { abs(it.emissivity - eps) }

    private fun buildPresetRows() {
        presetRows.removeAllViews()
        for (preset in glossPresets) {
            presetRows.addView(presetRow(preset))
            presetRows.addView(separator())
        }
    }

    private fun presetRow(preset: GlossPreset): View {
        val selected = abs(preset.emissivity - planck.emissivity) < 0.005
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            background = rippleBackground()
            setOnClickListener { pickEmissivity(preset.emissivity) }
        }
        val radio = FrameLayout(this).apply {
            setBackgroundResource(if (selected) R.drawable.bg_radio_on else R.drawable.bg_radio)
            addView(
                View(context).apply {
                    if (selected) setBackgroundResource(R.drawable.bg_radio_dot)
                },
                FrameLayout.LayoutParams(dp(8), dp(8), Gravity.CENTER),
            )
        }
        row.addView(radio, LinearLayout.LayoutParams(dp(20), dp(20)))
        row.addView(
            View(this).apply { background = glossSwatch(preset.gloss) },
            LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(12) },
        )
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(context, null, 0, R.style.Ff_RowTitle)
                    .apply { setText(preset.nameRes) },
                LinearLayout.LayoutParams(MATCH, WRAP),
            )
            addView(
                TextView(context, null, 0, R.style.Ff_RowSub)
                    .apply { setText(preset.examplesRes) },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) },
            )
            if (preset.isDefault) {
                addView(
                    TextView(context, null, 0, R.style.Ff_Stamp).apply {
                        setText(R.string.stamp_default)
                        setBackgroundResource(R.drawable.bg_stamp_done)
                        setTextColor(getColor(R.color.ff_accent))
                    },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) },
                )
            }
        }
        row.addView(
            column,
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) },
        )
        row.addView(
            TextView(this, null, 0, R.style.Ff_Mono).apply {
                text = dec(preset.emissivity, 2)
                textSize = 14f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                if (selected) setTextColor(getColor(R.color.ff_accent))
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) },
        )
        return row
    }

    private fun pickEmissivity(value: Double) {
        emissivitySeek.progress = (value * 100).roundToInt()
        prefs.edit().putFloat(PREF_EMISSIVITY, value.toFloat()).apply()
    }

    /**
     * Puts each preset's label where its value actually falls on the slider.
     *
     * Evenly spaced labels would be a lie about a scale whose presets are not evenly
     * spaced - 0.95, 0.80, 0.60, 0.30 bunch up at the top.
     */
    private fun placeEmissivityMarks() {
        emissivityMarks.post {
            emissivityMarks.removeAllViews()
            val width = emissivityMarks.width
            if (width == 0) return@post
            // The thumb travels between the slider's own end paddings, not the view's
            // edges, so the marks have to use the same span.
            val inset = dp(10)
            val span = width - 2 * inset
            val low = Planck.MIN_EMISSIVITY
            val range = Planck.MAX_EMISSIVITY - low
            for (preset in glossPresets) {
                val label = TextView(this, null, 0, R.style.Ff_Mono).apply {
                    text = dec(preset.emissivity, 2)
                    textSize = 10.5f
                    setTextColor(getColor(R.color.ff_muted))
                }
                label.measure(0, 0)
                val centre = inset + ((preset.emissivity - low) / range * span).toInt()
                emissivityMarks.addView(
                    label,
                    FrameLayout.LayoutParams(WRAP, WRAP).apply {
                        marginStart = (centre - label.measuredWidth / 2)
                            .coerceIn(0, width - label.measuredWidth)
                    },
                )
            }
        }
    }

    // --- settings -----------------------------------------------------------------

    private fun buildPaletteOptions() {
        paletteOptions.removeAllViews()
        Palette.entries.chunked(2).forEachIndexed { rowIndex, chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEachIndexed { index, palette ->
                row.addView(
                    paletteOption(palette),
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                        if (index > 0) marginStart = dp(8)
                    },
                )
            }
            // An odd count would otherwise leave the last option double width.
            if (chunk.size == 1) {
                row.addView(
                    Space(this),
                    LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) },
                )
            }
            paletteOptions.addView(
                row,
                LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    if (rowIndex > 0) topMargin = dp(8)
                },
            )
        }
    }

    private fun paletteOption(palette: Palette): View {
        val selected = renderer.palette == palette
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            setBackgroundResource(if (selected) R.drawable.bg_opt_on else R.drawable.bg_opt)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            addView(
                View(context).apply { background = paletteStrip(palette) },
                LinearLayout.LayoutParams(MATCH, dp(8)),
            )
            addView(
                TextView(context, null, 0, R.style.Ff_Mono).apply {
                    text = palette.label.uppercase(RU)
                    textSize = 12f
                    letterSpacing = 0.06f
                    gravity = Gravity.CENTER
                    if (selected) setTextColor(getColor(R.color.ff_accent))
                },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(7) },
            )
            setOnClickListener { pickPalette(palette) }
        }
    }

    private fun pickPalette(palette: Palette) {
        renderer.palette = palette
        prefs.edit().putString(PREF_PALETTE, palette.name).apply()
        buildPaletteOptions()
        refreshLegend()
    }

    private fun buildRotationOptions() {
        rotationOptions.removeAllViews()
        ViewTransform.ROTATIONS.forEachIndexed { index, degrees ->
            val selected = rotation == degrees
            val option = TextView(this, null, 0, R.style.Ff_Mono).apply {
                text = getString(R.string.rotation_option, degrees)
                textSize = 12f
                letterSpacing = 0.06f
                gravity = Gravity.CENTER
                minHeight = dp(48)
                setBackgroundResource(if (selected) R.drawable.bg_opt_on else R.drawable.bg_opt)
                if (selected) setTextColor(getColor(R.color.ff_accent))
                isClickable = true
                setOnClickListener { pickRotation(degrees) }
            }
            rotationOptions.addView(
                option,
                LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    if (index > 0) marginStart = dp(8)
                },
            )
        }
    }

    private fun pickRotation(degrees: Int) {
        rotation = degrees
        spotMeter.rotation = degrees
        prefs.edit().putInt(PREF_ROTATION, degrees).apply()
        buildRotationOptions()
    }

    private fun refreshSummaries() {
        val eps = planck.emissivity
        val nearest = nearestPreset(eps)
        emissivitySummary.text = getString(
            R.string.emissivity_summary,
            dec(eps, 2),
            nearest?.let { getString(it.nameRes).lowercase(RU) }
                ?: getString(R.string.emissivity_custom),
        )
        fovSummary.text = getString(R.string.fov_summary, dec(compositor.fovRatio.toDouble(), 2))
        val stats = lastStats
        logSummary.text = if (stats == null) {
            getString(R.string.log_summary_idle)
        } else {
            getString(R.string.log_summary, dec(stats.fps.toDouble()), stats.droppedFfc, stats.resyncs)
        }
    }

    // --- field of view ------------------------------------------------------------

    /**
     * The two alignment controls. Both are remembered: the field-of-view crop is a
     * property of this camera's optics that only needs finding once, and the parallax
     * setting is usually left wherever the user's typical working distance puts it.
     */
    private fun setUpOverlayControls() {
        compositor.fovRatio = prefs.getFloat(PREF_FOV_RATIO, Compositor.DEFAULT_FOV_RATIO)
        compositor.parallax = prefs.getFloat(PREF_PARALLAX, Compositor.DEFAULT_PARALLAX)
        fovSeek.progress = (compositor.fovRatio * 100).roundToInt()
        alignSeek.progress = ((compositor.parallax / ALIGN_RANGE + 1f) * 100f).roundToInt()
        updateFovLabels()

        fovSeek.setOnSeekBarChangeListener(seekListener({ progress ->
            compositor.fovRatio = progress / 100f
        }, { prefs.edit().putFloat(PREF_FOV_RATIO, compositor.fovRatio).apply() }))

        alignSeek.setOnSeekBarChangeListener(seekListener({ progress ->
            compositor.parallax = (progress / 100f - 1f) * ALIGN_RANGE
        }, { prefs.edit().putFloat(PREF_PARALLAX, compositor.parallax).apply() }))

        findViewById<View>(R.id.fovReset).setOnClickListener {
            fovSeek.progress = (Compositor.DEFAULT_FOV_RATIO * 100).roundToInt()
            prefs.edit().putFloat(PREF_FOV_RATIO, compositor.fovRatio).apply()
        }
    }

    private fun seekListener(
        onChange: (Int) -> Unit,
        onCommit: () -> Unit,
    ) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
            onChange(progress)
            updateFovLabels()
        }

        override fun onStartTrackingTouch(bar: SeekBar) = Unit

        override fun onStopTrackingTouch(bar: SeekBar) = onCommit()
    }

    private fun updateFovLabels() {
        fovValue.text = dec(compositor.fovRatio.toDouble(), 2)
        alignLabel.text = getString(R.string.align_label, signed(compositor.parallax.toDouble()))
        placeCropRect()
        refreshSummaries()
    }

    private fun refreshFovScreen() {
        // Both screens share one crop, so the preview has to start from the live ones.
        previewCompositor.fovRatio = compositor.fovRatio
        previewCompositor.parallax = compositor.parallax
        updateFovLabels()
    }

    /**
     * The dashed rectangle on the visible-light thumbnail: which part of the wider
     * frame the current crop keeps.
     *
     * Placed against the drawn image rather than the box around it - the thumbnail is
     * portrait and the frame is landscape, so there are bands above and below it.
     */
    private fun placeCropRect() {
        val frameWidth = thumbVisible.width
        val frameHeight = thumbVisible.height
        if (frameWidth == 0 || frameHeight == 0) return
        val bitmap = thumbVisible.drawable ?: return
        val scale = minOf(
            frameWidth.toFloat() / bitmap.intrinsicWidth,
            frameHeight.toFloat() / bitmap.intrinsicHeight,
        )
        val imageWidth = bitmap.intrinsicWidth * scale
        val imageHeight = bitmap.intrinsicHeight * scale
        val left = (frameWidth - imageWidth) / 2f
        val top = (frameHeight - imageHeight) / 2f
        val cropWidth = compositor.fovRatio * imageWidth
        val cropHeight = compositor.fovRatio * imageHeight
        // Parallax shifts along the sensor's own x axis, which is the axis the two
        // lenses are separated in - see Compositor.parallax.
        val centreX = left + (0.5f + compositor.parallax) * imageWidth
        val centreY = top + imageHeight / 2f
        (cropRect.layoutParams as FrameLayout.LayoutParams).let {
            it.width = cropWidth.roundToInt().coerceAtLeast(1)
            it.height = cropHeight.roundToInt().coerceAtLeast(1)
            it.leftMargin = (centreX - cropWidth / 2f).roundToInt()
            it.topMargin = (centreY - cropHeight / 2f).roundToInt()
            cropRect.layoutParams = it
        }
    }

    /** Feeds the comparison screen, which needs the two layers apart, not blended. */
    private fun updateFovPreview(thermal: Bitmap) {
        previewCompositor.fovRatio = compositor.fovRatio
        previewCompositor.parallax = compositor.parallax
        val frame = camera.lastFrame
        val visible = frame?.let { previewCompositor.decodeVisible(it) }
        val placement = visible?.let {
            previewCompositor.visibleMatrix(it, thermal.width, thermal.height)
        }
        splitPreview.show(thermal, visible, placement, rotation, mirrored)
        thumbThermal.setImageBitmap(thermal)
        thumbVisible.setImageBitmap(visible)
        placeCropRect()
    }

    // --- diagnostics --------------------------------------------------------------

    private fun refreshStats() {
        val info = geometry
        val stats = lastStats
        statFrameValue.text = info?.let { "${it.width}×${it.height}" }
            ?: getString(R.string.stat_none)
        statLeptonValue.text = info?.versionLepton ?: getString(R.string.stat_none)
        statStreamValue.text = stats?.let { getString(R.string.stat_fps, dec(it.fps.toDouble())) }
            ?: getString(R.string.stat_none)
        statFfcValue.text = stats?.droppedFfc?.toString() ?: getString(R.string.stat_none)
        statResyncValue.text = stats?.resyncs?.toString() ?: getString(R.string.stat_none)
        // The scene's own extremes, which the scale ends no longer show once the
        // window is pinned to a fixed temperature range.
        statSceneValue.text = if (stats != null && stats.rawMax > 0) {
            getString(
                R.string.stat_scene_range,
                dec(planck.rawToCelsius(stats.rawMin)),
                dec(planck.rawToCelsius(stats.rawMax)),
            )
        } else {
            getString(R.string.stat_none)
        }
    }

    // --- device plumbing ----------------------------------------------------------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            intent.usbDeviceExtra()?.let { requestPermissionOrStart(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Only while the view is actually being looked at - the accelerometer has no
        // business running behind another app.
        orientationListener.enable()
    }

    override fun onPause() {
        super.onPause()
        orientationListener.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        camera.stop()
        unregisterReceiver(usbReceiver)
    }

    /**
     * Rounds the phone's angle to a right angle, holding on to the current one until
     * it is [ROTATION_HYSTERESIS] degrees away rather than switching at the halfway
     * mark.
     */
    private fun snapRotation(degrees: Int, current: Int): Int {
        val fromCurrent = abs(((degrees - current + 540) % 360) - 180)
        if (fromCurrent < ROTATION_HYSTERESIS) return current
        return ((degrees + 45) / 90 % 4) * 90
    }

    private fun findFlirDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == FlirProtocol.VENDOR_ID && it.productId == FlirProtocol.PRODUCT_ID
        }

    private fun requestPermissionOrStart(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            camera.start(device)
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    // --- FlirOneCamera.Listener (called on the camera thread) ---------------------

    override fun onLog(message: String) {
        Log.d(TAG, message)
        runOnUiThread {
            logLines.addLast(message)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
            logView.text = logLines.joinToString("\n")
            if (screen == Screen.LOG) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onGeometry(info: SledInfo) {
        renderer.resetBounds()
        runOnUiThread {
            geometry = info
            refreshTopBar()
            refreshStats()
        }
    }

    override fun onFrame(frame: ThermalFrame) {
        // Colourising is CPU work on a small array; do it here rather than hopping to
        // the UI thread with raw counts, and coalesce repaints so a slow frame does
        // not queue up behind a backlog of invalidates.
        val thermal = renderer.render(frame)
        // No point mixing a visible layer into a picture nobody is looking at: the
        // comparison screen draws the two layers itself, and the rest show neither.
        val showing = screen
        val bitmap = if (showing == Screen.LIVE) {
            compositor.compose(frame, thermal, blendMode) ?: thermal
        } else {
            thermal
        }
        if (calibrating) {
            calibrating = false
            runOnUiThread { refreshTopBar() }
        }
        val labels = spotLabels(frame)
        if (repaintPending.compareAndSet(false, true)) {
            runOnUiThread {
                repaintPending.set(false)
                thermalView.spots = labels
                thermalView.setImage(bitmap, rotation, mirrored)
                if (screen == Screen.FOV) updateFovPreview(thermal)
            }
        }
    }

    override fun onCalibrating(status: FrameStatus) {
        if (calibrating) return
        calibrating = true
        // The scene changes under a closed shutter, so the old contrast window is stale.
        renderer.resetBounds()
        runOnUiThread { refreshTopBar() }
    }

    override fun onStats(stats: FlirOneCamera.Stats) {
        runOnUiThread {
            lastStats = stats
            refreshTopBar()
            refreshLegend()
            if (screen == Screen.LOG) refreshStats()
            if (screen == Screen.SETTINGS) refreshSummaries()
        }
    }

    override fun onStopped(reason: String) {
        runOnUiThread {
            lastStats = null
            refreshTopBar()
            refreshStats()
        }
    }

    /**
     * Reads each spot off the frame. Done on the camera thread with the frame in
     * hand, so the numbers on screen belong to the picture under them rather than to
     * whatever frame happened to be current when the UI got round to drawing.
     */
    private fun spotLabels(frame: ThermalFrame): List<SpotLabel> {
        val calibration = planck
        return spotMeter.positions().mapIndexed { index, spot ->
            val celsius = calibration.rawToCelsius(frame.rawAt(spot.u, spot.v))
            SpotLabel(spot.u, spot.v, index + 1, "${dec(celsius)}°C")
        }
    }

    // --- snapshots ----------------------------------------------------------------

    /**
     * Captures the frame currently on screen and writes it to the gallery.
     *
     * The frame is grabbed synchronously so the file matches what the user was
     * looking at when they pressed the button, but the encode and the write go to a
     * background thread - PNG compression on the UI thread would stutter the live
     * view at the exact moment the user is holding the camera still.
     */
    private fun saveSnapshot() {
        val frame = camera.lastFrame
        if (frame == null) {
            toast(getString(R.string.nothing_to_save))
            return
        }
        flash()
        // Save what is on screen: if the visible layer is mixed in, the file gets it
        // too. A separate compositor because the live one is being written by the
        // camera thread; it only has to carry the same alignment.
        val thermal = renderer.snapshot(frame)
        snapshotCompositor.fovRatio = compositor.fovRatio
        snapshotCompositor.parallax = compositor.parallax
        val composed = snapshotCompositor.compose(frame, thermal, blendMode) ?: thermal
        val bitmap = ViewTransform.orient(composed, rotation, mirrored)
        thread(name = "flir-save") {
            val message = try {
                getString(R.string.saved_to, SnapshotSaver.save(this, bitmap).displayPath)
            } catch (e: Exception) {
                Log.e(TAG, "snapshot save failed", e)
                getString(R.string.save_failed, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                toast(message)
                onLog(message)
            }
        }
    }

    /** A saved frame is otherwise indistinguishable from a missed tap. */
    private fun flash() {
        flashView.animate().cancel()
        flashView.alpha = 0.5f
        flashView.animate().alpha(0f).setDuration(160).start()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // --- small helpers ------------------------------------------------------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun dec(value: Double, digits: Int = 1): String =
        if (value.isNaN()) getString(R.string.stat_none)
        else String.format(RU, "%.${digits}f", value)

    private fun signed(value: Double): String =
        (if (value < 0) "−" else "+") + String.format(RU, "%.3f", abs(value))

    private fun separator(): View = View(this).apply {
        setBackgroundColor(getColor(R.color.ff_border))
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
    }

    /** The theme's own touch feedback, for views built in code. */
    private fun rippleBackground(): android.graphics.drawable.Drawable? {
        val value = android.util.TypedValue()
        if (!theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) return null
        return getDrawable(value.resourceId)
    }

    private fun paletteStrip(palette: Palette): GradientDrawable {
        val colours = IntArray(16) { palette.lut[it * 255 / 15] }
        return GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colours).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(1).toFloat()
        }
    }

    /** A dull surface with a highlight on it, the highlight growing with the gloss. */
    private fun glossSwatch(gloss: Float): LayerDrawable {
        val base = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF6A5D4F.toInt(), 0xFF3A3129.toInt()),
        )
        val highlight = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.32f, 0.28f)
            gradientRadius = dp(16).toFloat()
            colors = intArrayOf(
                Color.argb((gloss * 255).roundToInt(), 255, 255, 255),
                Color.TRANSPARENT,
            )
        }
        return LayerDrawable(arrayOf(base, highlight))
    }

    private fun metalSwatchDrawable(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(
            0xFF4A5560.toInt(), 0xFFC9D1D8.toInt(), 0xFFF4F6F8.toInt(),
            0xFF8E9BA6.toInt(), 0xFF3F4A54.toInt(),
        ),
    )

    /** minSdk-26-safe replacement for the version-33-deprecated Intent.getParcelableExtra(String). */
    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}
