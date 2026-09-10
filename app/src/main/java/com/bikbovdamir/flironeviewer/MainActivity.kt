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
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Live view. Step 1 proved the protocol on real hardware; this screen is Step 2 -
 * the decode pipeline: de-interleave, drop shutter-calibration frames, colourise,
 * display. Temperatures are deliberately absent: converting counts to degrees needs
 * this unit's own Planck constants, which have not been read off it yet.
 */
class MainActivity : Activity(), FlirOneCamera.Listener {

    private companion object {
        const val TAG = "FlirOneViewer"
        const val ACTION_USB_PERMISSION = "com.bikbovdamir.flironeviewer.USB_PERMISSION"
        const val MAX_LOG_LINES = 400
        const val PREFS = "flir-one-viewer"
        const val PREF_EMISSIVITY = "emissivity"
    }

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private lateinit var usbManager: UsbManager
    private lateinit var imageView: ImageView
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var saveButton: Button
    private lateinit var paletteButton: Button
    private lateinit var logButton: Button
    private lateinit var emissivityLabel: TextView
    private lateinit var emissivitySeek: SeekBar

    private lateinit var camera: FlirOneCamera
    private val renderer = ThermalRenderer()

    /**
     * Coefficients for the unit this was developed on, with the user's emissivity
     * applied on top. Readings from another camera will be off - see [Planck] for how
     * to read its own out of a saved JPEG.
     */
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
        imageView = findViewById(R.id.thermalView)
        statusView = findViewById(R.id.statusView)
        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)
        saveButton = findViewById(R.id.saveButton)
        paletteButton = findViewById(R.id.paletteButton)
        logButton = findViewById(R.id.logButton)
        emissivityLabel = findViewById(R.id.emissivityLabel)
        emissivitySeek = findViewById(R.id.emissivitySeek)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        camera = FlirOneCamera(usbManager, this)

        saveButton.setOnClickListener { saveSnapshot() }
        paletteButton.setOnClickListener { cyclePalette() }
        logButton.setOnClickListener { toggleLog() }
        updatePaletteButton()
        setUpEmissivity()

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
        refreshStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            intent.usbDeviceExtra()?.let { requestPermissionOrStart(it) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        camera.stop()
        unregisterReceiver(usbReceiver)
    }

    // --- device plumbing ----------------------------------------------------------

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
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onGeometry(info: SledInfo) {
        renderer.resetBounds()
        runOnUiThread {
            geometry = info
            refreshStatus()
        }
    }

    override fun onFrame(frame: ThermalFrame) {
        // Colourising is CPU work on a small array; do it here rather than hopping to
        // the UI thread with raw counts, and coalesce repaints so a slow frame does
        // not queue up behind a backlog of invalidates.
        val bitmap = renderer.render(frame)
        if (calibrating) {
            calibrating = false
            runOnUiThread { refreshStatus() }
        }
        if (repaintPending.compareAndSet(false, true)) {
            runOnUiThread {
                repaintPending.set(false)
                showBitmap(bitmap)
            }
        }
    }

    override fun onCalibrating(status: FrameStatus) {
        if (calibrating) return
        calibrating = true
        // The scene changes under a closed shutter, so the old contrast window is stale.
        renderer.resetBounds()
        runOnUiThread { refreshStatus() }
    }

    override fun onStats(stats: FlirOneCamera.Stats) {
        runOnUiThread {
            lastStats = stats
            refreshStatus()
        }
    }

    override fun onStopped(reason: String) {
        runOnUiThread {
            lastStats = null
            refreshStatus()
        }
    }

    // --- UI -----------------------------------------------------------------------

    private fun showBitmap(bitmap: Bitmap) {
        val current = (imageView.drawable as? BitmapDrawable)?.bitmap
        if (current !== bitmap) {
            imageView.setImageBitmap(bitmap)
            // 80x60 blown up to a phone screen: interpolation just smears the pixels,
            // and a wrong de-interleave has to stay visible as hard banding.
            (imageView.drawable as? BitmapDrawable)?.isFilterBitmap = false
        } else {
            imageView.invalidate()
        }
    }

    /**
     * Wires up the emissivity slider and restores the last value used.
     *
     * It is remembered across launches deliberately: someone measuring the same
     * thing repeatedly should not silently fall back to 0.95 every time they reopen
     * the app, which would quietly change their readings between sessions.
     */
    private fun setUpEmissivity() {
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
        emissivityLabel.text = getString(R.string.emissivity_label, clamped)
        refreshStatus()
    }

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
        val bitmap = renderer.snapshot(frame)
        saveButton.isEnabled = false
        thread(name = "flir-save") {
            val message = try {
                getString(R.string.saved_to, SnapshotSaver.save(this, bitmap).displayPath)
            } catch (e: Exception) {
                Log.e(TAG, "snapshot save failed", e)
                getString(R.string.save_failed, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                saveButton.isEnabled = true
                toast(message)
                onLog(message)
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun cyclePalette() {
        val values = Palette.entries
        renderer.palette = values[(values.indexOf(renderer.palette) + 1) % values.size]
        updatePaletteButton()
    }

    private fun updatePaletteButton() {
        paletteButton.text = getString(R.string.palette_button, renderer.palette.label)
    }

    private fun toggleLog() {
        val show = logScroll.visibility != View.VISIBLE
        logScroll.visibility = if (show) View.VISIBLE else View.GONE
        logButton.setText(if (show) R.string.hide_log else R.string.show_log)
    }

    private fun refreshStatus() {
        val info = geometry
        val parts = mutableListOf<String>()
        parts += when {
            calibrating -> "Калибровка затвора…"
            !camera.isRunning() -> "Камера не подключена"
            info == null -> "Ожидание геометрии…"
            else -> "${info.width}×${info.height}"
        }
        info?.versionLepton?.let { parts += "Lepton $it" }
        lastStats?.let {
            if (it.rawMax > 0) {
                parts += "%.1f / %.1f / %.1f °C".format(
                    planck.rawToCelsius(it.rawMin),
                    planck.rawToCelsius(it.rawCenter),
                    planck.rawToCelsius(it.rawMax),
                )
            }
            parts += "%.1f fps".format(it.fps)
            if (it.droppedFfc > 0) parts += "FFC ${it.droppedFfc}"
            if (it.resyncs > 0) parts += "resync ${it.resyncs}"
        }
        statusView.text = parts.joinToString("  ·  ")
    }

    /** minSdk-26-safe replacement for the version-33-deprecated Intent.getParcelableExtra(String). */
    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}
