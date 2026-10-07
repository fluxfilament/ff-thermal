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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cutting the USB byte streams back into messages. A bulk read can stop anywhere, and
 * the poll loop can start listening mid-message, so what is checked here is mostly what
 * happens at the seams.
 */
class AssemblersTest {

    // --- EP 0x85: frames ----------------------------------------------------------

    private class Received(val thermal: ByteArray, val jpeg: ByteArray, val status: String)

    private fun frameAssembler(into: MutableList<Received>, log: MutableList<String> = mutableListOf()) =
        FrameAssembler(log = { log += it }) { buf, thermalSize, jpgSize, statusSize ->
            // The buffer is reused after the callback, so copy out what is kept.
            val t0 = FlirProtocol.FRAME_HEADER_BYTES
            into += Received(
                thermal = buf.copyOfRange(t0, t0 + thermalSize),
                jpeg = buf.copyOfRange(t0 + thermalSize, t0 + thermalSize + jpgSize),
                status = String(buf, t0 + thermalSize + jpgSize, statusSize, Charsets.UTF_8),
            )
        }

    private val thermal = TestFrames.thermalPayload(80, 60) { x, y -> 3000 + x + y }
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
    private val frame = TestFrames.frame(thermal, jpeg)

    private fun assertIsTheFrame(got: Received) {
        assertArrayEquals(thermal, got.thermal)
        assertArrayEquals(jpeg, got.jpeg)
        assertEquals(TestFrames.STATUS, got.status)
    }

    @Test
    fun aFrameInOneReadComesOutWhole() {
        val got = mutableListOf<Received>()
        val assembler = frameAssembler(got)
        assembler.feed(frame, frame.size)
        assertEquals(1, got.size)
        assertIsTheFrame(got[0])
        assertEquals(0, assembler.resyncs)
    }

    @Test
    fun theSplitPointDoesNotMatter() {
        // One byte at a time cuts through the magic, the header and the payload alike.
        for (size in listOf(1, 3, 27, 28, 29, 512, 16384)) {
            val got = mutableListOf<Received>()
            val assembler = frameAssembler(got)
            TestFrames.inChunks(frame, size, assembler::feed)
            assertEquals("reads of $size bytes", 1, got.size)
            assertIsTheFrame(got[0])
        }
    }

    @Test
    fun framesBackToBackInOneReadAreBothDelivered() {
        val got = mutableListOf<Received>()
        val assembler = frameAssembler(got)
        val two = frame + frame
        assembler.feed(two, two.size)
        assertEquals(2, got.size)
        assertEquals(2, assembler.completed)
    }

    @Test
    fun listeningMidFrameSkipsToTheNextMagic() {
        val got = mutableListOf<Received>()
        val assembler = frameAssembler(got)
        // The tail end of a frame the loop missed the start of, then a whole one.
        val stream = frame.copyOfRange(frame.size / 2, frame.size) + frame
        TestFrames.inChunks(stream, 4096, assembler::feed)
        assertEquals(1, got.size)
        assertIsTheFrame(got[0])
    }

    @Test
    fun aMagicWithANonsenseHeaderIsPassedOver() {
        val got = mutableListOf<Received>()
        val log = mutableListOf<String>()
        val assembler = frameAssembler(got, log)
        // Magic bytes followed by sizes that do not add up, then a real frame.
        val bogus = FlirProtocol.FRAME_MAGIC + ByteArray(24) { 0x11 }
        val stream = bogus + frame
        assembler.feed(stream, stream.size)
        assertEquals(1, got.size)
        assertIsTheFrame(got[0])
        assertTrue(assembler.resyncs >= 1)
        assertTrue(log.any { it.contains("implausible header") })
    }

    // --- EP 0x81: config messages -------------------------------------------------

    private class Telemetry {
        val log = mutableListOf<String>()
        val sleds = mutableListOf<SledInfo>()
        val assembler = TelemetryAssembler(log = { log += it }) { info, _ -> sleds += info }
        fun feed(bytes: ByteArray) = assembler.feed(bytes, bytes.size)
    }

    private val battery = """{"type":"batteryVoltageUpdate","data":{"voltage":3.77,"percentage":51}}"""

    @Test
    fun sledInformationConfiguresTheGeometry() {
        val t = Telemetry()
        t.feed(TestFrames.telemetry(TestFrames.SLED))
        assertEquals(1, t.assembler.messages)
        assertEquals(1, t.sleds.size)
        assertEquals(80, t.sleds[0].width)
        assertEquals(60, t.sleds[0].height)
        assertEquals("1.0.30", t.assembler.sledInfo!!.versionLepton)
    }

    @Test
    fun headerAndPayloadInSeparateReads() {
        // How the dongle actually sends them.
        val t = Telemetry()
        val message = TestFrames.telemetry(TestFrames.SLED)
        t.feed(message.copyOfRange(0, 16))
        assertNull(t.assembler.sledInfo)
        t.feed(message.copyOfRange(16, message.size))
        assertNotNull(t.assembler.sledInfo)
        assertEquals(1, t.sleds.size)
    }

    @Test
    fun aMagicInsideBinaryDataDoesNotSwallowTheNextMessage() {
        val t = Telemetry()
        // Magic, a wrong always-1 word and a huge size: the shape of chance bytes.
        val chance = FlirProtocol.TELEMETRY_MAGIC_BYTES + byteArrayOf(7, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0)
        // A plausible header whose size is over the cap.
        val oversize = TestFrames.telemetry(battery).also { it[8] = 0; it[9] = 0; it[10] = 1 }
        t.feed(chance + oversize.copyOfRange(0, 16) + TestFrames.telemetry(TestFrames.SLED))
        assertEquals(1, t.sleds.size)
        assertEquals(1, t.assembler.messages)
    }

    @Test
    fun aMissedHeaderIsRecoveredFromTheText() {
        // The loop came up after sledInformation started: only its tail arrives, with
        // no header to parse, and the geometry still has to be found.
        val t = Telemetry()
        val message = TestFrames.telemetry(TestFrames.SLED)
        TestFrames.inChunks(message.copyOfRange(40, message.size), 64) { chunk, n -> t.assembler.feed(chunk, n) }
        assertEquals(0, t.assembler.messages)
        assertEquals(1, t.sleds.size)
        assertEquals(80, t.sleds[0].width)
        assertTrue(t.log.any { it.contains("raw-text scan") })
    }

    @Test
    fun geometryIsTakenOnce() {
        val t = Telemetry()
        t.feed(TestFrames.telemetry(TestFrames.SLED))
        t.feed(TestFrames.telemetry(TestFrames.SLED.replace("\"80\"", "\"160\"").replace("\"60\"", "\"120\"")))
        assertEquals(1, t.sleds.size)
        assertEquals(80, t.assembler.sledInfo!!.width)
    }

    @Test
    fun theLogShowsEachTypeOnceAndNoSerials() {
        val t = Telemetry()
        repeat(5) { t.feed(TestFrames.telemetry(battery)) }
        t.feed(TestFrames.telemetry(TestFrames.SLED))
        assertEquals(6, t.assembler.messages)
        assertEquals(1, t.log.count { it.contains("batteryVoltageUpdate") })
        val all = t.log.joinToString("\n")
        for (secret in listOf("TESTBOARD01", "98765432", "QR-TEST-0001")) {
            assertFalse("$secret in the log", all.contains(secret))
        }
    }
}
