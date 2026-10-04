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

/**
 * The per-unit constants a FLIR ONE writes into every JPEG its official app saves:
 * the Planck coefficients and the IR window in front of the lens. Atmosphere and
 * window default to what this project's own unit carries, which is also what the
 * app writes for every gen 3 seen so far.
 */
data class CameraConstants(
    val r1: Double,
    val b: Double,
    val f: Double,
    val o: Double,
    val r2: Double,
    val irWindowTransmission: Double,
    val irWindowTemperature: Double,
    val atmosphericTemperature: Double,
    /** "FLIR ONE (gen 3)" and the like - shown back so the user can tell it was read. */
    val model: String,
) {
    fun applyTo(planck: Planck): Planck = planck.copy(
        r1 = r1, b = b, f = f, o = o, r2 = r2,
        irWindowTransmission = irWindowTransmission,
        irWindowTemperature = irWindowTemperature,
        atmosphericTemperature = atmosphericTemperature,
    )

    /**
     * Equal to the precision a JPEG stores them in. The file holds 32-bit floats, so
     * this unit's R1 comes back as 16515.19921875 against the 16515.199219 written
     * in the code - the same constant, and re-importing it must not count as a
     * change, or it would throw away a calibration for nothing.
     */
    fun sameAs(other: CameraConstants): Boolean {
        fun close(a: Double, b: Double) = kotlin.math.abs(a - b) <= 1e-6 * maxOf(1.0, kotlin.math.abs(a))
        return close(r1, other.r1) && close(b, other.b) && close(f, other.f) &&
            close(o, other.o) && close(r2, other.r2) &&
            close(irWindowTransmission, other.irWindowTransmission) &&
            close(irWindowTemperature, other.irWindowTemperature) &&
            close(atmosphericTemperature, other.atmosphericTemperature)
    }

    /** Kept as text: doubles round-trip through toString exactly, floats in prefs do not. */
    fun serialize(): String = listOf(
        r1, b, f, o, r2, irWindowTransmission, irWindowTemperature, atmosphericTemperature,
    ).joinToString(";") + ";" + model.replace(';', ',')

    companion object {
        fun of(planck: Planck) = CameraConstants(
            planck.r1, planck.b, planck.f, planck.o, planck.r2,
            planck.irWindowTransmission, planck.irWindowTemperature, planck.atmosphericTemperature,
            model = "",
        )

        val BUILT_IN: CameraConstants = of(Planck.DEVELOPMENT_UNIT)

        fun deserialize(text: String): CameraConstants? {
            val parts = text.split(';')
            if (parts.size != 9) return null
            val v = parts.take(8).map { it.toDoubleOrNull() ?: return null }
            return CameraConstants(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], parts[8])
        }
    }
}

/**
 * Reads [CameraConstants] out of a FLIR ONE JPEG - the Kotlin side of
 * `tools/fff_parse.py`, at the same exiftool offsets.
 *
 * The FFF blob rides in APP1 segments tagged "FLIR\0", split into numbered chunks
 * that have to be put back together before its record index makes sense. The index
 * is big-endian on these cameras and the numbers inside a record little-endian;
 * reading a record in the index's order gives garbage, which is how the Python
 * tool's first version came back empty on JPEGs from app 2.20.1.
 */
object FffReader {

    class NotFlir(message: String) : Exception(message)

    fun read(jpeg: ByteArray): CameraConstants {
        val fff = collectFff(jpeg)
        if (fff.size < 0x20 || !fff.startsWith("FFF\u0000")) throw NotFlir("no FFF data")
        val info = cameraInfo(fff) ?: throw NotFlir("no CameraInfo record")
        val le = ByteBuffer.wrap(info).order(ByteOrder.LITTLE_ENDIAN)
        if (info.size < 0x310) throw NotFlir("CameraInfo too short")
        val constants = CameraConstants(
            r1 = le.getFloat(0x58).toDouble(),
            b = le.getFloat(0x5C).toDouble(),
            f = le.getFloat(0x60).toDouble(),
            o = le.getInt(0x308).toDouble(),
            r2 = le.getFloat(0x30C).toDouble(),
            irWindowTransmission = le.getFloat(0x34).toDouble(),
            irWindowTemperature = le.getFloat(0x30).toDouble(),
            atmosphericTemperature = le.getFloat(0x2C).toDouble(),
            model = text(info, 0xD4, 32),
        )
        if (!plausible(constants)) throw NotFlir("values out of range")
        return constants
    }

    /**
     * Ranges wide enough for every unit on record (R1 16515-18666, O -1307 to
     * -4387) and narrow enough that a misread record cannot pass.
     */
    private fun plausible(c: CameraConstants): Boolean =
        c.r1 in 1e3..1e6 && c.b in 1e3..2e3 && c.f in 0.5..2.0 &&
            c.o in -1e5..0.0 && c.r2 in 1e-4..1.0 &&
            c.irWindowTransmission in 0.5..1.0 &&
            c.irWindowTemperature in 250.0..350.0 &&
            c.atmosphericTemperature in 230.0..330.0

    private fun collectFff(data: ByteArray): ByteArray {
        val chunks = sortedMapOf<Int, ByteArray>()
        var i = 2
        while (i < data.size - 4) {
            if (data[i] != 0xFF.toByte()) {
                i++
                continue
            }
            val marker = data[i + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                i += 2
                continue
            }
            if (marker == 0xDA) break // start of scan: the metadata is all behind us
            val length = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            val end = i + 2 + length
            if (end > data.size) break
            if (marker == 0xE1 && end - (i + 4) > 8 && matches(data, i + 4, "FLIR\u0000")) {
                // one reserved byte, then the chunk number and the chunk count
                chunks[data[i + 10].toInt() and 0xFF] = data.copyOfRange(i + 12, end)
            }
            i = end
        }
        val out = java.io.ByteArrayOutputStream()
        for (chunk in chunks.values) out.write(chunk)
        return out.toByteArray()
    }

    private fun cameraInfo(fff: ByteArray): ByteArray? {
        for (order in listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
            val buf = ByteBuffer.wrap(fff).order(order)
            val indexOff = buf.getInt(0x18)
            val count = buf.getInt(0x1C)
            if (count !in 1 until 1000 || indexOff <= 0 || indexOff + count * 32 > fff.size) continue
            for (n in 0 until count) {
                val at = indexOff + n * 32
                val main = buf.getShort(at).toInt() and 0xFFFF
                val off = buf.getInt(at + 12)
                val len = buf.getInt(at + 16)
                if (main == CAMERA_INFO && len > 0 && off >= 0 && off + len <= fff.size) {
                    return fff.copyOfRange(off, off + len)
                }
            }
        }
        return null
    }

    private fun text(rec: ByteArray, at: Int, size: Int): String {
        val end = (at until at + size).firstOrNull { rec[it] == 0.toByte() } ?: (at + size)
        return String(rec, at, end - at, Charsets.ISO_8859_1).trim()
    }

    private fun ByteArray.startsWith(prefix: String) = matches(this, 0, prefix)

    private fun matches(data: ByteArray, at: Int, prefix: String): Boolean =
        prefix.indices.all { at + it < data.size && data[at + it] == prefix[it].code.toByte() }

    private const val CAMERA_INFO = 0x20
}
