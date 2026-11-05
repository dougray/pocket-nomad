// Radio settings: MeshCore set-params / tx-power / reboot frames and OK/ERR
// replies (layout from MeshCore companion-v1.18.0 MyMesh.cpp), Meshtastic
// AdminMessage get/set with the session passkey (golden bytes from the field
// numbers in meshtastic/protobufs admin.proto and config.proto), the
// "match radios near you" choice, validation and the radio card's hint.
// Nothing here is checked against a real radio.
package org.nomadpocket.mesh

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nomadpocket.mesh.proto.*
import java.util.UUID

class MeshCoreRadioCommandTest {
    @Test fun setRadioParamsMatchesTheFirmwareLayout() {
        // [11][freq u32 LE = 910525][bw u32 LE = 62500][sf][cr]
        assertEquals(listOf(0x0B, 0xBD, 0xE4, 0x0D, 0x00, 0x24, 0xF4, 0x00, 0x00, 0x07, 0x05),
            MeshCoreCommand.setRadioParams(910_525, 62_500, 7, 5).u())
        val p = MeshCoreRadioParams(869.618, 62.5, 8, 8)
        assertEquals(listOf(0x0B) + CoreFrames.le(869_618L) + CoreFrames.le(62_500L) + listOf(8, 8),
            MeshCoreCommand.setRadioParams(p.frequencyKHz, p.bandwidthHz, p.spreadingFactor, p.codingRate).u())
        // 31.25 kHz and 7.8 kHz go out in Hz.
        assertEquals(31_250L, MeshCoreRadioParams(915.0, 31.25, 7, 5).bandwidthHz)
        assertEquals(7_800L, MeshCoreRadioParams(915.0, 7.8, 7, 5).bandwidthHz)
    }

    @Test fun txPowerAndReboot() {
        assertEquals(listOf(0x0C, 0x16), MeshCoreCommand.setTxPower(22).u())
        assertEquals(listOf(0x0C, 0xF7), MeshCoreCommand.setTxPower(-9).u())
        assertEquals(listOf(0x13, 0x72, 0x65, 0x62, 0x6F, 0x6F, 0x74), MeshCoreCommand.reboot().u())
    }

    @Test fun okAndErrReplies() {
        assertEquals(MeshCoreFrame.Ok(null), MeshCoreFrame.decode(bytes(0)))
        assertEquals(MeshCoreFrame.Error(6), MeshCoreFrame.decode(bytes(1, 6)))
        assertEquals(MeshCoreFrame.Error(1), MeshCoreFrame.decode(bytes(1, 1)))
    }

    @Test fun selfInfoCarriesTxPower() {
        val f = MeshCoreFrame.decode(CoreFrames.selfInfo(CoreFrames.key(1), "X", 869_618, 62_500, 8, 5, txPower = 10, maxTx = 22))
        val info = (f as MeshCoreFrame.SelfInfo).info
        assertEquals(10, info.txPowerDbm)
        assertEquals(22, info.maxTxPowerDbm)
        assertEquals(869.618, info.frequencyMHz, 1e-9)
        assertEquals(8, info.spreadingFactor)
    }
}

class MeshCoreRadioSettingsServiceTest {
    private val h = ServiceHarness()
    @After fun tearDown() = h.cleanup()

    private fun connected(): Triple<MeshService, FakeMeshTransport, FakeMeshCoreRadio> {
        val fake = FakeMeshTransport()
        val radio = FakeMeshCoreRadio(fake)
        radio.freqKHz = 869_618; radio.sf = 8
        val svc = h.service(fake)
        svc.start()
        svc.connect(MeshDiscoveredDevice(UUID.randomUUID().toString(), "MeshCore-Test", -60, 0, Firmware.MESHCORE))
        fake.emit(MeshTransportEvent.Ready(fake.connectedID!!, "MeshCore-Test", Firmware.MESHCORE))
        radio.run()
        return Triple(svc, fake, radio)
    }

    @Test fun handshakeReportsCurrentParams() {
        val (svc, _, _) = connected()
        assertTrue(svc.radio.meshCoreParams!!.sameAs(MeshCoreRadioParams.FIRMWARE_DEFAULT))
        assertEquals(20, svc.radio.txPowerDbm)
        assertEquals(22, svc.radio.maxTxPowerDbm)
        assertEquals("869.618 MHz · 62.5 kHz · SF8 · CR5", svc.radio.settingsSummary)
    }

    @Test fun applySendsParamsAwaitsOkThenConfirms() {
        val (svc, fake, radio) = connected()
        val before = fake.writes.size
        svc.applyMeshCore(MeshCorePresets.USA.params)
        assertTrue(svc.applyFlow.value.isBusy)
        radio.run()
        val sent = fake.writes.drop(before).map { it[0].toInt() and 0xFF }
        assertEquals(listOf(11, 1), sent) // set params, then APP_START to read SELF_INFO back; no reboot
        assertEquals(listOf(0x0B, 0xBD, 0xE4, 0x0D, 0x00, 0x24, 0xF4, 0x00, 0x00, 0x07, 0x05), fake.writes[before].u())
        val done = svc.applyFlow.value as RadioApplyState.Done
        assertTrue(done.confirmed.contains("910.525 MHz"))
        assertTrue(svc.radio.meshCoreParams!!.sameAs(MeshCorePresets.USA.params))
    }

    @Test fun applyWithTxPowerSendsItAfterParams() {
        val (svc, fake, radio) = connected()
        val before = fake.writes.size
        svc.applyMeshCore(MeshCorePresets.USA.params, txPowerDbm = 14)
        radio.run()
        assertEquals(listOf(11, 12, 1), fake.writes.drop(before).map { it[0].toInt() and 0xFF })
        assertEquals(14, svc.radio.txPowerDbm)
        assertTrue(svc.applyFlow.value is RadioApplyState.Done)
    }

    @Test fun errReplyFailsAndNothingElseIsSent() {
        val (svc, fake, radio) = connected()
        radio.refuseParams = true
        val before = fake.writes.size
        svc.applyMeshCore(MeshCorePresets.USA.params)
        radio.run()
        assertEquals(listOf(11), fake.writes.drop(before).map { it[0].toInt() and 0xFF })
        val failed = svc.applyFlow.value as RadioApplyState.Failed
        assertTrue(failed.reason.contains("out of range"))
        assertTrue(svc.radio.meshCoreParams!!.sameAs(MeshCoreRadioParams.FIRMWARE_DEFAULT))
    }

    @Test fun radioThatKeepsOldValuesIsReported() {
        val (svc, _, radio) = connected()
        radio.ignoreParams = true
        svc.applyMeshCore(MeshCorePresets.USA.params)
        radio.run()
        assertTrue((svc.applyFlow.value as RadioApplyState.Failed).reason.contains("869.618"))
    }

    @Test fun invalidValuesAreNotSent() {
        val (svc, fake, _) = connected()
        val before = fake.writes.size
        svc.applyMeshCore(MeshCoreRadioParams(1200.0, 62.5, 7, 5))
        assertEquals(before, fake.writes.size)
        assertTrue(svc.applyFlow.value is RadioApplyState.Failed)
        svc.clearApply()
        assertEquals(RadioApplyState.Idle, svc.applyFlow.value)
    }
}

class MeshtasticAdminCodecTest {
    private val passkey = Bytes(bytes(1, 2, 3, 4, 5, 6, 7, 8))

    @Test fun getLoraConfigRequest() {
        // AdminMessage.get_config_request (5, varint) = LORA_CONFIG (5)
        assertEquals(listOf(0x28, 0x05), MeshPBAdmin.getLoraConfig().encode().u())
    }

    @Test fun setLoraConfigWithPasskey() {
        val lora = MeshPBLoRaConfig(usePreset = true, modemPreset = 0, region = 1, hopLimit = 3, txEnabled = true)
        val expected = listOf(
            0x92, 0x02, 0x0A, // set_config (34, length-delimited), 10 bytes of Config
            0x32, 0x08, //       Config.lora (6), 8 bytes of LoRaConfig
            0x08, 0x01, //       use_preset (1) = true; modem_preset LONG_FAST (0) is the default, so left out
            0x38, 0x01, //       region (7) = US
            0x40, 0x03, //       hop_limit (8) = 3
            0x48, 0x01, //       tx_enabled (9) = true
            0xAA, 0x06, 0x08, 1, 2, 3, 4, 5, 6, 7, 8, // session_passkey (101), 8 bytes
        )
        assertEquals(expected, MeshPBAdmin.setLora(lora, passkey).encode().u())
        val back = MeshPBAdmin.decode(MeshPBAdmin.setLora(lora, passkey).encode())
        assertEquals(lora, back.setLoraConfig)
        assertEquals(passkey, back.sessionPasskey)
    }

    @Test fun configResponseCarriesLoraAndPasskey() {
        val lora = MeshPBLoRaConfig(region = 0, modemPreset = 4, hopLimit = 3, txEnabled = true, txPower = 27)
        val a = MeshPBAdmin.decode(MeshPBAdmin(loraConfigResponse = lora, sessionPasskey = passkey).encode())
        assertTrue(a.isConfigResponse)
        assertEquals(lora, a.loraConfigResponse)
        assertEquals(passkey, a.sessionPasskey)
    }

    @Test fun unknownLoraFieldsSurviveARoundTrip() {
        // region US, channel_num (11) = 20, ignore_mqtt (104) = true, override_frequency (14, float) = 915.0
        val w = MeshProtoWriter()
        w.writeUInt32(7, 1)
        w.writeUInt32(11, 20)
        w.writeBool(104, true)
        w.writeFloat(14, 915.0f)
        val decoded = MeshPBLoRaConfig.decode(w.data)
        assertEquals(1L, decoded.region)
        val changed = MeshPBLoRaConfig.decode(decoded.copy(region = 3, modemPreset = 4).encode())
        assertEquals(3L, changed.region)
        assertEquals(4L, changed.modemPreset)
        var channel = 0L; var ignore = false; var freq = 0f
        MeshProtoReader.forEachField(changed.encode()) { f ->
            when (f.number) { 11 -> channel = f.uint32; 104 -> ignore = f.bool; 14 -> freq = f.float }
        }
        assertEquals(20L, channel)
        assertTrue(ignore)
        assertEquals(915.0f, freq)
    }

    @Test fun adminPacketGoesToOwnNodeOnAdminPort() {
        val p = MeshPBPacket(to = 0x1234, decoded = MeshPBData(portnum = MeshPort.ADMIN, payload = MeshPBAdmin.getLoraConfig().encode(), wantResponse = true), id = 7)
        val back = MeshPBPacket.decode(p.encode())
        assertEquals(6L, back.decoded!!.portnum)
        assertEquals(0L, back.from)
        assertTrue(back.decoded!!.wantResponse)
    }
}

class MeshtasticRadioSettingsServiceTest {
    private val me = 0x1111_1111L
    private val h = ServiceHarness()
    private val passkey = Bytes(bytes(9, 8, 7, 6, 5, 4, 3, 2))
    @After fun tearDown() = h.cleanup()

    private fun handshake(fake: FakeMeshTransport, lora: MeshPBLoRaConfig) {
        val cfg = fake.lastWantConfigID()!!
        fake.enqueue(MeshPBFromRadioVariant.MyInfo(MeshPBMyNodeInfo(myNodeNum = me)))
        fake.enqueue(MeshPBFromRadioVariant.LoraConfig(lora))
        fake.enqueue(MeshPBFromRadioVariant.ConfigCompleteID(cfg))
        fake.pump()
    }

    private fun connected(lora: MeshPBLoRaConfig): Pair<MeshService, FakeMeshTransport> {
        val fake = FakeMeshTransport()
        val svc = h.service(fake)
        svc.start()
        svc.connect(MeshDiscoveredDevice(UUID.randomUUID().toString(), "Test Radio", -60, 0))
        fake.becomeReady()
        handshake(fake, lora)
        return svc to fake
    }

    private fun FakeMeshTransport.deliver(v: MeshPBFromRadioVariant) {
        enqueue(v)
        emit(MeshTransportEvent.FromNumChanged)
        pump()
    }

    private fun adminReply(requestID: Long, lora: MeshPBLoRaConfig) = MeshPBFromRadioVariant.Packet(MeshPBPacket(
        from = me, to = me, decoded = MeshPBData(portnum = MeshPort.ADMIN, requestID = requestID,
            payload = MeshPBAdmin(loraConfigResponse = lora, sessionPasskey = passkey).encode()), id = 99))

    @Test fun unsetRegionShowsRadioSettingsNotice() {
        val (svc, _) = connected(MeshPBLoRaConfig(region = 0, usePreset = true))
        assertTrue(svc.notice!!.contains("Radio settings"))
        assertEquals("Region: Not set · Long Fast", svc.radio.settingsSummary)
    }

    @Test fun getEchoPasskeySetRestartAndConfirm() {
        // The radio's config has fields we don't model (channel_num 11 = 20): they must come back unchanged.
        val w = MeshProtoWriter(); w.writeUInt32(8, 3); w.writeUInt32(11, 20)
        val radioLora = MeshPBLoRaConfig.decode(w.data)
        val (svc, fake) = connected(radioLora)

        svc.applyMeshtastic(region = 1, modemPreset = 0)
        val get = fake.lastSentPacket()!!
        assertEquals(me, get.to)
        assertEquals(0L, get.from)
        assertEquals(MeshPort.ADMIN, get.decoded!!.portnum)
        assertTrue(get.decoded!!.wantResponse)
        assertEquals(listOf(0x28, 0x05), get.decoded!!.payload.u())
        assertEquals(RadioApplyState.Working("Reading the radio's settings…"), svc.applyFlow.value)

        fake.deliver(adminReply(get.id, radioLora))
        val set = fake.lastSentPacket()!!
        assertEquals(me, set.to)
        val admin = MeshPBAdmin.decode(set.decoded!!.payload)
        assertEquals(passkey, admin.sessionPasskey)
        val sent = admin.setLoraConfig!!
        assertEquals(1L, sent.region)
        assertEquals(0L, sent.modemPreset)
        assertTrue(sent.usePreset)
        assertTrue(sent.txEnabled) // first region: the firmware turns transmit on too
        assertEquals(3L, sent.hopLimit)
        var channel = 0L
        MeshProtoReader.forEachField(sent.encode()) { if (it.number == 11) channel = it.uint32 }
        assertEquals(20L, channel)
        assertEquals(RadioApplyState.Restarting, svc.applyFlow.value)

        // The radio restarts: the link drops, comes back, and the handshake reports the new region.
        fake.emit(MeshTransportEvent.Disconnected(fake.connectedID!!, null, true))
        assertTrue(svc.connection is MeshConnectionState.Reconnecting)
        fake.becomeReady()
        handshake(fake, sent)
        val done = svc.applyFlow.value as RadioApplyState.Done
        assertTrue(done.confirmed.contains("United States"))
        assertNull(svc.notice)
    }

    @Test fun routingErrorOnSetFails() {
        val (svc, fake) = connected(MeshPBLoRaConfig(region = 0))
        svc.applyMeshtastic(1, 0)
        fake.deliver(adminReply(fake.lastSentPacket()!!.id, MeshPBLoRaConfig(region = 0)))
        val set = fake.lastSentPacket()!!
        fake.deliver(MeshPBFromRadioVariant.Packet(MeshPBPacket(from = me, to = me,
            decoded = MeshPBData(portnum = MeshPort.ROUTING, requestID = set.id,
                payload = MeshPBRouting(MeshRoutingError.ADMIN_BAD_SESSION_KEY).encode()), id = 5)))
        assertTrue((svc.applyFlow.value as RadioApplyState.Failed).reason.contains("session expired"))
    }

    @Test fun sameSettingsSendNothing() {
        val lora = MeshPBLoRaConfig(region = 1, modemPreset = 0, usePreset = true, txEnabled = true)
        val (svc, fake) = connected(lora)
        svc.applyMeshtastic(1, 0, true)
        fun packets() = fake.decodedWrites().count { it is MeshPBToRadio.Packet }
        val before = packets()
        fake.deliver(adminReply(fake.lastSentPacket()!!.id, lora))
        assertEquals(before, packets())
        assertNull(MeshPBAdmin.decode(fake.lastSentPacket()!!.decoded!!.payload).setLoraConfig)
        assertTrue(svc.applyFlow.value is RadioApplyState.Done)
    }

    @Test fun refusesWithoutConnectionOrRegion() {
        val svc = h.service(FakeMeshTransport())
        svc.applyMeshtastic(1, 0)
        assertTrue(svc.applyFlow.value is RadioApplyState.Failed)
        val (svc2, fake) = connected(MeshPBLoRaConfig(region = 0))
        val n = fake.writes.size
        svc2.applyMeshtastic(99, 0)
        assertEquals(n, fake.writes.size)
        assertTrue(svc2.applyFlow.value is RadioApplyState.Failed)
    }
}

class RadioSettingsLogicTest {
    private val usa = MeshCoreRadioParams(910.525, 62.5, 7, 5)
    private val usaCr8 = MeshCoreRadioParams(910.525, 62.5, 7, 8)
    private val socal = MeshCoreRadioParams(927.875, 62.5, 7, 5)

    @Test fun mostCommonCountsOnlyKnownSettings() {
        val m = NearbyRadioParams.mostCommon(listOf(usa, usa, usaCr8, null, usa, socal, null))!!
        assertTrue(m.params.sameAs(usa))
        assertEquals(3, m.count)
        assertEquals(5, m.total)
        assertEquals("Used by 3 of 5 nearby radios", m.text)
    }

    @Test fun mapNoiseGroupsTogether() {
        val noisy = MeshCoreRadioParams(910.52502, 62.49999, 7, 5)
        val m = NearbyRadioParams.mostCommon(listOf(noisy, usa, usaCr8))!!
        assertEquals(2, m.count)
        assertEquals(910_525L, m.params.frequencyKHz)
    }

    @Test fun tiesPreferAPresetThenTheLowerFrequency() {
        val odd = MeshCoreRadioParams(909.0, 62.5, 7, 5)
        // 909.0 is lower but not a preset: the USA preset wins the tie.
        assertTrue(NearbyRadioParams.mostCommon(listOf(odd, usa, odd, usa))!!.params.sameAs(usa))
        // Both presets: lower frequency wins, in either order.
        assertTrue(NearbyRadioParams.mostCommon(listOf(socal, usa))!!.params.sameAs(usa))
        assertTrue(NearbyRadioParams.mostCommon(listOf(usa, socal))!!.params.sameAs(usa))
    }

    @Test fun nothingUsable() {
        assertNull(NearbyRadioParams.mostCommon(emptyList()))
        assertNull(NearbyRadioParams.mostCommon(listOf(null, null)))
        assertNull(NearbyRadioParams.mostCommon(listOf(MeshCoreRadioParams(0.0, 0.0, 0, 0))))
    }

    @Test fun validation() {
        assertTrue(MeshCoreRadioLimits.problems(usa).isEmpty())
        assertEquals(1, MeshCoreRadioLimits.problems(usa.copy(frequencyMHz = 149.9)).size)
        assertEquals(1, MeshCoreRadioLimits.problems(usa.copy(frequencyMHz = 960.1)).size)
        assertTrue(MeshCoreRadioLimits.problems(usa.copy(frequencyMHz = 960.0)).isEmpty())
        assertEquals(1, MeshCoreRadioLimits.problems(usa.copy(bandwidthKHz = 100.0)).size)
        assertEquals(1, MeshCoreRadioLimits.problems(usa.copy(spreadingFactor = 4)).size)
        assertEquals(1, MeshCoreRadioLimits.problems(usa.copy(spreadingFactor = 13)).size)
        assertEquals(1, MeshCoreRadioLimits.problems(usa.copy(codingRate = 9)).size)
        assertEquals(1, MeshCoreRadioLimits.problems(usa, txPowerDbm = 23, maxTxDbm = 22).size)
        assertTrue(MeshCoreRadioLimits.problems(usa, txPowerDbm = -9, maxTxDbm = 22).isEmpty())
        assertEquals(1, MeshCoreRadioLimits.problems(usa.copy(frequencyMHz = Double.NaN)).size)
        assertEquals(910.525, MeshCoreRadioLimits.parseMHz(" 910,525 ")!!, 1e-9)
        assertNull(MeshCoreRadioLimits.parseMHz("abc"))
        MeshCoreRadioLimits.BANDWIDTHS.forEach { assertTrue(MeshCoreRadioLimits.problems(usa.copy(bandwidthKHz = it)).isEmpty()) }
    }

    @Test fun presetsAreValidAndUsaIsTheRecommendedOne() {
        MeshCorePresets.official.forEach { assertTrue(it.title, MeshCoreRadioLimits.problems(it.params).isEmpty()) }
        assertEquals("910.525 MHz · 62.5 kHz · SF7 · CR5", MeshCorePresets.USA.params.summary)
        assertEquals("USA", MeshCorePresets.matching(usa)!!.title)
        assertNull(MeshCorePresets.matching(MeshCoreRadioParams(909.0, 62.5, 7, 5)))
    }

    @Test fun cardHint() {
        val match = NearbyRadioParams.mostCommon(listOf(usa, usa, usa, socal))!!
        val hint = NearbyRadioParams.hint(MeshCoreRadioParams.FIRMWARE_DEFAULT, match)!!
        assertTrue(hint.contains("910.525 MHz"))
        assertTrue(hint.contains("869.618 MHz"))
        assertTrue(hint.contains("3 of 4"))
        assertNull(NearbyRadioParams.hint(usa, match))
        assertNotNull(NearbyRadioParams.hint(usaCr8, match)!!.takeIf { it.contains("CR5") })
        // Too few radios agree: say nothing.
        assertNull(NearbyRadioParams.hint(socal, NearbyRadioParams.mostCommon(listOf(usa, usa))))
        assertNull(NearbyRadioParams.hint(null, match))
        assertNull(NearbyRadioParams.hint(socal, null))
    }

    @Test fun meshtasticRegionsAndPresets() {
        assertEquals("US", MeshtasticRegions.suggested("us")!!.key)
        assertEquals("US", MeshtasticRegions.suggested("CA")!!.key)
        assertEquals("EU_868", MeshtasticRegions.suggested("DE")!!.key)
        assertEquals("ANZ", MeshtasticRegions.suggested("NZ")!!.key)
        assertNull(MeshtasticRegions.suggested("ZZ"))
        assertNull(MeshtasticRegions.suggested(null))
        assertEquals("United States (902–928 MHz)", MeshtasticRegions.of(1)!!.label)
        assertEquals("Not set", MeshtasticRegions.name(0))
        assertEquals((1L..26L).toList(), MeshtasticRegions.all.map { it.code })
        assertEquals("Long Fast", MeshtasticModemPresets.name(0))
        assertEquals("Medium Fast", MeshtasticModemPresets.name(4))
        assertFalse(MeshtasticModemPresets.all.any { it.code == 1L || it.code == 2L })
    }

    @Test fun storeKeepsRadioSettings() {
        val dir = java.nio.file.Files.createTempDirectory("rs").toFile()
        try {
            val info = MeshRadioInfo(firmware = Firmware.MESHCORE, meshCoreParams = usa, txPowerDbm = 10, maxTxPowerDbm = 22,
                modemPreset = 4, usePreset = true, txEnabled = false)
            MeshStore(dir).save(MeshSnapshot(emptyList(), emptyList(), info, emptyMap()))
            assertEquals(info, MeshStore(dir).load().radio)
        } finally { dir.deleteRecursively() }
    }
}
