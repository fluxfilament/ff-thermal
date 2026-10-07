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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * From the thermal payload of one frame to a row-major image of raw counts, and the
 * spot readings taken off that image.
 */
class FrameDecodeTest {

    /** Every pixel different, so a pixel read from the wrong place cannot pass. */
    private fun scene(x: Int, y: Int) = 1000 + y * 160 + x

    private fun expected(width: Int, height: Int) =
        IntArray(width * height) { scene(it % width, it / width) }

    private fun decode(thermal: ByteArray, width: Int, height: Int, littleEndian: Boolean = true): IntArray? {
        val frame = TestFrames.frame(thermal)
        val into = IntArray(width * height)
        val ok = FlirProtocol.deinterleave(frame, thermal.size, width, height, littleEndian, into)
        return if (ok) into else null
    }

    @Test
    fun leptonTwoPayloadIsOnePacketPerRow() {
        val thermal = TestFrames.thermalPayload(80, 60, telemetryRows = 3, pixel = ::scene)
        // The size this unit actually sends: 63 packets, 60 image rows and 3 of telemetry.
        assertEquals(10332, thermal.size)
        assertEquals(3, FlirProtocol.telemetryPackets(thermal.size, 80, 60))
        assertArrayEquals(expected(80, 60), decode(thermal, 80, 60))
    }

    @Test
    fun theFixtureWouldCatchUpstreamsMidRowSkip() {
        // flirone-v4l2 skips 4 more bytes from x = 40 on. At 80 wide that walks the
        // last two pixels of every row into the next packet's ID and CRC. Read the last
        // pixel of row 0 that way and it must not look like the scene, or this fixture
        // could not tell the two decoders apart.
        val thermal = TestFrames.thermalPayload(80, 60, pixel = ::scene)
        val frame = TestFrames.frame(thermal)
        val skewed = FlirProtocol.FRAME_HEADER_BYTES + FlirProtocol.VOSPI_PACKET_HEADER_BYTES + 2 * 79 + 4
        val read = (frame[skewed].toInt() and 0xff) or (frame[skewed + 1].toInt() and 0xff shl 8)
        assertNotEquals(scene(79, 0), read)
        assertEquals(scene(79, 0), decode(thermal, 80, 60)!![79])
    }

    @Test
    fun leptonThreeRowsSpanTwoPackets() {
        // The FLIR One Pro geometry: never run on hardware here, but the packet model
        // says each row is two packets and the second one has its own header.
        val thermal = TestFrames.thermalPayload(160, 120, telemetryRows = 2, pixel = ::scene)
        assertEquals(2, FlirProtocol.packetsPerRow(160))
        // Two rows of telemetry at two packets a row.
        assertEquals(4, FlirProtocol.telemetryPackets(thermal.size, 160, 120))
        val image = decode(thermal, 160, 120)!!
        assertArrayEquals(expected(160, 120), image)
        // The first pixel after the second packet's header, spelled out.
        assertEquals(scene(80, 7), image[7 * 160 + 80])
    }

    @Test
    fun byteOrderFollowsTheSledFlag() {
        val thermal = TestFrames.thermalPayload(80, 60, littleEndian = false, pixel = ::scene)
        assertArrayEquals(expected(80, 60), decode(thermal, 80, 60, littleEndian = false))
        assertFalse(expected(80, 60).contentEquals(decode(thermal, 80, 60, littleEndian = true)!!))
    }

    @Test
    fun aPayloadTooShortForTheGeometryIsRefused() {
        val thermal = TestFrames.thermalPayload(80, 60, telemetryRows = 0, pixel = ::scene)
        val frame = TestFrames.frame(thermal)
        val into = IntArray(80 * 60)
        assertTrue(FlirProtocol.deinterleave(frame, thermal.size, 80, 60, true, into))
        assertFalse(FlirProtocol.deinterleave(frame, thermal.size - 1, 80, 60, true, into))
        // An 80x60 payload read as a Lepton 3 frame: refused, not rendered as garbage.
        assertFalse(FlirProtocol.deinterleave(frame, thermal.size, 160, 120, true, IntArray(160 * 120)))
        assertFalse(FlirProtocol.deinterleave(frame, thermal.size, 80, 60, true, IntArray(80 * 60 - 1)))
    }

    private fun frameOf(raw: IntArray, status: String = TestFrames.STATUS) =
        ThermalFrame(80, 60, raw, raw.min(), raw.max(), FrameStatus(status), jpeg = null)

    @Test
    fun spotReadingsAverageTheNeighbourhood() {
        val frame = frameOf(expected(80, 60))
        // Middle of the frame: the 3x3 block around (40, 30), which averages to itself
        // on a linear scene.
        assertEquals(scene(40, 30), frame.rawAt(0.5f, 0.5f))
        // A corner has only four neighbours on the picture, and only those count.
        val corner = (scene(0, 0) + scene(1, 0) + scene(0, 1) + scene(1, 1)) / 4
        assertEquals(corner, frame.rawAt(0f, 0f))
        // Off the far edge is clamped onto the last pixel, not out of bounds.
        val far = (scene(78, 58) + scene(79, 58) + scene(78, 59) + scene(79, 59)) / 4
        assertEquals(far, frame.rawAt(1f, 1f))
        assertEquals(far, frame.rawAt(1.5f, 2f))
    }

    @Test
    fun oneHotPixelIsDilutedNotReported() {
        val raw = IntArray(80 * 60) { 3000 }
        raw[30 * 80 + 40] = 3000 + 900
        val frame = frameOf(raw)
        assertEquals(3100, frame.rawAt(0.5f, 0.5f))
        // The centre reading is the middle four pixels, the hot one among them.
        assertEquals(3225, frame.center)
    }

    @Test
    fun shutterStateIsReadByName() {
        assertFalse(FrameStatus(TestFrames.STATUS).isFfc)
        val closed = FrameStatus("""{"ffcState":"FFC_PROGRESS","shutterTemperature":304.5,"shutterState":"FFC"}""")
        assertTrue(closed.isFfc)
        assertEquals("FFC_PROGRESS", closed.ffcState)
        assertEquals("304.5", closed.shutterTemperature)
    }
}
