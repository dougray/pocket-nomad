// Port of MeshTests/MeshProtocolTests.swift. Coordinates are made up.
package org.nomadpocket.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nomadpocket.mesh.proto.*

class MeshProtocolTest {
    @Test fun wantConfigWireBytes() {
        assertEquals(listOf(0x18, 0x01), MeshPBToRadio.WantConfigID(1).encode().u())
        assertEquals(listOf(0x18, 0xAC, 0x02), MeshPBToRadio.WantConfigID(300).encode().u())
    }

    @Test fun broadcastTextPacketWireBytes() {
        val p = MeshPBPacket(
            to = MeshConstants.BROADCAST_NUM,
            decoded = MeshPBData(portnum = 1, payload = "hi".toByteArray()),
            id = 0x0102_0304, hopLimit = 3, wantAck = true,
        )
        val expectedPacket = listOf(
            0x15, 0xFF, 0xFF, 0xFF, 0xFF,
            0x22, 0x06, 0x08, 0x01, 0x12, 0x02, 0x68, 0x69,
            0x35, 0x04, 0x03, 0x02, 0x01,
            0x48, 0x03,
            0x50, 0x01,
        )
        assertEquals(listOf(0x0A, expectedPacket.size) + expectedPacket, MeshPBToRadio.Packet(p).encode().u())
    }

    @Test fun toRadioRoundTrip() {
        val cases = listOf(
            MeshPBToRadio.WantConfigID(0xFFFF_FFFFL),
            MeshPBToRadio.Disconnect,
            MeshPBToRadio.Heartbeat(9),
            MeshPBToRadio.Packet(MeshPBPacket(from = 1, to = 2, channel = 1,
                decoded = MeshPBData(portnum = 1, payload = "x".toByteArray(), replyID = 5, emoji = 1),
                id = 77, hopLimit = 3, wantAck = true, priority = 70)),
        )
        for (c in cases) assertEquals(c, MeshPBToRadio.decode(c.encode()))
    }

    @Test fun packetRoundTripWithAllFields() {
        val p = MeshPBPacket(from = 0xA1B2_C3D4L, to = 0xFFFF_FFFFL, channel = 2,
            decoded = MeshPBData(portnum = 3, payload = bytes(1, 2, 3), wantResponse = true,
                dest = 9, source = 8, requestID = 7, replyID = 6, emoji = 1, bitfield = 1),
            id = 0xFFFF_FFFEL, rxTime = 1_760_000_000, rxSNR = -12.75f, hopLimit = 2, wantAck = true,
            priority = 64, rxRSSI = -118, viaMQTT = true, hopStart = 5)
        val back = MeshPBPacket.decode(p.encode())
        assertEquals(p, back)
        assertEquals(3L, back.hopsAway)
    }

    @Test fun encryptedPacketKeepsBytes() {
        val p = MeshPBPacket(from = 1, to = 2, encrypted = bytes(9, 9, 9), id = 3)
        val back = MeshPBPacket.decode(p.encode())
        assertNull(back.decoded)
        assertArrayEquals(bytes(9, 9, 9), back.encrypted)
    }

    @Test fun nodeInfoRoundTripAndPosition() {
        val n = MeshPBNodeInfo(
            num = 0xDEAD_BEEFL,
            user = MeshPBUser(id = "!deadbeef", longName = "Base Camp", shortName = "BC", hwModel = 43,
                isLicensed = false, role = 0, publicKey = ByteArray(32) { 1 }),
            position = MeshPBPosition(latitudeI = 101_234_560, longitudeI = -206_543_210, altitude = 150, time = 1),
            snr = 6.5f, lastHeard = 1_760_000_000,
            deviceMetrics = MeshPBDeviceMetrics(batteryLevel = 101, voltage = 4.2f),
            channel = 0, viaMQTT = false, hopsAway = 0,
        )
        val back = MeshPBNodeInfo.decode(n.encode())
        assertEquals(n, back)
        assertEquals(10.123456, back.position!!.latitude!!, 1e-7)
        assertEquals(-20.654321, back.position!!.longitude!!, 1e-7)
        assertTrue(back.position!!.hasFix)
        assertFalse(MeshPBPosition(latitudeI = 0, longitudeI = 0).hasFix)
        assertFalse(MeshPBPosition().hasFix)
    }

    @Test fun fromRadioVariantsRoundTrip() {
        val variants = listOf(
            MeshPBFromRadioVariant.MyInfo(MeshPBMyNodeInfo(myNodeNum = 42, rebootCount = 3, minAppVersion = 30200)),
            MeshPBFromRadioVariant.NodeInfo(MeshPBNodeInfo(num = 5, user = MeshPBUser(longName = "A"))),
            MeshPBFromRadioVariant.ConfigCompleteID(123_456),
            MeshPBFromRadioVariant.Rebooted,
            MeshPBFromRadioVariant.Channel(MeshPBChannel(index = 0, name = "LongFast", hasPSK = true, role = 1)),
            MeshPBFromRadioVariant.Channel(MeshPBChannel(index = 2, name = "Family", hasPSK = true, role = 2)),
            MeshPBFromRadioVariant.QueueStatus(MeshPBQueueStatus(res = -1, free = 0, maxlen = 16, meshPacketID = 99)),
            MeshPBFromRadioVariant.Metadata(MeshPBDeviceMetadata(firmwareVersion = "2.6.11.abc", role = 0, hwModel = 43, hasPKC = true)),
            MeshPBFromRadioVariant.LoraConfig(MeshPBLoRaConfig(modemPreset = 0, region = 1, hopLimit = 3)),
            MeshPBFromRadioVariant.ClientNotification("Rate limited"),
            MeshPBFromRadioVariant.LogRecord("boot"),
            MeshPBFromRadioVariant.Packet(MeshPBPacket(from = 1, to = 2, decoded = MeshPBData(portnum = 1, payload = "yo".toByteArray()), id = 3)),
        )
        for (v in variants) {
            val msg = MeshPBFromRadio(id = 7, variant = v)
            assertEquals("$v", msg, MeshPBFromRadio.decode(msg.encode()))
        }
    }

    @Test fun nonLoraConfigAndUnknownVariant() {
        assertEquals(MeshPBFromRadioVariant.OtherConfig,
            MeshPBFromRadio.decode(MeshPBFromRadio(variant = MeshPBFromRadioVariant.OtherConfig).encode()).variant)
        val w = MeshProtoWriter()
        w.writeUInt32(1, 4)
        w.writeMessage(9) { it.writeVarint(1, 1) }
        val r = MeshPBFromRadio.decode(w.data)
        assertEquals(4L, r.id)
        assertEquals(MeshPBFromRadioVariant.Unhandled(9), r.variant)
    }

    @Test fun routingDecode() {
        assertEquals(0L, MeshPBRouting.decode(MeshPBRouting(0).encode()).errorReason)
        assertEquals(listOf(0x18, 0x00), MeshPBRouting(0).encode().u())
        assertEquals(5L, MeshPBRouting.decode(MeshPBRouting(5).encode()).errorReason)
        assertEquals(0L, MeshPBRouting.decode(bytes()).errorReason)
        val w = MeshProtoWriter()
        w.writeMessage(1) { it.writeFixed32(1, 1) }
        assertNull(MeshPBRouting.decode(w.data).errorReason)
        assertEquals("Their radio didn't answer", MeshRoutingError.describe(5))
        assertEquals("Error 200", MeshRoutingError.describe(200))
    }

    @Test fun sosTextFormatting() {
        val t = MeshSOS.text(10.123456, -20.654321, "Trail Lead")
        assertTrue(t, t.contains("10.12346, -20.65432"))
        assertTrue(t.startsWith("SOS"))
        assertTrue(MeshText.byteCount(t) <= MeshConstants.MAX_TEXT_BYTES)
        assertTrue(MeshSOS.text(null, null, null).contains("Location unknown"))
        assertEquals("-0.00000", MeshSOS.format(-0.000004))
        assertEquals("12.00000", MeshSOS.format(12.0))
        val long = MeshSOS.text(1.0, 2.0, "N".repeat(400))
        assertTrue(MeshText.byteCount(long) <= MeshConstants.MAX_TEXT_BYTES)
    }

    @Test fun truncationRespectsCharacters() {
        val s = "📡".repeat(60)
        assertEquals(200, MeshText.byteCount(MeshText.truncated(s, 200)))
        assertEquals("é", MeshText.truncated("é" + "📡", 4))
        assertEquals(2, MeshText.byteCount("é"))
    }
}
