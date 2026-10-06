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

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Reading camera constants out of a FLIR JPEG.
 *
 * No real JPEG goes into the repository: every one the official app saves carries
 * the camera's serial number and a visible photo. Instead the test builds the
 * smallest file with the same structure - an FFF blob split across numbered APP1
 * "FLIR" chunks, a big-endian record index, a little-endian CameraInfo record at
 * exiftool's offsets - with this project's unit's values in it. The byte-order
 * split is the trap that made the Python tool come back empty on app 2.20.1.
 */
class FffReaderTest {

    @Test
    fun readsTheConstantsOfAFlirOneJpeg() {
        val c = FffReader.read(flirJpeg())
        assertEquals(16515.199219, c.r1, 1e-3)
        assertEquals(1435.0, c.b, 0.0)
        assertEquals(1.0, c.f, 0.0)
        assertEquals(-4387.0, c.o, 0.0)
        assertEquals(0.0125, c.r2, 1e-9)
        assertEquals(0.80, c.irWindowTransmission, 1e-6)
        assertEquals(298.15, c.irWindowTemperature, 1e-4)
        assertEquals(293.15, c.atmosphericTemperature, 1e-4)
        assertEquals("FLIR ONE (gen 3)", c.model)
    }

    @Test
    fun theDevelopmentUnitReadsAsUnchanged() {
        // Floats in the file, doubles in the code: re-importing the same camera must
        // not count as a change, or it throws away the user's calibration.
        assertTrue(FffReader.read(flirJpeg()).sameAs(CameraConstants.BUILT_IN))
    }

    @Test
    fun chunksAreJoinedByNumberNotByOrderInTheFile() {
        FffReader.read(flirJpeg(chunksReversed = true))
    }

    @Test
    fun aLittleEndianIndexIsReadToo() {
        assertEquals(1435.0, FffReader.read(flirJpeg(indexOrder = ByteOrder.LITTLE_ENDIAN)).b, 0.0)
    }

    @Test
    fun anOrdinaryJpegIsRefused() {
        assertRefused(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDA.toByte(), 0, 2))
    }

    @Test
    fun implausibleValuesAreRefused() {
        // R1 read with the wrong byte order comes out as noise; it must not pass.
        assertRefused(flirJpeg(r1 = 1.0e-20f))
    }

    private fun assertRefused(jpeg: ByteArray) {
        try {
            FffReader.read(jpeg)
            fail("expected NotFlir")
        } catch (expected: FffReader.NotFlir) {
        }
    }

    private fun cameraInfo(r1: Float): ByteArray {
        val rec = ByteBuffer.allocate(0x400).order(ByteOrder.LITTLE_ENDIAN)
        rec.putFloat(0x20, 0.95f) // emissivity
        rec.putFloat(0x24, 1.0f) // object distance
        rec.putFloat(0x28, 295.15f) // reflected
        rec.putFloat(0x2C, 293.15f) // air
        rec.putFloat(0x30, 298.15f) // window temperature
        rec.putFloat(0x34, 0.80f) // window transmission
        rec.putFloat(0x3C, 0.50f) // humidity
        rec.putFloat(0x58, r1)
        rec.putFloat(0x5C, 1435.0f)
        rec.putFloat(0x60, 1.0f)
        rec.putInt(0x308, -4387)
        rec.putFloat(0x30C, 0.0125f)
        val model = "FLIR ONE (gen 3)".toByteArray(Charsets.ISO_8859_1)
        model.copyInto(rec.array(), 0xD4)
        return rec.array()
    }

    private fun fff(indexOrder: ByteOrder, r1: Float): ByteArray {
        val info = cameraInfo(r1)
        val indexAt = 0x40
        val infoAt = indexAt + 2 * 32
        val blob = ByteBuffer.allocate(infoAt + info.size).order(indexOrder)
        "FFF\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(blob.array(), 0)
        blob.putInt(0x18, indexAt)
        blob.putInt(0x1C, 2)
        // An empty raw-data entry first, so the reader has to look past it.
        blob.putShort(indexAt, 0x01)
        blob.putShort(indexAt + 32, 0x20)
        blob.putShort(indexAt + 32 + 2, 1)
        blob.putInt(indexAt + 32 + 12, infoAt)
        blob.putInt(indexAt + 32 + 16, info.size)
        info.copyInto(blob.array(), infoAt)
        return blob.array()
    }

    private fun flirJpeg(
        indexOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
        chunksReversed: Boolean = false,
        r1: Float = 16515.199f,
    ): ByteArray {
        val blob = fff(indexOrder, r1)
        val half = blob.size / 2
        val chunks = listOf(blob.copyOfRange(0, half), blob.copyOfRange(half, blob.size))
        val order = if (chunksReversed) listOf(1, 0) else listOf(0, 1)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        for (n in order) {
            val payload = "FLIR\u0000".toByteArray(Charsets.ISO_8859_1) +
                byteArrayOf(1, n.toByte(), (chunks.size - 1).toByte()) + chunks[n]
            val length = payload.size + 2
            out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()))
            out.write(payload)
        }
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2))
        return out.toByteArray()
    }
}
