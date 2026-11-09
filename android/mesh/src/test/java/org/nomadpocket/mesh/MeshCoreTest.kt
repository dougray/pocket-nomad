// Port of MeshTests/MeshCoreTests.swift: MeshCore companion frames built byte
// for byte to the firmware's layout, and a full session against a scripted
// MeshCore radio. Coordinates are made up.
package org.nomadpocket.mesh

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nomadpocket.mesh.proto.*
import java.util.UUID

class MeshCoreCodecTest {
    @Test fun commandsMatchTheDocumentedBytes() {
        assertEquals(listOf(0x01, 0, 0, 0, 0, 0, 0, 0, 0x6d, 0x63, 0x63, 0x6c, 0x69), MeshCoreCommand.appStart("mccli").u())
        assertEquals(listOf(0x16, 0x03), MeshCoreCommand.deviceQuery().u())
        assertEquals(listOf(0x1F, 0x01), MeshCoreCommand.getChannel(1).u())
        assertEquals(listOf(0x03, 0x00, 0x01, 0xD2, 0x02, 0x96, 0x49, 0x48, 0x65, 0x6C, 0x6C, 0x6F),
            MeshCoreCommand.sendChannelText("Hello", 1, 1_234_567_890).u())
        assertEquals(listOf(0x02, 0, 0, 0xD2, 0x02, 0x96, 0x49, 1, 2, 3, 4, 5, 6, 0x68, 0x69),
            MeshCoreCommand.sendText("hi", bytes(1, 2, 3, 4, 5, 6, 7, 8), 1_234_567_890).u())
        assertEquals(listOf(0x07, 0x01), MeshCoreCommand.sendSelfAdvert().u())
        assertEquals(listOf(0x06, 0xD2, 0x02, 0x96, 0x49), MeshCoreCommand.setDeviceTime(1_234_567_890).u())
    }

    @Test fun decodesContact() {
        val frame = CoreFrames.contact(key = CoreFrames.key(0xAA), name = "Ana", type = 1, pathLen = 2)
        assertEquals(148, frame.size)
        val c = (MeshCoreFrame.decode(frame) as MeshCoreFrame.Contact).contact
        assertEquals("Ana", c.name)
        assertEquals(MeshCoreContactType.CHAT, c.type)
        assertEquals(2, c.pathLength)
        assertEquals(10.1234, c.latitude!!, 1e-6)
        assertEquals(-20.6543, c.longitude!!, 1e-6)
        assertEquals(0x4433_22AAL, c.nodeNum)

        val flood = (MeshCoreFrame.decode(CoreFrames.contact(key = CoreFrames.key(1), name = "R", type = 2, pathLen = 0xFF, lat = 0, lon = 0))
            as MeshCoreFrame.Contact).contact
        assertNull(flood.pathLength)
        assertNull(flood.latitude)
        assertEquals(MeshCoreContactType.REPEATER, flood.type)
    }

    @Test fun decodesSelfAndDeviceInfo() {
        val me = (MeshCoreFrame.decode(CoreFrames.selfInfo(CoreFrames.key(0x11), "Base Camp")) as MeshCoreFrame.SelfInfo).info
        assertEquals("Base Camp", me.name)
        assertEquals(910.525, me.frequencyMHz, 1e-9)
        assertEquals(62.5, me.bandwidthKHz, 1e-9)
        assertEquals(7, me.spreadingFactor)

        val info = (MeshCoreFrame.decode(CoreFrames.deviceInfo(8)) as MeshCoreFrame.DeviceInfo).info
        assertEquals(8, info.maxChannels)
        assertEquals(100, info.maxContacts)
        assertEquals("Heltec V4", info.model)
        assertEquals("v1.12.0", info.version)
    }

    @Test fun decodesMessages() {
        val ch = (MeshCoreFrame.decode(CoreFrames.channelMessageV3(0, "Ana: at the bridge", 1_790_000_100)) as MeshCoreFrame.ChannelMessage).message
        assertEquals("Ana", ch.senderName)
        assertEquals("at the bridge", ch.text)
        assertEquals(5.5f, ch.snr)
        assertEquals(1_790_000_100L, ch.timestamp)

        val dm = (MeshCoreFrame.decode(CoreFrames.directMessageV3(listOf(1, 2, 3, 4, 5, 6), "ok", 7, signed = true)) as MeshCoreFrame.DirectMessage).message
        assertEquals("ok", dm.text)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), dm.senderKeyPrefix.array.u())
        assertEquals(5f, dm.snr)

        // Old (pre-V3) channel format.
        val old = CoreFrames.b(listOf(8, 2, 0, 0) + CoreFrames.le(9L) + "hello".toByteArray().map { it.toInt() })
        val o = (MeshCoreFrame.decode(old) as MeshCoreFrame.ChannelMessage).message
        assertEquals(2, o.channel)
        assertNull(o.senderName)
        assertEquals("hello", o.text)
    }

    @Test fun decodesAcksAndPushes() {
        assertEquals(MeshCoreFrame.Sent(false, 0xDEAD_BEEFL, 5000), MeshCoreFrame.decode(CoreFrames.sent(0xDEAD_BEEFL)))
        assertEquals(MeshCoreFrame.SendConfirmed(7, 800), MeshCoreFrame.decode(CoreFrames.sendConfirmed(7)))
        assertEquals(MeshCoreFrame.MessagesWaiting, MeshCoreFrame.decode(bytes(0x83)))
        assertEquals(MeshCoreFrame.NoMoreMessages, MeshCoreFrame.decode(bytes(0x0A)))
        assertEquals(MeshCoreFrame.Error(2), MeshCoreFrame.decode(bytes(0x01, 0x02)))
        assertEquals(MeshCoreFrame.Other(0x8F), MeshCoreFrame.decode(bytes(0x8F, 1, 2, 3)))
    }

    @Test fun rawPacketNamesWhoWeHeard() {
        // Flood text that passed two repeaters: the last one is in range of us.
        val relayed = MeshCoreRawPacket.parse(CoreFrames.b(CoreFrames.packet(1, 5, listOf(listOf(0x3A), listOf(0xC7)), listOf(1, 2, 3))))!!
        assertEquals(MeshCoreRawPacket.HeardFrom.Repeater(Bytes(byteArrayOf(0xC7.toByte()))), relayed.heardFrom)
        assertEquals(5, relayed.payloadType)
        // Transport codes are skipped; two-byte hashes are read whole.
        val transport = MeshCoreRawPacket.parse(CoreFrames.b(CoreFrames.packet(0, 2, listOf(listOf(1, 2), listOf(0xAB, 0xCD)), listOf(7))))!!
        assertEquals(MeshCoreRawPacket.HeardFrom.Repeater(Bytes(byteArrayOf(0xAB.toByte(), 0xCD.toByte()))), transport.heardFrom)
        // An advert straight from its sender names it in full.
        val key = CoreFrames.key(0x5E)
        val advert = MeshCoreRawPacket.parse(CoreFrames.b(CoreFrames.packet(1, 4, payload = key + listOf(0, 0, 0, 0))))!!
        assertEquals(MeshCoreRawPacket.HeardFrom.Node(Bytes(CoreFrames.b(key))), advert.heardFrom)
        // Direct routes drop hashes as they go, so the sender is unknown.
        assertNull(MeshCoreRawPacket.parse(CoreFrames.b(CoreFrames.packet(2, 2, listOf(listOf(0x44)), listOf(1))))!!.heardFrom)
        assertNull(MeshCoreRawPacket.parse(bytes(0x05, 0x03, 0x01)))
    }

    @Test fun receiveLogTraceAndTraceCommand() {
        val log = (MeshCoreFrame.decode(CoreFrames.rxLog(6.25f, CoreFrames.packet(1, 5, listOf(listOf(0xC7)), listOf(1)), rssi = -97)) as MeshCoreFrame.RxLog).log
        assertEquals(6.25f, log.snr)
        assertEquals(-97, log.rssi)
        assertEquals(MeshCoreRawPacket.HeardFrom.Repeater(Bytes(byteArrayOf(0xC7.toByte()))), log.packet?.heardFrom)
        assertEquals(MeshCoreFrame.TraceData(0x1234_5678L, -3f), MeshCoreFrame.decode(CoreFrames.traceData(0x1234_5678L, listOf(0xC7))))
        assertEquals(listOf(36, 4, 3, 2, 1, 0, 0, 0, 0, 0, 0xC7),
            MeshCoreCommand.sendTrace(0x0102_0304L, byteArrayOf(0xC7.toByte())).map { it.toInt() and 0xFF })
        assertEquals("flags carry the hash size minus one", 1, MeshCoreCommand.sendTrace(1, byteArrayOf(1, 2))[9].toInt())
    }

    @Test fun truncatedFramesAreRejected() {
        val contact = CoreFrames.contact(key = CoreFrames.key(1), name = "A")
        assertNull(MeshCoreFrame.decode(contact.copyOfRange(0, 100)))
        assertNull(MeshCoreFrame.decode(bytes(0x82, 1, 2)))
        assertNull(MeshCoreFrame.decode(bytes()))
    }

    @Test fun senderSplitIgnoresOrdinaryColons() {
        assertEquals("Ana", MeshCoreFrame.splitSender("Ana: hi").first)
        assertNull(MeshCoreFrame.splitSender("no sender here").first)
        assertNull(MeshCoreFrame.splitSender("A very long sentence that happens to contain: a colon later").first)
    }
}

class MeshCoreServiceTest {
    private val h = ServiceHarness()

    @After fun tearDown() = h.cleanup()

    private fun connected(): Triple<MeshService, FakeMeshTransport, FakeMeshCoreRadio> {
        val fake = FakeMeshTransport()
        val radio = FakeMeshCoreRadio(fake)
        radio.waiting.addLast(CoreFrames.channelMessageV3(0, "Ana: anyone on?", 1_790_000_000))
        val svc = h.service(fake)
        svc.start()
        svc.connect(MeshDiscoveredDevice(UUID.randomUUID().toString(), "MeshCore-Base", -60, 0, Firmware.MESHCORE))
        fake.emit(MeshTransportEvent.Ready(fake.connectedID!!, "MeshCore-Base", Firmware.MESHCORE))
        radio.run()
        return Triple(svc, fake, radio)
    }

    @Test fun handshakeLoadsIdentityContactsChannelsAndQueuedMessages() {
        val (svc, fake, _) = connected()
        assertEquals(MeshConnectionState.Connected("MeshCore-Base"), svc.connection)
        assertEquals(Firmware.MESHCORE, svc.firmware)
        assertEquals(133, svc.maxTextBytes)
        assertEquals("Base Camp", svc.myNode?.longName)
        assertEquals("910.525 MHz", svc.radio.regionName)
        assertEquals("MeshCore v1.12.0", svc.radio.firmwareVersion)
        assertEquals("Public", svc.radio.primaryChannelName)
        assertEquals(listOf("Ana"), svc.sortedNodes.map { it.longName })
        assertEquals(2L, svc.sortedNodes.first().hopsAway)
        assertEquals(1, fake.writes.first()[0].toInt())
        val thread = svc.messages(MeshConversation.Channel(0))
        assertEquals(listOf("anyone on?"), thread.map { it.text })
        assertEquals("Ana", thread.first().senderName)
        assertEquals("sender name matched to the contact", svc.sortedNodes.first().num, thread.first().from)
    }

    @Test fun directMessageIsAddressedByKeyAndConfirmed() {
        val (svc, fake, radio) = connected()
        val ana = svc.sortedNodes.first()
        assertTrue(svc.send("meet at the bridge", MeshConversation.Direct(ana.num)))
        radio.run()
        val cmd = fake.writes.last()
        assertEquals(2, cmd[0].toInt())
        assertEquals(radio.ana.take(6), cmd.copyOfRange(7, 13).u())
        assertEquals("meet at the bridge", String(cmd.copyOfRange(13, cmd.size)))

        var sent = svc.messages(MeshConversation.Direct(ana.num)).last()
        assertEquals(MeshDeliveryState.Sent, sent.delivery)
        fake.emit(MeshTransportEvent.Frame(CoreFrames.sendConfirmed(sent.packetID)))
        sent = svc.messages(MeshConversation.Direct(ana.num)).last()
        assertEquals(MeshDeliveryState.Delivered, sent.delivery)
    }

    @Test fun channelSendAndTextLimit() {
        val (svc, fake, radio) = connected()
        assertTrue(svc.send("all good here", MeshConversation.Channel(0)))
        radio.run()
        assertEquals(3, fake.writes.last()[0].toInt())
        assertFalse("MeshCore's limit is 133 bytes", svc.send("x".repeat(134), MeshConversation.Channel(0)))
    }

    @Test fun messagesWaitingPushTriggersSync() {
        val (svc, fake, radio) = connected()
        radio.waiting.addLast(CoreFrames.directMessageV3(radio.ana.take(6), "you ok?", 1_790_000_500))
        fake.emit(MeshTransportEvent.Frame(bytes(0x83)))
        radio.run()
        val ana = svc.sortedNodes.first()
        assertEquals(listOf("you ok?"), svc.messages(MeshConversation.Direct(ana.num)).map { it.text })
        radio.waiting.addLast(CoreFrames.directMessageV3(radio.ana.take(6), "you ok?", 1_790_000_500))
        fake.emit(MeshTransportEvent.Frame(bytes(0x83)))
        radio.run()
        assertEquals(1, svc.messages(MeshConversation.Direct(ana.num)).size)
    }

    @Test fun sosFitsMeshCoreLimit() {
        val (svc, _, _) = connected()
        val text = runBlocking { svc.sendSOS(false) }!!
        assertTrue(MeshText.byteCount(text) <= 133)
        assertTrue(text.startsWith("SOS"))
    }

    @Test fun meshCorePingNeedsATargetAndIsConfirmed() {
        val (svc, fake, radio) = connected()
        assertFalse("MeshCore can only ping a chosen radio", svc.ping(null) {})
        val ana = svc.sortedNodes.first()
        var outcome: PingOutcome? = null
        val ack = radio.nextAck
        assertTrue(svc.ping(ana.num) { outcome = it })
        radio.run()
        val cmd = fake.writes.last()
        assertEquals(2, cmd[0].toInt())
        assertEquals(MeshService.PING_TEXT, String(cmd.copyOfRange(13, cmd.size)))
        assertTrue("pings are not added to the chat", svc.messages(MeshConversation.Direct(ana.num)).isEmpty())
        fake.emit(MeshTransportEvent.Frame(CoreFrames.sendConfirmed(ack)))
        assertEquals(PingOutcome.REACHED, outcome)
    }

    @Test fun everyPacketHeardIsACoveragePointAndARepeaterInRange() {
        val (svc, fake, _) = connected()
        val heard = mutableListOf<MeshHeard>()
        svc.onHeard = { heard += it }
        fake.emit(MeshTransportEvent.Frame(CoreFrames.rxLog(7.5f, CoreFrames.packet(1, 5, listOf(listOf(0x3A), listOf(0xC7)), listOf(1, 2, 3)))))
        val c7 = MeshCoreContact.nodeNumForHash(byteArrayOf(0xC7.toByte()))
        assertEquals(MeshHeard(c7, 7.5f, 2, Firmware.MESHCORE), heard.single())
        assertEquals("the repeater just heard is the ping target", c7, svc.autoPingTarget())
        // A hash that starts a known contact's key is still recorded by its hash: many radios
        // share a hash, so HeardResolver picks one by distance when the map is drawn.
        fake.emit(MeshTransportEvent.Frame(CoreFrames.rxLog(2f, CoreFrames.packet(1, 5, listOf(listOf(0xAA)), listOf(1)))))
        val ana = svc.sortedNodes.first { it.longName == "Ana" }
        assertEquals(MeshCoreContact.nodeNumForHash(byteArrayOf(0xAA.toByte())), heard.last().node)
        assertTrue(ana.num != heard.last().node)
        fake.emit(MeshTransportEvent.Frame(bytes(0x88, 0, 0, 0x05)))
        assertEquals(2, heard.size)
    }

    @Test fun repeaterPingIsATraceAnsweredByTraceData() {
        val (svc, fake, radio) = connected()
        fake.emit(MeshTransportEvent.Frame(CoreFrames.rxLog(5f, CoreFrames.packet(1, 5, listOf(listOf(0xC7)), listOf(1)))))
        val c7 = svc.autoPingTarget()!!
        var outcome: PingOutcome? = null
        assertTrue(svc.ping(c7) { outcome = it })
        radio.run()
        val cmd = fake.writes.last()
        assertEquals(36, cmd[0].toInt())
        assertEquals(0xC7, cmd.last().toInt() and 0xFF)
        val tag = (0 until 4).fold(0L) { acc, i -> acc or ((cmd[1 + i].toLong() and 0xFF) shl (8 * i)) }
        fake.emit(MeshTransportEvent.Frame(CoreFrames.traceData(tag xor 1, listOf(0xC7))))
        assertNull("someone else's trace", outcome)
        fake.emit(MeshTransportEvent.Frame(CoreFrames.traceData(tag, listOf(0xC7))))
        assertEquals(PingOutcome.REACHED, outcome)
    }
}
