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
 *
 * The USB protocol implemented in this file (endpoint addresses, frame
 * header layout, control-transfer handshake) is derived from the
 * reverse-engineering work in:
 *   - fnoop/flirone-v4l2 (C) 2015-2016 Thomas <tomas123@EEVblog>, GPL-2.0-or-later
 *   - Miso98/hw-flir-one-gen3, based on flir-gtk (C) 2021 Nicole Faerber,
 *     2024 additions by Mitchell (Miso98), GPL-2.0-or-later
 * See NOTICE for full attribution.
 */
package com.bikbovdamir.flironeviewer

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
/**
 * Step 1 of the project plan: verify the reverse-engineered protocol
 * (from fnoop/flirone-v4l2 and Miso98/hw-flir-one-gen3, both
 * GPL-2.0-or-later) against our actual physical FLIR One for Android unit
 * before writing the real frame-decode pipeline (Step 2).
 *
 * Does NOT colourise or display thermal pixels yet. What it does prove:
 * the handshake, the sledInformation JSON off EP 0x81 (the authoritative
 * thermalWidth / thermalHeight / versionLepton for this unit), and whole
 * frames reassembled off EP 0x85 with header fields that add up.
 *
 * Shape of the driver matters: upstream does NOT fire four back-to-back
 * control transfers and then read. It runs a state machine across
 * iterations of a poll loop - interface 2 (FRAME) is started only after
 * the loop is already running, and EP 0x81/0x83 are polled on every
 * iteration. Doing it as a single burst gets you one lucky frame and then
 * a dead endpoint, so the loop shape is reproduced here deliberately.
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "FlirOneViewer"
        private const val ACTION_USB_PERMISSION = "com.bikbovdamir.flironeviewer.USB_PERMISSION"
        private const val VENDOR_ID = 0x09cb
        private const val PRODUCT_ID = 0x1996

        // Endpoint addresses per the protocol writeup.
        private const val EP_FRAME = 0x85      // bulk IN - thermal/JPEG/status frames
        private const val EP_STATUS = 0x83     // bulk IN - polled to keep the device alive
        private const val EP_TELEMETRY = 0x81  // bulk IN - sledInformation etc, JSON

        private val FRAME_MAGIC = byteArrayOf(0xEF.toByte(), 0xBE.toByte(), 0x00, 0x00)

        /** Config-message magic on EP 0x81 (0x5510 would be a file message, 0xbeef a frame). */
        private const val TELEMETRY_MAGIC = 0x1cc
        private val TELEMETRY_MAGIC_BYTES = byteArrayOf(0xCC.toByte(), 0x01, 0x00, 0x00)

        /**
         * Biggest config payload we will believe. The real ones are a few hundred
         * bytes; without a cap, a magic-shaped byte pair inside binary data yields a
         * bogus payload size, the parser waits for data that never comes, and the
         * buffer eventually resets mid-message and splices two fragments together.
         */
        private const val MAX_TELEMETRY_PAYLOAD = 8192

        /** 7 little-endian words ahead of the payload on EP 0x85. */
        private const val FRAME_HEADER_BYTES = 28

        /** How long one verification pass streams before printing its summary. */
        private const val RUN_MILLIS = 20_000L
    }

    private lateinit var usbManager: UsbManager
    private lateinit var logView: TextView
    private val logBuilder = StringBuilder()

    @Volatile private var running = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = intent.usbDeviceExtra()
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let { runVerification(it) }
                        } else {
                            log("USB permission denied by user for device: $device")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.usbDeviceExtra()
                    device?.let { requestPermissionOrRun(it) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logView = findViewById(R.id.logView)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }

        log("FLIR One Viewer - Step 1 hardware verification")
        log("Looking for VID=0x${VENDOR_ID.toString(16)} PID=0x${PRODUCT_ID.toString(16)}...")

        findFlirDevice()?.let { requestPermissionOrRun(it) }
            ?: log("Device not currently attached. Plug in the FLIR One and re-open the app, " +
                    "or it should auto-launch via the USB_DEVICE_ATTACHED intent filter.")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device: UsbDevice? = intent.usbDeviceExtra()
            device?.let { requestPermissionOrRun(it) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        unregisterReceiver(usbReceiver)
    }

    private fun findFlirDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == VENDOR_ID && it.productId == PRODUCT_ID
        }

    private fun requestPermissionOrRun(device: UsbDevice) {
        log("Found candidate device: vendorId=0x${device.vendorId.toString(16)} " +
                "productId=0x${device.productId.toString(16)} name=${device.deviceName} " +
                "interfaceCount=${device.interfaceCount}")

        if (usbManager.hasPermission(device)) {
            runVerification(device)
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION), flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun runVerification(device: UsbDevice) {
        if (running) {
            log("Verification already running - ignoring duplicate start.")
            return
        }
        // Do the actual USB I/O off the main thread.
        thread(name = "flir-verify") {
            running = true
            try {
                verify(device)
            } catch (e: Exception) {
                log("ERROR during verification: $e")
                Log.e(TAG, "verification failed", e)
            } finally {
                running = false
            }
        }
    }

    private fun verify(device: UsbDevice) {
        val connection: UsbDeviceConnection = usbManager.openDevice(device)
            ?: run { log("openDevice() returned null - permission problem?"); return }

        // Claim every interface we can see; log what's there either way.
        val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
        for (iface in interfaces) {
            val claimed = connection.claimInterface(iface, true)
            log("Interface ${iface.id}: endpointCount=${iface.endpointCount} claimed=$claimed")
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                log("  endpoint address=0x${ep.address.toString(16)} " +
                        "direction=${if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"} " +
                        "type=${ep.type}")
            }
        }

        try {
            pollLoop(connection, interfaces)
        } finally {
            // Android exposes no libusb_reset_device equivalent, so releasing the
            // interfaces explicitly is the most we can do to leave the camera in a
            // re-openable state for the next run.
            for (iface in interfaces) connection.releaseInterface(iface)
            connection.close()
            log("Interfaces released, connection closed.")
        }
    }

    private fun pollLoop(connection: UsbDeviceConnection, interfaces: List<UsbInterface>) {
        // bmRequestType 0x01 = OUT | STANDARD | INTERFACE recipient. bRequest 0x0B = SET_INTERFACE.
        val requestType = UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD or 0x01
        fun setInterface(value: Int, index: Int, data: ByteArray?) {
            val len = data?.size ?: 0
            val r = connection.controlTransfer(requestType, 0x0B, value, index, data, len, 200)
            log("SET_INTERFACE value=$value index=$index len=$len -> result=$r")
        }

        val allEndpoints = interfaces.flatMap { iface ->
            (0 until iface.endpointCount).map { iface.getEndpoint(it) }
        }
        val epFrame = allEndpoints.firstOrNull { it.address == EP_FRAME }
        val epStatus = allEndpoints.firstOrNull { it.address == EP_STATUS }
        val epTelemetry = allEndpoints.firstOrNull { it.address == EP_TELEMETRY }
        if (epFrame == null || epTelemetry == null) {
            log("FATAL: required endpoints missing (frame=$epFrame telemetry=$epTelemetry)")
            return
        }

        val frames = FrameAssembler()
        val telemetry = TelemetryAssembler()
        val frameBuf = ByteArray(65536)
        val telemetryBuf = ByteArray(16384)
        val statusBuf = ByteArray(16384)

        // --- state 1: stop FRAME, stop FILEIO, start FILEIO ---
        log("state 1: stop iface 2 (FRAME), stop iface 1 (FILEIO), start iface 1 (FILEIO)")
        setInterface(0, 2, null)
        setInterface(0, 1, null)
        setInterface(1, 1, null)

        // sledInformation is emitted once, as FILEIO comes up, and it is the only place
        // the sensor geometry is stated outright. Drain EP 0x81 for it before asking for
        // frames - otherwise it slips past while the frame endpoint is starting.
        log("draining EP 0x81 for sledInformation before starting the frame stream")
        var drains = 0
        while (drains < 60 && telemetry.sledInformation == null && running) {
            drains++
            val t = connection.bulkTransfer(epTelemetry, telemetryBuf, telemetryBuf.size, 20)
            if (t > 0) telemetry.feed(telemetryBuf, t)
        }
        log("drain finished after $drains reads, sledInformation " +
                if (telemetry.sledInformation != null) "FOUND" else "not seen")

        // Upstream jumps straight from state 1 to state 3: state 2 only fetches
        // CameraFiles.zip, whose contents it never actually uses.
        var state = 3

        val started = System.currentTimeMillis()
        var iterations = 0
        var emptyFrameReads = 0

        while (System.currentTimeMillis() - started < RUN_MILLIS && running) {
            iterations++
            when (state) {
                3 -> {
                    log("state 3: start iface 2 (FRAME) - asking for the video stream on EP 0x85")
                    setInterface(1, 2, ByteArray(2))
                    state = 4
                }
                4 -> {
                    // Upstream's comment is emphatic: don't change this 100 ms timeout.
                    val n = connection.bulkTransfer(epFrame, frameBuf, frameBuf.size, 100)
                    if (n > 0) frames.feed(frameBuf, n) else emptyFrameReads++
                }
            }
            // EP 0x81 and 0x83 get polled every iteration regardless of state, exactly
            // as upstream does - this is what keeps the camera streaming.
            val t = connection.bulkTransfer(epTelemetry, telemetryBuf, telemetryBuf.size, 10)
            if (t > 0) telemetry.feed(telemetryBuf, t)
            if (epStatus != null) connection.bulkTransfer(epStatus, statusBuf, statusBuf.size, 10)
        }

        val elapsed = (System.currentTimeMillis() - started) / 1000.0
        log("--- summary after ${"%.1f".format(elapsed)}s ---")
        log("loop iterations=$iterations, empty EP 0x85 reads=$emptyFrameReads")
        log("complete frames reassembled=${frames.completed}, bytes seen=${frames.bytesSeen}, " +
                "resyncs=${frames.resyncs}")
        log("telemetry messages parsed=${telemetry.messages}")
        if (frames.completed > 0) {
            log("frame rate ~${"%.1f".format(frames.completed / elapsed)} fps")
        }
        if (telemetry.sledInformation == null) {
            log("WARNING: never saw a sledInformation message - resolution not confirmed from telemetry")
        }
    }

    /**
     * Reassembles the EP 0x81 byte stream into whole config messages. Header is
     * 4 little-endian words: magic (0x1cc), an always-1 word, payload size, CRC-32.
     * Payload and header arrive in separate bulk transfers, and a read can land
     * mid-message, so this resyncs on the magic rather than trusting offset 0.
     */
    private inner class TelemetryAssembler {
        private val buf = ByteArray(256 * 1024)
        private var len = 0
        private val seenTypes = HashSet<String>()
        private val rawText = StringBuilder()

        var messages = 0
            private set
        var sledInformation: String? = null
            private set

        fun feed(chunk: ByteArray, n: Int) {
            scanRawForResolution(chunk, n)
            if (len + n > buf.size) len = 0 // pathological; start over rather than grow
            System.arraycopy(chunk, 0, buf, len, n)
            len += n
            drain()
        }

        /**
         * sledInformation is sent once as the stream comes up, so the poll loop can
         * start mid-message and never see a parseable header for it. The resolution is
         * the whole point of this step, so also recover it straight out of the bytes.
         */
        private fun scanRawForResolution(chunk: ByteArray, n: Int) {
            if (sledInformation != null) return
            for (i in 0 until n) {
                val c = chunk[i].toInt() and 0xff
                rawText.append(if (c in 32..126) c.toChar() else ' ')
            }
            if (rawText.length > 32 * 1024) rawText.delete(0, rawText.length - 8 * 1024)
            val text = rawText.toString()
            if (!text.contains("thermalWidth") && !text.contains("thermalHeight")) return
            log("EP 0x81 raw-text scan recovered the sled fragment: " +
                    text.substring(maxOf(0, text.indexOf("thermal") - 200)))
            log("*** RESOLUTION CONFIRMED BY DEVICE (raw scan): " +
                    "thermalWidth=${field(text, "thermalWidth")} " +
                    "thermalHeight=${field(text, "thermalHeight")} " +
                    "versionLepton=${field(text, "versionLepton")} " +
                    "bigEndianThermal=${field(text, "bigEndianThermal")} ***")
            sledInformation = text
        }

        private fun drain() {
            while (len >= 16) {
                val bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
                if (bb.getInt(0) != TELEMETRY_MAGIC) {
                    if (!resync()) return
                    continue
                }
                val always1 = bb.getInt(4)
                val payloadSize = bb.getInt(8)
                // The magic is only two significant bytes, so it turns up inside binary
                // data by chance. Demand the always-1 word and a sane size too.
                if (always1 != 1 || payloadSize <= 0 || payloadSize > MAX_TELEMETRY_PAYLOAD) {
                    if (!resync()) return
                    continue
                }
                if (len < 16 + payloadSize) return // wait for the rest
                // Every payload seen on this endpoint is a JSON object.
                if (buf[16] != '{'.code.toByte()) {
                    if (!resync()) return
                    continue
                }
                val payload = clean(String(buf, 16, payloadSize, Charsets.UTF_8))
                messages++
                onMessage(payload)
                consume(16 + payloadSize)
            }
        }

        /** Drops bytes up to the next plausible magic. False when nothing is left to find. */
        private fun resync(): Boolean {
            val at = indexOf(buf, len, TELEMETRY_MAGIC_BYTES, from = 1)
            if (at < 0) {
                // Keep a short tail: a magic could straddle two reads.
                val keep = minOf(len, TELEMETRY_MAGIC_BYTES.size - 1)
                System.arraycopy(buf, len - keep, buf, 0, keep)
                len = keep
                return false
            }
            consume(at)
            return true
        }

        private fun consume(count: Int) {
            System.arraycopy(buf, count, buf, 0, len - count)
            len -= count
        }

        private fun onMessage(payload: String) {
            val type = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"").find(payload)?.groupValues?.get(1)
                ?: "unknown"
            // Battery updates repeat every second; log each type once, in full.
            if (seenTypes.add(type)) {
                log("EP 0x81 message type=$type: $payload")
            }
            if ((type == "sledInformation" || payload.contains("thermalWidth")) &&
                sledInformation == null
            ) {
                sledInformation = payload
                log("*** RESOLUTION CONFIRMED BY DEVICE: " +
                        "thermalWidth=${field(payload, "thermalWidth")} " +
                        "thermalHeight=${field(payload, "thermalHeight")} " +
                        "versionLepton=${field(payload, "versionLepton")} " +
                        "bigEndianThermal=${field(payload, "bigEndianThermal")} ***")
            }
        }

        private fun field(json: String, key: String): String =
            Regex("\"$key\"\\s*:\\s*\"?([^\",}]+)\"?").find(json)?.groupValues?.get(1) ?: "?"
    }

    /** Reassembles the EP 0x85 byte stream into whole frames, resyncing on the magic. */
    private inner class FrameAssembler {
        private val buf = ByteArray(2 * 1024 * 1024)
        private var len = 0
        private var geometryLogged = false

        var completed = 0
            private set
        var bytesSeen = 0L
            private set
        var resyncs = 0
            private set

        fun feed(chunk: ByteArray, n: Int) {
            bytesSeen += n
            if (len + n > buf.size) {
                log("EP 0x85 buffer full at $len bytes without a complete frame - resyncing")
                len = 0
                resyncs++
            }
            System.arraycopy(chunk, 0, buf, len, n)
            len += n
            drain()
        }

        private fun drain() {
            while (true) {
                val start = indexOf(buf, len, FRAME_MAGIC)
                if (start < 0) {
                    val keep = minOf(len, FRAME_MAGIC.size - 1)
                    System.arraycopy(buf, len - keep, buf, 0, keep)
                    len = keep
                    return
                }
                if (start > 0) consume(start)
                if (len < FRAME_HEADER_BYTES) return

                val bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
                val frameSize = bb.getInt(8)
                val thermalSize = bb.getInt(12)
                val jpgSize = bb.getInt(16)
                val statusSize = bb.getInt(20)

                val sane = frameSize > 0 && frameSize <= buf.size - FRAME_HEADER_BYTES &&
                        thermalSize >= 0 && jpgSize >= 0 && statusSize >= 0 &&
                        thermalSize + jpgSize + statusSize == frameSize
                if (!sane) {
                    if (completed == 0) {
                        log("EP 0x85: implausible header (frameSize=$frameSize thermal=$thermalSize " +
                                "jpg=$jpgSize status=$statusSize) - skipping this magic")
                    }
                    resyncs++
                    consume(FRAME_MAGIC.size)
                    continue
                }

                val total = FRAME_HEADER_BYTES + frameSize
                if (len < total) return // rest of the frame hasn't arrived yet

                completed++
                if (completed <= 2) {
                    val statusStart = FRAME_HEADER_BYTES + thermalSize + jpgSize
                    val status = clean(String(buf, statusStart, statusSize, Charsets.UTF_8))
                    log("complete frame #$completed: frameSize=$frameSize thermal=$thermalSize " +
                            "jpg=$jpgSize status=$statusSize")
                    log("  status string: $status")
                }
                if (!geometryLogged) {
                    geometryLogged = true
                    logGeometry(thermalSize)
                }
                consume(total)
            }
        }

        private fun logGeometry(thermalSize: Int) {
            // Thermal rows are padded, so a row costs more than width*2 bytes: upstream
            // reads pixel (x,y) at 2*(y*stride + x) + 32, with the second half of each row
            // shifted 4 bytes further on. The two upstreams disagree on the stride
            // (flirone-v4l2 uses width+4 words, hw-flir-one-gen3 width+2 and its comment
            // literally says "assuming"), so report every candidate that divides cleanly
            // and let the sledInformation JSON be the authority.
            val strideModels = listOf<Pair<String, (Int) -> Int>>(
                "width+4 words, flirone-v4l2" to { w -> (w + 4) * 2 },
                "width+2 words, hw-flir-one-gen3" to { w -> (w + 2) * 2 },
            )
            log("thermal payload is $thermalSize bytes; candidate geometries:")
            var any = false
            for (width in intArrayOf(80, 160)) {
                for ((label, rowBytesOf) in strideModels) {
                    val rowBytes = rowBytesOf(width)
                    if (thermalSize % rowBytes == 0) {
                        any = true
                        log("  width=$width, $rowBytes bytes/row [$label] -> " +
                                "${thermalSize / rowBytes} rows (image rows plus any Lepton telemetry rows)")
                    }
                }
            }
            if (!any) log("  none of the known stride models divide $thermalSize evenly")
        }

        private fun consume(count: Int) {
            System.arraycopy(buf, count, buf, 0, len - count)
            len -= count
        }
    }

    /** Drops the NUL padding and other control bytes upstream leaves on its strings. */
    private fun clean(s: String): String = s.filter { it.code >= 32 }.trim()

    private fun indexOf(haystack: ByteArray, haystackLen: Int, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..haystackLen - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /** minSdk-26-safe replacement for the version-33-deprecated Intent.getParcelableExtra(String). */
    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        runOnUiThread {
            logBuilder.append(msg).append('\n')
            logView.text = logBuilder.toString()
        }
    }
}
