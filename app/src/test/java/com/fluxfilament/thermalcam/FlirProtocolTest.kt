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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the app writes to its log about the camera, and what it reads from the same message. */
class FlirProtocolTest {

    // The shape of a real sledInformation message, with made-up serial numbers.
    private val sled = """{"type":"sledInformation","data":{"serialNumberBoard":"TESTBOARD01",""" +
        """"partNumberBoard":"invalid","versionBoard":"invalid","serialNumberLepton":"98765432",""" +
        """"versionLepton":"1.0.30","leptonQR":"QR-TEST-0001","versionRosebudAPI":"master.bc654fc",""" +
        """"formFactor":"dongle","thermalHeight":"60","thermalWidth":"80","bigEndianThermal":"0"}}"""

    @Test
    fun serialNumbersNeverReachTheLog() {
        val logged = FlirProtocol.redactIdentifiers(sled)
        for (secret in listOf("TESTBOARD01", "98765432", "QR-TEST-0001")) {
            assertFalse("$secret in $logged", logged.contains(secret))
        }
        assertEquals("hidden", FlirProtocol.jsonField(logged, "serialNumberBoard"))
        assertEquals("hidden", FlirProtocol.jsonField(logged, "leptonQR"))
    }

    @Test
    fun everythingElseIsLoggedAsItCame() {
        val logged = FlirProtocol.redactIdentifiers(sled)
        assertTrue(logged.contains(""""versionLepton":"1.0.30""""))
        assertTrue(logged.contains(""""partNumberBoard":"invalid""""))
        val info = SledInfo.parse(logged)!!
        assertEquals(80, info.width)
        assertEquals(60, info.height)
        assertEquals("1.0.30", info.versionLepton)
    }

    @Test
    fun anUnquotedSerialIsHiddenToo() {
        val logged = FlirProtocol.redactIdentifiers("""{"serialNumberLepton": 98765432, "thermalWidth":"80"}""")
        assertFalse(logged, logged.contains("98765432"))
        assertTrue(logged.contains(""""thermalWidth":"80""""))
    }

    @Test
    fun messagesWithoutIdentifiersAreUntouched() {
        val battery = """{"type":"batteryVoltageUpdate","data":{"voltage":3.77,"percentage":51}}"""
        assertEquals(battery, FlirProtocol.redactIdentifiers(battery))
    }

    @Test
    fun geometryComesFromTheMessage() {
        val info = SledInfo.parse(sled)!!
        assertEquals(80, info.width)
        assertEquals(60, info.height)
        assertFalse(info.bigEndianThermal)
    }
}
