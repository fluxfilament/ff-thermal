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

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Builds camera traffic byte for byte, the way the dongle puts it on the wire, so the
 * decoding path can be exercised without one plugged in.
 *
 * The packet IDs and CRCs inside the thermal payload are filled with values that look
 * nothing like pixels. That is deliberate: the one decoding bug this project has
 * already met (upstream's extra 4-byte skip at 80 wide) reads the last pixels of a row
 * out of the next packet's header, and a fixture with zeroed headers would let it pass.
 */
object TestFrames {

    /** A pixel value no real scene in these tests produces, for header bytes. */
    private const val HEADER_NOISE = 0xA5A5

    /**
     * The thermal payload: [height] image rows plus [telemetryRows] Lepton telemetry
     * rows, each row one VoSPI packet per 80 pixels. [pixel] gives the raw count at
     * (x, y).
     */
    fun thermalPayload(
        width: Int,
        height: Int,
        telemetryRows: Int = 3,
        littleEndian: Boolean = true,
        pixel: (x: Int, y: Int) -> Int,
    ): ByteArray {
        val packetsPerRow = FlirProtocol.packetsPerRow(width)
        val rows = height + telemetryRows
        val out = ByteArray(rows * packetsPerRow * FlirProtocol.VOSPI_PACKET_BYTES)
        for (row in 0 until rows) {
            for (p in 0 until packetsPerRow) {
                val packet = row * packetsPerRow + p
                val at = packet * FlirProtocol.VOSPI_PACKET_BYTES
                // ID: a sequence number, as on the real sensor. CRC: anything but pixels.
                put16(out, at, packet, littleEndian)
                put16(out, at + 2, HEADER_NOISE xor packet, littleEndian)
                for (i in 0 until FlirProtocol.VOSPI_PIXELS_PER_PACKET) {
                    val x = p * FlirProtocol.VOSPI_PIXELS_PER_PACKET + i
                    val value = when {
                        row >= height -> 0x7777 // telemetry row
                        x >= width -> 0
                        else -> pixel(x, row)
                    }
                    put16(out, at + FlirProtocol.VOSPI_PACKET_HEADER_BYTES + 2 * i, value, littleEndian)
                }
            }
        }
        return out
    }

    /** A whole EP 0x85 frame: the 28-byte header, then thermal, JPEG and status. */
    fun frame(thermal: ByteArray, jpeg: ByteArray = ByteArray(0), status: String = STATUS): ByteArray {
        val statusBytes = status.toByteArray(Charsets.UTF_8)
        val payload = thermal.size + jpeg.size + statusBytes.size
        val bb = ByteBuffer.allocate(FlirProtocol.FRAME_HEADER_BYTES + payload).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(FlirProtocol.FRAME_MAGIC)
        bb.putInt(0) // unused by the decoder
        bb.putInt(payload)
        bb.putInt(thermal.size)
        bb.putInt(jpeg.size)
        bb.putInt(statusBytes.size)
        bb.putInt(0)
        bb.put(thermal).put(jpeg).put(statusBytes)
        return bb.array()
    }

    /** A config message as it arrives on EP 0x81: 16-byte header, then the JSON. */
    fun telemetry(json: String): ByteArray {
        val payload = json.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(payload) }.value.toInt()
        return ByteBuffer.allocate(16 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(FlirProtocol.TELEMETRY_MAGIC)
            .putInt(1)
            .putInt(payload.size)
            .putInt(crc)
            .put(payload)
            .array()
    }

    /** The shape of a real sledInformation message, with made-up serial numbers. */
    const val SLED = """{"type":"sledInformation","data":{"serialNumberBoard":"TESTBOARD01",""" +
        """"partNumberBoard":"invalid","versionBoard":"invalid","serialNumberLepton":"98765432",""" +
        """"versionLepton":"1.0.30","leptonQR":"QR-TEST-0001","versionRosebudAPI":"master.bc654fc",""" +
        """"formFactor":"dongle","thermalHeight":"60","thermalWidth":"80","bigEndianThermal":"0"}}"""

    const val STATUS = """{"shutterState":"ON","shutterTemperature":304.5,"ffcState":"FFC_VALID"}"""

    /** Feeds [bytes] to [sink] in pieces of [size], as bulk reads would. */
    fun inChunks(bytes: ByteArray, size: Int, sink: (ByteArray, Int) -> Unit) {
        var at = 0
        while (at < bytes.size) {
            val n = minOf(size, bytes.size - at)
            sink(bytes.copyOfRange(at, at + n), n)
            at += n
        }
    }

    private fun put16(into: ByteArray, at: Int, value: Int, littleEndian: Boolean) {
        val lo = (value and 0xff).toByte()
        val hi = (value shr 8 and 0xff).toByte()
        if (littleEndian) {
            into[at] = lo; into[at + 1] = hi
        } else {
            into[at] = hi; into[at + 1] = lo
        }
    }
}
