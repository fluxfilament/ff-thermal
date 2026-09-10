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
 * Derived from the reverse-engineering work in fnoop/flirone-v4l2 and
 * Miso98/hw-flir-one-gen3, both GPL-2.0-or-later. See NOTICE.
 */
package com.bikbovdamir.flironeviewer

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlin.concurrent.thread

/**
 * Drives the camera: claims the interfaces, runs the poll loop, hands decoded
 * frames to a [Listener]. Everything USB happens on one background thread; the
 * listener is called from that thread, so callers marshal to the UI themselves.
 *
 * The loop shape is not incidental. Upstream is a state machine spread across
 * iterations, not four back-to-back control transfers: interface 2 (FRAME) starts
 * only once the loop is already running, and EP 0x81/0x83 get polled on every
 * iteration. Firing the transfers as one burst yields a single lucky frame and
 * then a dead endpoint, so the staging is reproduced deliberately.
 */
class FlirOneCamera(
    private val usbManager: UsbManager,
    private val listener: Listener,
) {

    interface Listener {
        fun onLog(message: String)
        fun onGeometry(info: SledInfo)
        fun onFrame(frame: ThermalFrame)

        /** Shutter is closed for flat-field correction; the scene is unavailable. */
        fun onCalibrating(status: FrameStatus)
        fun onStats(stats: Stats)
        fun onStopped(reason: String)
    }

    data class Stats(
        val frames: Int,
        val droppedFfc: Int,
        val resyncs: Int,
        val fps: Double,
    )

    private companion object {
        /** Upstream's comment on this one is emphatic: don't change it. */
        const val FRAME_READ_TIMEOUT_MS = 100
        const val SIDE_READ_TIMEOUT_MS = 10

        /** No whole frame for this long means the stream wedged; restart the interfaces. */
        const val STALL_RESTART_MS = 3_000L
        const val STATS_INTERVAL_MS = 1_000L

        /**
         * Geometry fallback if sledInformation is missed entirely: thermal payload
         * size to sensor size. Only the two shipped sensors exist, and each has a
         * distinct packet count, so this is unambiguous where it applies.
         */
        val GEOMETRY_BY_THERMAL_SIZE = mapOf(
            FlirProtocol.VOSPI_PACKET_BYTES * (60 + 3) to (80 to 60),      // Lepton 2
            FlirProtocol.VOSPI_PACKET_BYTES * (2 * 120 + 3) to (160 to 120), // Lepton 3
        )
    }

    @Volatile
    private var running = false
    private var worker: Thread? = null

    /** Switchable from the UI so the two candidate pixel layouts can be compared live. */
    @Volatile
    var layout: FlirProtocol.Layout = FlirProtocol.Layout.VOSPI

    /** Latest raw counts, kept so a snapshot can be taken off the UI thread later. */
    @Volatile
    var lastFrame: ThermalFrame? = null
        private set

    fun isRunning(): Boolean = running

    fun start(device: UsbDevice) {
        if (running) {
            listener.onLog("Camera already streaming - ignoring duplicate start.")
            return
        }
        running = true
        worker = thread(name = "flir-camera") {
            var reason = "stopped"
            try {
                run(device)
            } catch (e: Exception) {
                reason = "error: $e"
                listener.onLog("ERROR in camera loop: $e")
            } finally {
                running = false
                listener.onStopped(reason)
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(1_000)
        worker = null
    }

    private fun run(device: UsbDevice) {
        val connection: UsbDeviceConnection = usbManager.openDevice(device)
            ?: run {
                listener.onLog("openDevice() returned null - permission problem?")
                return
            }

        val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
        for (iface in interfaces) {
            val claimed = connection.claimInterface(iface, true)
            if (!claimed) listener.onLog("WARNING: could not claim interface ${iface.id}")
        }

        try {
            pollLoop(connection, interfaces)
        } finally {
            // Android exposes no libusb_reset_device equivalent, so releasing the
            // interfaces explicitly is the most we can do to leave the camera in a
            // re-openable state for the next run.
            for (iface in interfaces) connection.releaseInterface(iface)
            connection.close()
            listener.onLog("Interfaces released, connection closed.")
        }
    }

    private fun pollLoop(connection: UsbDeviceConnection, interfaces: List<UsbInterface>) {
        val endpoints = interfaces.flatMap { iface ->
            (0 until iface.endpointCount).map { iface.getEndpoint(it) }
        }
        val epFrame = endpoints.firstOrNull { it.address == FlirProtocol.EP_FRAME }
        val epStatus = endpoints.firstOrNull { it.address == FlirProtocol.EP_STATUS }
        val epTelemetry = endpoints.firstOrNull { it.address == FlirProtocol.EP_TELEMETRY }
        if (epFrame == null || epTelemetry == null) {
            listener.onLog("FATAL: required endpoints missing (frame=$epFrame telemetry=$epTelemetry)")
            return
        }

        var geometry: SledInfo? = null
        var pixels = IntArray(0)
        var frames = 0
        var droppedFfc = 0
        var decodeFailures = 0

        val telemetry = TelemetryAssembler(
            log = listener::onLog,
            onSled = { info, _ ->
                geometry = info
                pixels = IntArray(info.width * info.height)
                listener.onGeometry(info)
            },
        )

        val assembler = FrameAssembler(log = listener::onLog) { buf, thermalSize, jpgSize, statusSize ->
            val statusStart = FlirProtocol.FRAME_HEADER_BYTES + thermalSize + jpgSize
            val status = FrameStatus(
                FlirProtocol.clean(String(buf, statusStart, statusSize, Charsets.UTF_8))
            )

            var info = geometry
            if (info == null) {
                info = guessGeometry(thermalSize)
                if (info != null) {
                    listener.onLog(
                        "sledInformation not seen; inferring ${info.width}x${info.height} from " +
                                "thermalSize=$thermalSize (${thermalSize / FlirProtocol.VOSPI_PACKET_BYTES} " +
                                "VoSPI packets). Assuming little-endian."
                    )
                    geometry = info
                    pixels = IntArray(info.width * info.height)
                    listener.onGeometry(info)
                }
            }

            if (info == null) {
                if (decodeFailures++ == 0) {
                    listener.onLog("No geometry yet and thermalSize=$thermalSize is unrecognised - skipping frames.")
                }
            } else if (status.isFfc) {
                // Shutter closed for flat-field correction: the sensor is imaging its
                // own shutter, so this frame is a calibration target, not a scene.
                droppedFfc++
                listener.onCalibrating(status)
            } else {
                val ok = FlirProtocol.deinterleave(
                    frame = buf,
                    thermalSize = thermalSize,
                    width = info.width,
                    height = info.height,
                    littleEndian = !info.bigEndianThermal,
                    into = pixels,
                    layout = layout,
                )
                if (!ok) {
                    if (decodeFailures++ == 0) {
                        listener.onLog(
                            "Thermal payload is $thermalSize bytes, too short for " +
                                    "${info.width}x${info.height} (needs " +
                                    "${FlirProtocol.imageBytes(info.width, info.height)}) - not rendering."
                        )
                    }
                } else {
                    if (frames == 0) {
                        val rows = thermalSize / FlirProtocol.VOSPI_PACKET_BYTES
                        listener.onLog(
                            "thermal payload $thermalSize B = $rows VoSPI packets = " +
                                    "${info.height} image rows + " +
                                    "${FlirProtocol.telemetryRows(thermalSize, info.width, info.height)} " +
                                    "Lepton telemetry rows"
                        )
                        listener.onLog("first frame status: ${status.raw}")
                    }
                    frames++
                    var min = Int.MAX_VALUE
                    var max = Int.MIN_VALUE
                    for (v in pixels) {
                        if (v < min) min = v
                        if (v > max) max = v
                    }
                    val frame = ThermalFrame(
                        width = info.width,
                        height = info.height,
                        raw = pixels.copyOf(),
                        min = min,
                        max = max,
                        status = status,
                    )
                    lastFrame = frame
                    listener.onFrame(frame)
                }
            }
        }

        val frameBuf = ByteArray(65536)
        val telemetryBuf = ByteArray(16384)
        val statusBuf = ByteArray(16384)

        startInterfaces(connection)
        drainForGeometry(connection, epTelemetry, telemetry, telemetryBuf)
        requestVideoStream(connection)

        var lastFrameAt = System.currentTimeMillis()
        var lastStatsAt = lastFrameAt
        var framesAtLastStats = 0
        var completedAtLastCheck = 0

        while (running) {
            val n = connection.bulkTransfer(epFrame, frameBuf, frameBuf.size, FRAME_READ_TIMEOUT_MS)
            if (n > 0) assembler.feed(frameBuf, n)

            // EP 0x81 and 0x83 get polled every iteration regardless - this is what
            // keeps the camera streaming.
            val t = connection.bulkTransfer(epTelemetry, telemetryBuf, telemetryBuf.size, SIDE_READ_TIMEOUT_MS)
            if (t > 0) telemetry.feed(telemetryBuf, t)
            if (epStatus != null) {
                connection.bulkTransfer(epStatus, statusBuf, statusBuf.size, SIDE_READ_TIMEOUT_MS)
            }

            val now = System.currentTimeMillis()
            // A successful read is not progress on its own - a wedged endpoint can keep
            // returning bytes that never complete a frame. Only whole frames count.
            if (assembler.completed != completedAtLastCheck) {
                completedAtLastCheck = assembler.completed
                lastFrameAt = now
            }

            if (now - lastFrameAt > STALL_RESTART_MS) {
                listener.onLog("No frames for ${STALL_RESTART_MS / 1000}s - restarting the interfaces.")
                startInterfaces(connection)
                requestVideoStream(connection)
                lastFrameAt = now
            }

            if (now - lastStatsAt >= STATS_INTERVAL_MS) {
                val elapsed = (now - lastStatsAt) / 1000.0
                listener.onStats(
                    Stats(
                        frames = frames,
                        droppedFfc = droppedFfc,
                        resyncs = assembler.resyncs,
                        fps = (frames - framesAtLastStats) / elapsed,
                    )
                )
                framesAtLastStats = frames
                lastStatsAt = now
            }
        }
    }

    /** State 1: stop FRAME, stop FILEIO, start FILEIO. */
    private fun startInterfaces(connection: UsbDeviceConnection) {
        setInterface(connection, value = 0, index = 2, data = null)
        setInterface(connection, value = 0, index = 1, data = null)
        setInterface(connection, value = 1, index = 1, data = null)
    }

    /** State 3: start FRAME, i.e. ask for the video stream on EP 0x85. */
    private fun requestVideoStream(connection: UsbDeviceConnection) {
        setInterface(connection, value = 1, index = 2, data = ByteArray(2))
    }

    private fun setInterface(connection: UsbDeviceConnection, value: Int, index: Int, data: ByteArray?) {
        // bmRequestType 0x01 = OUT | STANDARD | INTERFACE recipient. bRequest 0x0B = SET_INTERFACE.
        val requestType = UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD or 0x01
        val len = data?.size ?: 0
        val r = connection.controlTransfer(requestType, 0x0B, value, index, data, len, 200)
        if (r < 0) listener.onLog("SET_INTERFACE value=$value index=$index failed -> $r")
    }

    /**
     * sledInformation is emitted once, as FILEIO comes up, and it is the only place the
     * sensor geometry is stated outright. Drain EP 0x81 for it before asking for frames -
     * otherwise it slips past while the frame endpoint is starting.
     */
    private fun drainForGeometry(
        connection: UsbDeviceConnection,
        epTelemetry: UsbEndpoint,
        telemetry: TelemetryAssembler,
        buf: ByteArray,
    ) {
        var reads = 0
        while (reads < 60 && telemetry.sledInfo == null && running) {
            reads++
            val n = connection.bulkTransfer(epTelemetry, buf, buf.size, 20)
            if (n > 0) telemetry.feed(buf, n)
        }
        if (telemetry.sledInfo == null) {
            listener.onLog("sledInformation not seen in $reads reads - will infer geometry from the frame.")
        }
    }

    private fun guessGeometry(thermalSize: Int): SledInfo? {
        val (w, h) = GEOMETRY_BY_THERMAL_SIZE[thermalSize] ?: return null
        return SledInfo(
            width = w,
            height = h,
            versionLepton = null,
            serialNumberLepton = null,
            bigEndianThermal = false,
        )
    }
}
