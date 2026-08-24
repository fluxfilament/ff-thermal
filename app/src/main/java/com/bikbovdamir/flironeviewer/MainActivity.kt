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
import android.hardware.usb.UsbEndpoint
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
 * Does NOT reassemble/decode frames yet - just proves the handshake works,
 * reads sledInformation off EP 0x81 (to learn the real thermalWidth /
 * thermalHeight / versionLepton for this unit), and confirms the magic
 * bytes + header fields on EP 0x85 match what was reverse-engineered.
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "FlirOneViewer"
        private const val ACTION_USB_PERMISSION = "com.bikbovdamir.flironeviewer.USB_PERMISSION"
        private const val VENDOR_ID = 0x09cb
        private const val PRODUCT_ID = 0x1996

        // Endpoint addresses per the protocol writeup.
        private const val EP_FRAME = 0x85      // bulk IN - thermal/JPEG/status frames
        private const val EP_STATUS = 0x83     // bulk IN - unused in step 1
        private const val EP_TELEMETRY = 0x81  // bulk IN - sledInformation etc, JSON
        private const val EP_COMMAND = 0x02    // bulk OUT - unused in step 1 (dead code path upstream)

        private val FRAME_MAGIC = byteArrayOf(0xEF.toByte(), 0xBE.toByte(), 0x00, 0x00)
    }

    private lateinit var usbManager: UsbManager
    private lateinit var logView: TextView
    private val logBuilder = StringBuilder()

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

        log("FLIR One Viewer — Step 1 hardware verification")
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
        // Do the actual USB I/O off the main thread.
        thread(name = "flir-verify") {
            try {
                verify(device)
            } catch (e: Exception) {
                log("ERROR during verification: ${e}")
                Log.e(TAG, "verification failed", e)
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

        // --- Handshake: 4x standard SET_INTERFACE control transfers ---
        // bmRequestType 0x01 = OUT | STANDARD | INTERFACE recipient. bRequest 0x0B = SET_INTERFACE.
        val requestType = UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD or 0x01 // RECIP_INTERFACE
        val setInterface: (value: Int, index: Int, data: ByteArray?) -> Unit = { value, index, data ->
            val len = data?.size ?: 0
            val r = connection.controlTransfer(requestType, 0x0B, value, index, data, len, 200)
            log("SET_INTERFACE value=$value index=$index len=$len -> result=$r")
        }
        setInterface(0, 2, null)               // stop iface 2 (FRAME)
        setInterface(0, 1, null)               // stop iface 1 (FILEIO)
        setInterface(1, 1, null)                // start iface 1 (FILEIO)
        setInterface(1, 2, ByteArray(2))       // start iface 2 (FRAME) — 2-byte dummy payload, per upstream

        // --- Find endpoints by address across all claimed interfaces ---
        val allEndpoints = interfaces.flatMap { iface -> (0 until iface.endpointCount).map { iface.getEndpoint(it) } }
        val epFrame = allEndpoints.firstOrNull { it.address == EP_FRAME }
        val epTelemetry = allEndpoints.firstOrNull { it.address == EP_TELEMETRY }

        if (epTelemetry == null) {
            log("WARNING: endpoint 0x${EP_TELEMETRY.toString(16)} (telemetry) not found on this device")
        } else {
            readTelemetryOnce(connection, epTelemetry)
        }

        if (epFrame == null) {
            log("WARNING: endpoint 0x${EP_FRAME.toString(16)} (frame) not found on this device")
        } else {
            readFrameHeaderOnce(connection, epFrame)
        }

        connection.close()
        log("Verification pass complete.")
    }

    /**
     * EP 0x81 telemetry framing (per NOTES.txt in hw-flir-one-gen3):
     * 4x u32 LE header (magic=0x1cc, always-1 word, payload size, CRC32),
     * then the JSON payload. Multiple messages can arrive concatenated in
     * a single bulk read, so we loop over the buffer splitting on this
     * 16-byte header rather than assuming one message per transfer.
     */
    private fun readTelemetryOnce(connection: UsbDeviceConnection, ep: UsbEndpoint) {
        val buf = ByteArray(16384)
        val n = connection.bulkTransfer(ep, buf, buf.size, 500)
        if (n <= 0) {
            log("EP 0x81 read returned $n (no telemetry yet - may need another attempt/frame cycle)")
            return
        }
        log("EP 0x81 read $n bytes, parsing message headers...")
        val bb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 0
        while (offset + 16 <= n) {
            bb.position(offset)
            val magic = bb.int
            val always1 = bb.int
            val payloadSize = bb.int
            val crc = bb.int
            if (magic != 0x1cc) {
                log("  stopped at offset $offset: magic=0x${magic.toString(16)} (expected 0x1cc) - " +
                        "likely end of valid messages in this read")
                break
            }
            val payloadStart = offset + 16
            val payloadEnd = (payloadStart + payloadSize).coerceAtMost(n)
            val payload = String(buf, payloadStart, payloadEnd - payloadStart, Charsets.UTF_8)
            log("  message: word2=$always1 payloadSize=$payloadSize crc=0x${crc.toString(16)}")
            log("  payload: $payload")
            if (payload.contains("thermalWidth") || payload.contains("thermalHeight") ||
                payload.contains("versionLepton")
            ) {
                log("  *** sledInformation found - this tells us the real sensor resolution ***")
            }
            offset = payloadEnd
        }
    }

    /** Confirms the EP 0x85 magic bytes + header fields match the reverse-engineered format. */
    private fun readFrameHeaderOnce(connection: UsbDeviceConnection, ep: UsbEndpoint) {
        val acc = ByteArray(1_048_576)
        var accLen = 0
        repeat(30) { attempt ->
            if (accLen >= 32) return@repeat // already have enough header bytes
            val chunk = ByteArray(16384)
            val n = connection.bulkTransfer(ep, chunk, chunk.size, 500)
            if (n <= 0) {
                log("EP 0x85 read attempt $attempt: n=$n")
                return@repeat
            }
            // Look for magic bytes as the start of a fresh frame.
            val magicIdx = indexOf(chunk, n, FRAME_MAGIC)
            if (magicIdx >= 0) {
                accLen = 0
                System.arraycopy(chunk, magicIdx, acc, 0, n - magicIdx)
                accLen = n - magicIdx
            } else if (accLen > 0) {
                System.arraycopy(chunk, 0, acc, accLen, n)
                accLen += n
            }
        }
        if (accLen < 24) {
            log("Never accumulated a full frame header (only got $accLen bytes starting from magic). " +
                    "Device may need the shutter/FFC cycle to finish, or streaming isn't fully started.")
            return
        }
        val bb = ByteBuffer.wrap(acc, 0, accLen).order(ByteOrder.LITTLE_ENDIAN)
        val magicOk = acc[0] == FRAME_MAGIC[0] && acc[1] == FRAME_MAGIC[1] &&
                acc[2] == FRAME_MAGIC[2] && acc[3] == FRAME_MAGIC[3]
        val frameSize = bb.getInt(8)
        val thermalSize = bb.getInt(12)
        val jpgSize = bb.getInt(16)
        val statusSize = bb.getInt(20)
        log("EP 0x85 frame header: magicOk=$magicOk frameSize=$frameSize thermalSize=$thermalSize " +
                "jpgSize=$jpgSize statusSize=$statusSize")
        log("  (expect thermalSize == thermalWidth*thermalHeight*2 from the telemetry above, " +
                "e.g. 160*120*2=38400 or 80*60*2=9600 — confirms which sensor generation this unit has)")
    }

    private fun indexOf(haystack: ByteArray, haystackLen: Int, needle: ByteArray): Int {
        outer@ for (i in 0..haystackLen - needle.size) {
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
