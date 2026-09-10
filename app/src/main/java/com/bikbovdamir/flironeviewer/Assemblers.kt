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

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reassembles the EP 0x81 byte stream into whole config messages. Header is 4
 * little-endian words: magic (0x1cc), an always-1 word, payload size, CRC-32.
 * Payload and header arrive in separate bulk transfers, and a read can land
 * mid-message, so this resyncs on the magic rather than trusting offset 0.
 */
class TelemetryAssembler(
    private val log: (String) -> Unit,
    private val onSled: (SledInfo, String) -> Unit,
) {
    private val buf = ByteArray(256 * 1024)
    private var len = 0
    private val seenTypes = HashSet<String>()
    private val rawText = StringBuilder()

    var messages = 0
        private set
    var sledInfo: SledInfo? = null
        private set

    fun feed(chunk: ByteArray, n: Int) {
        scanRawForResolution(chunk, n)
        if (len + n > buf.size) len = 0 // pathological; start over rather than grow
        System.arraycopy(chunk, 0, buf, len, n)
        len += n
        drain()
    }

    /**
     * sledInformation is sent once as the stream comes up, so the poll loop can start
     * mid-message and never see a parseable header for it. The geometry is what the
     * whole decode pipeline is configured from, so also recover it straight out of the
     * bytes as a fallback.
     */
    private fun scanRawForResolution(chunk: ByteArray, n: Int) {
        if (sledInfo != null) return
        for (i in 0 until n) {
            val c = chunk[i].toInt() and 0xff
            rawText.append(if (c in 32..126) c.toChar() else ' ')
        }
        if (rawText.length > 32 * 1024) rawText.delete(0, rawText.length - 8 * 1024)
        val text = rawText.toString()
        if (!text.contains("thermalWidth") || !text.contains("thermalHeight")) return
        val parsed = SledInfo.parse(text) ?: return
        log("EP 0x81: recovered sled geometry from a raw-text scan (header was missed)")
        accept(parsed, text)
    }

    private fun drain() {
        while (len >= 16) {
            val bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.getInt(0) != FlirProtocol.TELEMETRY_MAGIC) {
                if (!resync()) return
                continue
            }
            val always1 = bb.getInt(4)
            val payloadSize = bb.getInt(8)
            // The magic is only two significant bytes, so it turns up inside binary
            // data by chance. Demand the always-1 word and a sane size too.
            if (always1 != 1 || payloadSize <= 0 || payloadSize > FlirProtocol.MAX_TELEMETRY_PAYLOAD) {
                if (!resync()) return
                continue
            }
            if (len < 16 + payloadSize) return // wait for the rest
            // Every payload seen on this endpoint is a JSON object.
            if (buf[16] != '{'.code.toByte()) {
                if (!resync()) return
                continue
            }
            val payload = FlirProtocol.clean(String(buf, 16, payloadSize, Charsets.UTF_8))
            messages++
            onMessage(payload)
            consume(16 + payloadSize)
        }
    }

    /** Drops bytes up to the next plausible magic. False when nothing is left to find. */
    private fun resync(): Boolean {
        val at = FlirProtocol.indexOf(buf, len, FlirProtocol.TELEMETRY_MAGIC_BYTES, from = 1)
        if (at < 0) {
            // Keep a short tail: a magic could straddle two reads.
            val keep = minOf(len, FlirProtocol.TELEMETRY_MAGIC_BYTES.size - 1)
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
        val type = FlirProtocol.jsonField(payload, "type") ?: "unknown"
        // Battery updates repeat every second; log each type once, in full.
        if (seenTypes.add(type)) log("EP 0x81 message type=$type: $payload")
        if (sledInfo != null) return
        if (type != "sledInformation" && !payload.contains("thermalWidth")) return
        SledInfo.parse(payload)?.let { accept(it, payload) }
    }

    private fun accept(info: SledInfo, source: String) {
        sledInfo = info
        log(
            "*** GEOMETRY FROM DEVICE: ${info.width}x${info.height}, " +
                    "lepton=${info.versionLepton}, " +
                    "endian=${if (info.bigEndianThermal) "big" else "little"} ***"
        )
        onSled(info, source)
    }
}

/**
 * Reassembles the EP 0x85 byte stream into whole frames, resyncing on the magic.
 * Hands each complete frame to [onFrame] as (buffer, thermalSize, jpgSize,
 * statusSize); the buffer is reused, so consumers must not retain it.
 */
class FrameAssembler(
    private val log: (String) -> Unit,
    private val onFrame: (ByteArray, Int, Int, Int) -> Unit,
) {
    private val buf = ByteArray(2 * 1024 * 1024)
    private var len = 0

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
            val start = FlirProtocol.indexOf(buf, len, FlirProtocol.FRAME_MAGIC)
            if (start < 0) {
                val keep = minOf(len, FlirProtocol.FRAME_MAGIC.size - 1)
                System.arraycopy(buf, len - keep, buf, 0, keep)
                len = keep
                return
            }
            if (start > 0) consume(start)
            if (len < FlirProtocol.FRAME_HEADER_BYTES) return

            val bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
            val frameSize = bb.getInt(8)
            val thermalSize = bb.getInt(12)
            val jpgSize = bb.getInt(16)
            val statusSize = bb.getInt(20)

            val sane = frameSize > 0 &&
                    frameSize <= buf.size - FlirProtocol.FRAME_HEADER_BYTES &&
                    thermalSize >= 0 && jpgSize >= 0 && statusSize >= 0 &&
                    thermalSize + jpgSize + statusSize == frameSize
            if (!sane) {
                if (completed == 0) {
                    log(
                        "EP 0x85: implausible header (frameSize=$frameSize thermal=$thermalSize " +
                                "jpg=$jpgSize status=$statusSize) - skipping this magic"
                    )
                }
                resyncs++
                consume(FlirProtocol.FRAME_MAGIC.size)
                continue
            }

            val total = FlirProtocol.FRAME_HEADER_BYTES + frameSize
            if (len < total) return // rest of the frame hasn't arrived yet

            completed++
            onFrame(buf, thermalSize, jpgSize, statusSize)
            consume(total)
        }
    }

    private fun consume(count: Int) {
        System.arraycopy(buf, count, buf, 0, len - count)
        len -= count
    }
}
