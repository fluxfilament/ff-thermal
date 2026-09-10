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
 * The USB protocol described here (endpoint addresses, frame header
 * layout, control-transfer handshake, thermal pixel addressing) is
 * derived from the reverse-engineering work in:
 *   - fnoop/flirone-v4l2 (C) 2015-2016 Thomas <tomas123@EEVblog>, GPL-2.0-or-later
 *   - Miso98/hw-flir-one-gen3, based on flir-gtk (C) 2021 Nicole Faerber,
 *     2024 additions by Mitchell (Miso98), GPL-2.0-or-later
 * See NOTICE for full attribution.
 */
package com.bikbovdamir.flironeviewer

/** Wire-format constants and pure decoding helpers. No Android dependencies. */
object FlirProtocol {

    const val VENDOR_ID = 0x09cb
    const val PRODUCT_ID = 0x1996

    /** Bulk IN: thermal + JPEG + status frames. */
    const val EP_FRAME = 0x85

    /** Bulk IN: polled every loop iteration to keep the device streaming. */
    const val EP_STATUS = 0x83

    /** Bulk IN: JSON config messages (sledInformation, battery updates). */
    const val EP_TELEMETRY = 0x81

    val FRAME_MAGIC = byteArrayOf(0xEF.toByte(), 0xBE.toByte(), 0x00, 0x00)

    /** 7 little-endian words ahead of the frame payload on EP 0x85. */
    const val FRAME_HEADER_BYTES = 28

    /** Config-message magic on EP 0x81 (0x5510 would be a file message, 0xbeef a frame). */
    const val TELEMETRY_MAGIC = 0x1cc
    val TELEMETRY_MAGIC_BYTES = byteArrayOf(0xCC.toByte(), 0x01, 0x00, 0x00)

    /**
     * Biggest config payload we will believe. The real ones are a few hundred bytes;
     * without a cap, a magic-shaped byte pair inside binary data yields a bogus payload
     * size, the parser waits for data that never comes, and the buffer eventually resets
     * mid-message and splices two fragments together.
     */
    const val MAX_TELEMETRY_PAYLOAD = 8192

    // --- Thermal payload geometry -------------------------------------------------
    //
    // The thermal payload is not a padded bitmap: it is a run of raw Lepton VoSPI
    // packets passed straight through. One packet is 2 bytes of ID + 2 bytes of CRC
    // + 160 bytes of payload = 164 bytes carrying exactly 80 pixels. That single fact
    // explains every "padding" oddity in the upstream code:
    //
    //   - 80-wide sensor (Lepton 2, our unit): one packet per image row, 164 B/row.
    //     thermalSize 10332 = 63 packets = 60 image rows + 3 Lepton telemetry rows.
    //   - 160-wide sensor (Lepton 3): two packets per image row, 328 B/row - which is
    //     upstream's hardcoded `2*(y*164 + x)` stride, and its extra `+4` for x >= 80
    //     is simply the second packet's own 4-byte header.
    //
    // So the addressing below is not a stride formula with a guessed pad; the pad is
    // the packet header, and it generalises across Lepton generations by construction.

    const val VOSPI_PACKET_BYTES = 164
    const val VOSPI_PACKET_HEADER_BYTES = 4
    const val VOSPI_PIXELS_PER_PACKET = 80

    /** Lepton telemetry rows appended after the image rows, if the payload has room. */
    fun telemetryRows(thermalSize: Int, width: Int, height: Int): Int =
        thermalSize / VOSPI_PACKET_BYTES - packetsPerRow(width) * height

    fun packetsPerRow(width: Int): Int =
        (width + VOSPI_PIXELS_PER_PACKET - 1) / VOSPI_PIXELS_PER_PACKET

    /** Bytes of thermal payload needed for [height] full image rows at [width]. */
    fun imageBytes(width: Int, height: Int): Int =
        packetsPerRow(width) * height * VOSPI_PACKET_BYTES

    /**
     * De-interleaves the VoSPI packet stream into a plain row-major array of raw
     * 16-bit sensor counts. [frame] is a whole reassembled EP 0x85 frame, magic
     * bytes included, so the thermal payload starts at [FRAME_HEADER_BYTES].
     *
     * Returns false when the payload is too short for the claimed geometry - that
     * means the geometry (or this packet model) is wrong, and rendering garbage
     * would be worse than showing nothing.
     *
     * Note what this deliberately does NOT do on an 80-wide sensor: hw-flir-one-gen3
     * keeps flirone-v4l2's extra 4-byte skip at the midpoint of the row. On a 160-wide
     * sensor that skip is real - it is the second packet's own header - but at 80 wide
     * one packet already holds the entire row, so the skip pushes the last two pixels
     * past the end of the packet and into the *next* packet's ID and CRC. Measured on
     * this unit: with the skip, the per-column standard deviation of the last two
     * columns is 17 and 81 against a scene-noise baseline of 0.6 - the ID being a
     * sequence number and the CRC being effectively random, exactly as the packet model
     * predicts. Without it, those columns sit at the baseline. So the skip is an
     * upstream bug at this width, not a variant worth supporting.
     */
    fun deinterleave(
        frame: ByteArray,
        thermalSize: Int,
        width: Int,
        height: Int,
        littleEndian: Boolean,
        into: IntArray,
    ): Boolean {
        if (into.size < width * height) return false
        if (thermalSize < imageBytes(width, height)) return false

        val packetsPerRow = packetsPerRow(width)
        var out = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val packet = y * packetsPerRow + x / VOSPI_PIXELS_PER_PACKET
                val at = FRAME_HEADER_BYTES +
                        packet * VOSPI_PACKET_BYTES +
                        VOSPI_PACKET_HEADER_BYTES +
                        2 * (x % VOSPI_PIXELS_PER_PACKET)
                val lo = frame[at].toInt() and 0xff
                val hi = frame[at + 1].toInt() and 0xff
                into[out++] = if (littleEndian) lo or (hi shl 8) else hi or (lo shl 8)
            }
        }
        return true
    }

    /** Pulls a JSON string/number field out without dragging in a JSON parser. */
    fun jsonField(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"?([^\",}]+)\"?").find(json)?.groupValues?.get(1)?.trim()

    /** Drops the NUL padding and other control bytes upstream leaves on its strings. */
    fun clean(s: String): String = s.filter { it.code >= 32 }.trim()

    fun indexOf(haystack: ByteArray, haystackLen: Int, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..haystackLen - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}

/**
 * Sensor geometry and identity, as the camera states it in the one-shot
 * `sledInformation` message on EP 0x81. Never hardcoded: the 80x60 (Lepton 2) and
 * 160x120 (Lepton 3 / FLIR One Pro) units differ, and the two upstream projects
 * disagree precisely because each hardcoded its own unit's size.
 */
data class SledInfo(
    val width: Int,
    val height: Int,
    val versionLepton: String?,
    val serialNumberLepton: String?,
    val bigEndianThermal: Boolean,
) {
    companion object {
        fun parse(json: String): SledInfo? {
            val w = FlirProtocol.jsonField(json, "thermalWidth")?.toIntOrNull() ?: return null
            val h = FlirProtocol.jsonField(json, "thermalHeight")?.toIntOrNull() ?: return null
            if (w !in 1..1024 || h !in 1..1024) return null
            return SledInfo(
                width = w,
                height = h,
                versionLepton = FlirProtocol.jsonField(json, "versionLepton"),
                serialNumberLepton = FlirProtocol.jsonField(json, "serialNumberLepton"),
                bigEndianThermal = FlirProtocol.jsonField(json, "bigEndianThermal") != "0",
            )
        }
    }
}

/**
 * One decoded thermal frame: raw sensor counts, not temperatures. Conversion to
 * degrees needs this unit's own Planck constants, which are not read yet.
 */
class ThermalFrame(
    val width: Int,
    val height: Int,
    /** Row-major raw 16-bit counts, [width] * [height] entries. */
    val raw: IntArray,
    val min: Int,
    val max: Int,
    val status: FrameStatus,
    /**
     * The visible-light JPEG the camera sends alongside the thermal data in the same
     * frame. Its own lens, so it does not line up with the thermal image by itself.
     */
    val jpeg: ByteArray?,
) {
    /**
     * Spot reading at the centre of the frame, averaged over the middle four pixels
     * the way upstream does - one pixel of an 80x60 sensor is noisy enough that a
     * single sample jitters visibly between frames.
     */
    val center: Int
        get() {
            val cx = width / 2
            val cy = height / 2
            val a = raw[(cy - 1) * width + cx - 1]
            val b = raw[(cy - 1) * width + cx]
            val c = raw[cy * width + cx - 1]
            val d = raw[cy * width + cx]
            return (a + b + c + d) / 4
        }

    /**
     * Raw counts at a point given as a fraction of the frame in each axis, averaged
     * over the three-by-three block around it.
     *
     * Averaged because a single detector at 80x60 visibly jitters between frames, and
     * a spot reading that will not sit still is hard to trust. The cost is that the
     * figure smears across a hard temperature edge - a reading taken right on the rim
     * of something hot is a blend of both sides, not the rim.
     */
    fun rawAt(u: Float, v: Float): Int {
        val cx = (u * width).toInt().coerceIn(0, width - 1)
        val cy = (v * height).toInt().coerceIn(0, height - 1)
        var sum = 0
        var n = 0
        for (y in (cy - 1)..(cy + 1)) {
            if (y !in 0 until height) continue
            for (x in (cx - 1)..(cx + 1)) {
                if (x !in 0 until width) continue
                sum += raw[y * width + x]
                n++
            }
        }
        return if (n == 0) raw[cy * width + cx] else sum / n
    }
}

/**
 * The per-frame status string. Upstream tests for shutter calibration with a
 * `strncmp` at fixed offset 17, which works only because `shutterState` happens to
 * sit there; the whole string arrives intact, so read the field by name instead.
 */
class FrameStatus(val raw: String) {
    val shutterState: String? = FlirProtocol.jsonField(raw, "shutterState")
    val ffcState: String? = FlirProtocol.jsonField(raw, "ffcState")
    val shutterTemperature: String? = FlirProtocol.jsonField(raw, "shutterTemperature")

    /**
     * True while the shutter is closed for flat-field correction - the sensor is
     * looking at its own shutter, so the frame is a calibration target, not a scene.
     */
    val isFfc: Boolean
        get() = shutterState?.contains("FFC", ignoreCase = true) == true
}
