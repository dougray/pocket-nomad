// Port of MeshTests/MeshServiceTests.swift: drives MeshService through a fake
// radio (handshake, chat, ACKs, persistence, pings). Coordinates are made up.
package org.nomadpocket.mesh

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.nomadpocket.mesh.proto.*
import java.util.UUID

class MeshServiceTest {
    private val me = 0x1111_1111L
    private val alice = 0xAAAA_0001L
    private val bob = 0xBBBB_0002L
    private val h = ServiceHarness()

    @After fun tearDown() = h.cleanup()

    private fun nowSec() = System.currentTimeMillis() / 1000

    private fun device(name: String = "Test Radio") =
        MeshDiscoveredDevice(UUID.randomUUID().toString(), name, -60, System.currentTimeMillis())

    /** Connects and completes the full startup handshake. */
    private fun connected(): Pair<MeshService, FakeMeshTransport> {
        val fake = FakeMeshTransport()
        val svc = h.service(fake)
        svc.start()
        svc.connect(device())
        fake.becomeReady()
        val cfg = fake.lastWantConfigID()!!
        fake.enqueue(MeshPBFromRadioVariant.MyInfo(MeshPBMyNodeInfo(myNodeNum = me)))
        fake.enqueue(MeshPBFromRadioVariant.Metadata(MeshPBDeviceMetadata(firmwareVersion = "2.6.0")))
        fake.enqueue(MeshPBFromRadioVariant.NodeInfo(MeshPBNodeInfo(num = me, user = MeshPBUser(id = "!11111111", longName = "My Radio", shortName = "ME"))))
        fake.enqueue(MeshPBFromRadioVariant.NodeInfo(MeshPBNodeInfo(num = alice, user = MeshPBUser(longName = "Alice", shortName = "AL"),
            position = MeshPBPosition(latitudeI = 101_234_560, longitudeI = -206_543_210),
            snr = 5.5f, lastHeard = nowSec() - 60, hopsAway = 1)))
        fake.enqueue(MeshPBFromRadioVariant.NodeInfo(MeshPBNodeInfo(num = bob, user = MeshPBUser(longName = "Bob", shortName = "BO"),
            lastHeard = nowSec() - 3600)))
        fake.enqueue(MeshPBFromRadioVariant.Channel(MeshPBChannel(index = 0, name = "LongFast", hasPSK = true, role = 1)))
        fake.enqueue(MeshPBFromRadioVariant.Channel(MeshPBChannel(index = 1, name = "", role = 0)))
        fake.enqueue(MeshPBFromRadioVariant.LoraConfig(MeshPBLoRaConfig(modemPreset = 0, region = 1, hopLimit = 4)))
        fake.enqueue(MeshPBFromRadioVariant.OtherConfig)
        fake.enqueue(MeshPBFromRadioVariant.Unhandled(9))
        fake.enqueue(MeshPBFromRadioVariant.ConfigCompleteID(cfg))
        fake.pump()
        return svc to fake
    }

    private fun incoming(from: Long, to: Long, text: String, id: Long, channel: Long = 0) =
        MeshPBFromRadioVariant.Packet(MeshPBPacket(from = from, to = to, channel = channel,
            decoded = MeshPBData(portnum = MeshPort.TEXT_MESSAGE, payload = text.toByteArray()),
            id = id, rxTime = nowSec(), rxSNR = 4.25f, hopLimit = 2, hopStart = 3))

    private fun routing(from: Long, requestID: Long, error: Long) =
        MeshPBFromRadioVariant.Packet(MeshPBPacket(from = from, to = me,
            decoded = MeshPBData(portnum = MeshPort.ROUTING, payload = MeshPBRouting(error).encode(), requestID = requestID),
            id = MeshService.newPacketID()))

    private fun FakeMeshTransport.deliver(vararg v: MeshPBFromRadioVariant) {
        v.forEach { enqueue(it) }
        emit(MeshTransportEvent.FromNumChanged)
        pump()
    }

    // MARK: Handshake

    @Test fun handshakeCollectsNodesChannelsAndSettings() {
        val (svc, _) = connected()
        assertEquals(MeshConnectionState.Connected("Test Radio"), svc.connection)
        assertEquals(me, svc.myNodeNum)
        assertEquals("2.6.0", svc.radio.firmwareVersion)
        assertEquals("LongFast", svc.radio.primaryChannelName)
        assertNull(svc.radio.channelNames[1])
        assertEquals(1L, svc.radio.region)
        assertEquals(4L, svc.radio.hopLimit)
        assertEquals("My Radio", svc.myNode?.longName)
        assertEquals(listOf(alice, bob), svc.sortedNodes.map { it.num })
        val a = svc.nodes[alice]!!
        assertEquals("AL", a.shortName)
        assertEquals(1L, a.hopsAway)
        assertEquals(5.5f, a.snr)
        assertEquals(10.123456, a.latitude!!, 1e-6)
        assertNull(svc.notice)
    }

    @Test fun handshakeIgnoresMismatchedConfigCompleteID() {
        val fake = FakeMeshTransport()
        val svc = h.service(fake)
        svc.start()
        svc.connect(device("R"))
        fake.becomeReady()
        assertEquals(MeshConnectionState.Syncing("Test Radio"), svc.connection)
        val cfg = fake.lastWantConfigID()!!
        fake.enqueue(MeshPBFromRadioVariant.MyInfo(MeshPBMyNodeInfo(myNodeNum = me)))
        fake.enqueue(MeshPBFromRadioVariant.ConfigCompleteID((cfg + 1) and 0xFFFFFFFFL))
        fake.pump()
        assertEquals(MeshConnectionState.Syncing("Test Radio"), svc.connection)
        fake.deliver(MeshPBFromRadioVariant.ConfigCompleteID(cfg))
        assertEquals(MeshConnectionState.Connected("Test Radio"), svc.connection)
    }

    @Test fun regionUnsetShowsNotice() {
        val fake = FakeMeshTransport()
        val svc = h.service(fake)
        svc.start()
        svc.connect(device("R"))
        fake.becomeReady()
        val cfg = fake.lastWantConfigID()!!
        fake.enqueue(MeshPBFromRadioVariant.LoraConfig(MeshPBLoRaConfig(region = 0)))
        fake.enqueue(MeshPBFromRadioVariant.ConfigCompleteID(cfg))
        fake.pump()
        assertTrue(svc.notice?.contains("US 915") == true)
    }

    @Test fun rebootTriggersNewHandshake() {
        val (svc, fake) = connected()
        val first = fake.lastWantConfigID()
        fake.deliver(MeshPBFromRadioVariant.Rebooted)
        assertEquals(MeshConnectionState.Syncing("Test Radio"), svc.connection)
        assertNotEquals(first, fake.lastWantConfigID())
    }

    @Test fun corruptPacketIsSkippedAndReadingContinues() {
        val (svc, fake) = connected()
        fake.queue.addLast(bytes(0x12, 0x7F, 0x00)) // length past end
        fake.deliver(incoming(alice, MeshConstants.BROADCAST_NUM, "after junk", 50))
        assertEquals(listOf("after junk"), svc.messages(MeshConversation.Channel(0)).map { it.text })
    }

    // MARK: Incoming messages

    @Test fun incomingBroadcastAndDuplicate() {
        val (svc, fake) = connected()
        fake.deliver(incoming(alice, MeshConstants.BROADCAST_NUM, "hello mesh", 100),
            incoming(alice, MeshConstants.BROADCAST_NUM, "hello mesh", 100))
        val thread = svc.messages(MeshConversation.Channel(0))
        assertEquals(1, thread.size)
        assertEquals("hello mesh", thread.first().text)
        assertEquals(MeshDeliveryState.Received, thread.first().delivery)
        assertEquals(1L, thread.first().hopsAway)
        assertEquals(1, svc.unreadCount(MeshConversation.Channel(0)))
        svc.markRead(MeshConversation.Channel(0))
        assertEquals(0, svc.unreadCount(MeshConversation.Channel(0)))
    }

    @Test fun incomingDirectMessageAndOthersTrafficIgnored() {
        val (svc, fake) = connected()
        fake.deliver(incoming(bob, me, "just you", 200), incoming(bob, alice, "not for me", 201),
            MeshPBFromRadioVariant.Packet(MeshPBPacket(from = bob, to = me, encrypted = bytes(1, 2), id = 202)))
        assertEquals(listOf("just you"), svc.messages(MeshConversation.Direct(bob)).map { it.text })
        assertEquals(listOf(bob), svc.directThreads.map { it.first.num })
        assertTrue(svc.messages(MeshConversation.Channel(0)).isEmpty())
        assertEquals(1, svc.messages.size)
    }

    @Test fun positionAndNodeInfoPacketsUpdateNodes() {
        val (svc, fake) = connected()
        val carol = 0xCCCC_0003L
        fake.deliver(
            MeshPBFromRadioVariant.Packet(MeshPBPacket(from = carol, to = MeshConstants.BROADCAST_NUM,
                decoded = MeshPBData(portnum = MeshPort.NODE_INFO, payload = MeshPBUser(longName = "Carol", shortName = "CA").encode()),
                id = 300, rxSNR = -3f)),
            MeshPBFromRadioVariant.Packet(MeshPBPacket(from = carol, to = MeshConstants.BROADCAST_NUM,
                decoded = MeshPBData(portnum = MeshPort.POSITION, payload = MeshPBPosition(latitudeI = -123_456_000, longitudeI = 1_234_567_000).encode()),
                id = 301)),
        )
        val c = svc.nodes[carol]!!
        assertEquals("Carol", c.longName)
        assertEquals(-3f, c.snr)
        assertEquals(-12.3456, c.latitude!!, 1e-6)
        assertEquals(carol, svc.sortedNodes.first().num)
    }

    // MARK: Sending and delivery

    @Test fun sendDirectMessageThenDeliveredAck() {
        val (svc, fake) = connected()
        assertTrue(svc.send("  meet at the trailhead  ", MeshConversation.Direct(alice)))
        val p = fake.lastSentPacket()!!
        assertEquals(alice, p.to)
        assertEquals(0L, p.channel)
        assertTrue(p.wantAck)
        assertEquals(4L, p.hopLimit)
        assertNotEquals(0L, p.id)
        assertEquals(1L, p.decoded?.portnum)
        assertEquals("meet at the trailhead", String(p.decoded!!.payload))

        assertEquals(MeshDeliveryState.Sent, svc.messages(MeshConversation.Direct(alice)).last().delivery)
        fake.deliver(routing(me, p.id, 0))
        assertEquals(MeshDeliveryState.HeardByMesh, svc.messages(MeshConversation.Direct(alice)).last().delivery)
        fake.deliver(routing(alice, p.id, 0))
        assertEquals(MeshDeliveryState.Delivered, svc.messages(MeshConversation.Direct(alice)).last().delivery)
        // A late NAK does not undo a confirmed delivery.
        fake.deliver(routing(me, p.id, 5))
        assertEquals(MeshDeliveryState.Delivered, svc.messages(MeshConversation.Direct(alice)).last().delivery)
    }

    @Test fun broadcastAckAndNak() {
        val (svc, fake) = connected()
        assertTrue(svc.send("one", MeshConversation.Channel(0)))
        val first = fake.lastSentPacket()!!
        assertEquals(MeshConstants.BROADCAST_NUM, first.to)
        assertTrue(svc.send("two", MeshConversation.Channel(0)))
        val second = fake.lastSentPacket()!!
        fake.deliver(routing(me, first.id, 0), routing(me, second.id, MeshRoutingError.MAX_RETRANSMIT))
        assertEquals(listOf(MeshDeliveryState.HeardByMesh, MeshDeliveryState.NotRepeated),
            svc.messages(MeshConversation.Channel(0)).map { it.delivery })

        assertTrue(svc.send("you there?", MeshConversation.Direct(alice)))
        val dm = fake.lastSentPacket()!!
        fake.deliver(routing(me, dm.id, MeshRoutingError.MAX_RETRANSMIT))
        assertEquals(MeshDeliveryState.Failed("Their radio didn't answer"), svc.messages(MeshConversation.Direct(alice)).last().delivery)
    }

    @Test fun queueFullMarksFailed() {
        val (svc, fake) = connected()
        assertTrue(svc.send("busy?", MeshConversation.Channel(0)))
        val p = fake.lastSentPacket()!!
        fake.enqueue(MeshPBFromRadioVariant.QueueStatus(MeshPBQueueStatus(res = 1, free = 0, maxlen = 16, meshPacketID = p.id)))
        fake.pump()
        assertEquals(MeshDeliveryState.Failed("Radio is busy, try again"), svc.messages(MeshConversation.Channel(0)).last().delivery)
    }

    @Test fun sendRejectsTooLongEmptyAndDisconnected() {
        val (svc, fake) = connected()
        val before = fake.writes.size
        assertFalse(svc.send("a".repeat(201), MeshConversation.Channel(0)))
        assertTrue(svc.send("a".repeat(200), MeshConversation.Channel(0)))
        assertFalse(svc.send("   ", MeshConversation.Channel(0)))
        assertFalse(svc.send("📡".repeat(51), MeshConversation.Channel(0))) // 204 bytes
        assertEquals(before + 1, fake.writes.size)
        svc.disconnect()
        assertEquals(MeshConnectionState.Idle, svc.connection)
        assertFalse(svc.send("hi", MeshConversation.Channel(0)))
    }

    @Test fun sosWithoutLocationGoesToPrimaryChannel() {
        val (svc, fake) = connected()
        val sent = runBlocking { svc.sendSOS(false) }
        assertTrue(sent!!.startsWith("SOS"))
        assertTrue(sent.contains("My Radio"))
        val p = fake.lastSentPacket()!!
        assertEquals(MeshConstants.BROADCAST_NUM, p.to)
        assertEquals(0L, p.channel)
    }

    @Test fun sosWithLocationIncludesTheFix() {
        val fake = FakeMeshTransport()
        val svc = MeshService(fake, MeshStore(h.dir), h.prefs, h.scope,
            location = { Fix(10.123456, -20.654321, 5.0, System.currentTimeMillis()) })
        svc.start()
        svc.connect(device())
        fake.becomeReady()
        fake.deliver(MeshPBFromRadioVariant.ConfigCompleteID(fake.lastWantConfigID()!!))
        val sent = runBlocking { svc.sendSOS(true) }!!
        assertTrue(sent, sent.contains("10.12346, -20.65432"))
    }

    // MARK: Persistence and reconnect

    @Test fun persistenceRoundTripAndAutoReconnect() {
        val (svc, fake) = connected()
        fake.deliver(incoming(alice, MeshConstants.BROADCAST_NUM, "saved?", 400))
        svc.send("reply", MeshConversation.Direct(alice))
        svc.saveNow()

        val fake2 = FakeMeshTransport()
        val svc2 = h.service(fake2)
        assertEquals(listOf("saved?", "reply"), svc2.messages.map { it.text })
        assertEquals("Alice", svc2.nodes[alice]?.longName)
        assertEquals(me, svc2.radio.myNodeNum)
        assertEquals("Test Radio", svc2.lastDeviceName)
        svc2.start()
        assertEquals(svc.lastDeviceID, fake2.connectedID)
    }

    @Test fun disconnectStopsAutoReconnect() {
        val (svc, _) = connected()
        svc.disconnect()
        val fake2 = FakeMeshTransport()
        val svc2 = h.service(fake2)
        svc2.start()
        assertNull(fake2.connectedID)
        assertNotNull(svc2.lastDeviceID)
    }

    @Test fun historyIsCapped() {
        val many = (0 until MeshStore.MAX_MESSAGES + 50).map {
            MeshMessage(packetID = it + 1L, from = alice, to = MeshConstants.BROADCAST_NUM, channel = 0,
                text = "$it", dateMillis = 0, isOutgoing = false, delivery = MeshDeliveryState.Received)
        }
        val capped = MeshStore.capped(many)
        assertEquals(MeshStore.MAX_MESSAGES, capped.size)
        assertEquals("${MeshStore.MAX_MESSAGES + 49}", capped.last().text)
    }

    @Test fun transportReconnectingState() {
        val (svc, fake) = connected()
        fake.emit(MeshTransportEvent.Disconnected("x", "Link lost", true))
        assertEquals(MeshConnectionState.Reconnecting("Test Radio"), svc.connection)
        assertFalse(svc.canSend)
        fake.emit(MeshTransportEvent.PairingProblem("PIN"))
        assertEquals("PIN", svc.pairingHint)
        fake.becomeReady()
        assertNull(svc.pairingHint)
        assertEquals(MeshConnectionState.Syncing("Test Radio"), svc.connection)
    }

    // MARK: Drive-test pings

    @Test fun broadcastPingIsSilentOneHopAndRelayedCounts() {
        val (svc, fake) = connected()
        var outcome: PingOutcome? = null
        assertTrue(svc.ping(null) { outcome = it })
        val p = fake.lastSentPacket()!!
        assertEquals(MeshConstants.BROADCAST_NUM, p.to)
        assertEquals("only neighbours repeat it", 1L, p.hopLimit)
        assertTrue(p.wantAck)
        assertEquals("not a text message, so it never shows as chat", MeshService.PING_PORT, p.decoded?.portnum)
        assertEquals(256L, MeshService.PING_PORT)
        assertTrue(svc.messages(MeshConversation.Channel(0)).isEmpty())
        fake.deliver(routing(me, p.id, 0))
        assertEquals(PingOutcome.RELAYED, outcome)
    }

    @Test fun targetedPingWaitsForTheTargetAndFallsBackToRelayed() {
        val (svc, fake) = connected()
        var first: PingOutcome? = null
        assertTrue(svc.ping(alice) { first = it })
        val p1 = fake.lastSentPacket()!!
        assertEquals(alice, p1.to)
        assertEquals(4L, p1.hopLimit)
        fake.deliver(routing(me, p1.id, 0))
        assertNull("a relay alone isn't an answer yet", first)
        fake.deliver(routing(alice, p1.id, 0))
        assertEquals(PingOutcome.REACHED, first)

        var second: PingOutcome? = null
        assertTrue(svc.ping(alice) { second = it })
        val p2 = fake.lastSentPacket()!!
        fake.deliver(routing(me, p2.id, 0), routing(me, p2.id, 5))
        assertEquals(PingOutcome.RELAYED, second)
    }

    @Test fun pingTimesOutWithNone() {
        val (svc, _) = connected()
        var outcome: PingOutcome? = null
        assertTrue(svc.ping(null) { outcome = it })
        h.scope.testScheduler.advanceTimeBy(44_000)
        assertNull(outcome)
        h.scope.testScheduler.advanceTimeBy(2_000)
        assertEquals(PingOutcome.NONE, outcome)
    }

    @Test fun pingNeedsAConnectedRadio() {
        val svc = h.service(FakeMeshTransport())
        assertFalse(svc.ping(null) { fail("no result without a radio") })
    }
}
