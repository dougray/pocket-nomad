package org.nomadpocket.ham

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The ham protocol core: KISS, AX.25, APRS, LZHUF, Winlink. Mirrors HamCoreTests.swift. */
class HamCoreTest {
    private fun fixture(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun kissEscapesAndReassembles() {
        val payload = b(1, 0xC0, 2, 0xDB, 3)
        val wire = Kiss.encode(payload)
        assertArrayEquals(b(0xC0, 0, 1, 0xDB, 0xDC, 2, 0xDB, 0xDD, 3, 0xC0), wire)
        val d = KissDecoder()
        val frames = (wire + wire).flatMap { d.feed(byteArrayOf(it)) }
        assertEquals(2, frames.size)
        assertArrayEquals(payload, frames[0].payload)
    }

    @Test fun ax25AddressBytesAndRoundTrip() {
        val f = Ax25Frame(Ax25Address.parse("APZNMD")!!, Ax25Address.parse("KF0ABC-7")!!, listOf(Ax25Address.parse("WIDE1-1")!!),
            control = Ax25Control.UI(false), info = "hello".toByteArray())
        val e = f.encoded()
        assertEquals(0xE0, e[6].toInt() and 0xFF)
        assertEquals(0x6E, e[13].toInt() and 0xFF)
        assertEquals(0x63, e[20].toInt() and 0xFF)
        assertEquals(0x03, e[21].toInt() and 0xFF)
        val back = Ax25Frame.decode(e)!!
        assertEquals(f, back)
        assertEquals("KF0ABC-7>APZNMD,WIDE1-1:hello", back.monitorLine)
        assertNull(Ax25Address.parse("TOOLONGCALL"))
        assertNull(Ax25Address.parse("AB-16"))
    }

    @Test fun controlFieldsRoundTrip() {
        val all = listOf(Ax25Control.I(5, 3, true), Ax25Control.RR(7, false), Ax25Control.RNR(1, true), Ax25Control.REJ(2, false),
            Ax25Control.SABM(true), Ax25Control.DISC(true), Ax25Control.UA(true), Ax25Control.DM(false), Ax25Control.FRMR(false), Ax25Control.UI(false))
        for (c in all) assertEquals(c, Ax25Control.from(c.byte))
        assertEquals(0x3F, Ax25Control.SABM(true).byte)
        assertEquals(0x73, Ax25Control.UA(true).byte)
    }

    @Test fun connectedModeSurvivesLoss() {
        val a = Ax25Address.parse("KF0AAA")!!; val bb = Ax25Address.parse("KF0BBB-1")!!
        val left = Ax25Link(a, bb, emptyList())
        val right = Ax25Link(bb, a, emptyList())
        val air = ArrayDeque<Pair<Ax25Link, Ax25Frame>>()
        var count = 0
        left.send = { f -> count++; if (count % 4 != 0) air.addLast(right to Ax25Frame.decode(f.encoded())!!) }
        right.send = { f -> count++; if (count % 5 != 0) air.addLast(left to Ax25Frame.decode(f.encoded())!!) }
        val gotR = java.io.ByteArrayOutputStream(); val gotL = java.io.ByteArrayOutputStream()
        right.onData = { gotR.write(it) }
        left.onData = { gotL.write(it) }
        var now = 0L
        left.connect(now)
        val msgA = ByteArray(1500) { (it % 251).toByte() }
        val msgB = ByteArray(700) { 7 }
        var wrote = false
        repeat(2000) {
            while (air.isNotEmpty()) { val (to, f) = air.removeFirst(); to.receive(f, now) }
            if (left.state == Ax25Link.State.CONNECTED && right.state == Ax25Link.State.CONNECTED && !wrote) {
                left.write(msgA, now); right.write(msgB, now); wrote = true
            }
            if (wrote && gotR.size() == msgA.size && gotL.size() == msgB.size) return@repeat
            now += 1000
            left.tick(now); right.tick(now)
        }
        assertArrayEquals(msgA, gotR.toByteArray())
        assertArrayEquals(msgB, gotL.toByteArray())
    }

    @Test fun connectGivesUp() {
        val link = Ax25Link(Ax25Address.parse("KF0AAA")!!, Ax25Address.parse("KF0ZZZ")!!, emptyList(), Ax25LinkConfig(n2 = 3))
        var sent = 0
        link.send = { sent++ }
        var now = 0L
        link.connect(now)
        repeat(100) { now += 1000; link.tick(now) }
        assertEquals(Ax25Link.State.DISCONNECTED, link.state)
        assertEquals(4, sent)
        assertNotNull(link.endReason)
    }

    @Test fun plainPosition() {
        val p = Aprs.decodeTnc2("KF0ABC-9>APRS,WIDE1-1:=3944.40N/10459.40W>090/036/A=005280Driving")!!
        assertEquals(AprsPacket.Kind.POSITION, p.kind)
        assertTrue(p.messaging)
        assertEquals(39.74, p.position!!.latitude, 1e-4)
        assertEquals(-104.99, p.position!!.longitude, 1e-4)
        assertEquals(90, p.courseDegrees)
        assertEquals(36.0, p.speedKnots!!, 0.0)
        assertEquals(5280.0, p.altitudeFeet!!, 0.0)
        assertEquals("Driving", p.comment)
    }

    @Test fun ambiguousAndCompressed() {
        val p = Aprs.decodeTnc2("N0CALL>APRS:@092345z3944.  N/10459.  W-Home")!!
        assertEquals(2, p.position!!.ambiguity)
        assertEquals("Home", p.comment)
        fun b91(v0: Int): String { var v = v0; var s = ""; repeat(4) { s = (v % 91 + 33).toChar() + s; v /= 91 }; return s }
        val y = ((90 - 39.74) * 380926).toInt(); val x = ((180 - 104.99) * 190463).toInt()
        val c = Aprs.decodeTnc2("N0CALL>APRS:!/" + b91(y) + b91(x) + ">7P[comment")!!
        assertEquals(39.74, c.position!!.latitude, 1e-4)
        assertEquals(-104.99, c.position!!.longitude, 1e-4)
        assertEquals("comment", c.comment)
    }

    @Test fun micE() {
        val info = b(0x60, 112, 87, 68, 30, 30, 79, '>'.code, '/'.code) + "]Hello=".toByteArray()
        val p = Aprs.decode("KF0ABC-7", "SY4TTP", emptyList(), info)!!
        assertEquals(AprsPacket.Kind.MIC_E, p.kind)
        assertEquals(39.74, p.position!!.latitude, 1e-4)
        assertEquals(-104.99, p.position!!.longitude, 1e-4)
        assertEquals(20.0, p.speedKnots!!, 0.0)
        assertEquals(251, p.courseDegrees)
        assertEquals("En Route", p.micEStatus)
        assertEquals("Hello", p.comment)
    }

    @Test fun messagesAcksBulletins() {
        val m = Aprs.decodeTnc2("KF0ABC>APRS::KF0XYZ-7 :Meet at the trailhead{42")!!
        assertEquals(AprsPacket.Kind.MESSAGE, m.kind)
        assertEquals("KF0XYZ-7", m.addressee)
        assertEquals("Meet at the trailhead", m.text)
        assertEquals("42", m.messageId)
        assertEquals("AB", Aprs.decodeTnc2("KF0ABC>APRS::KF0XYZ   :ok{AB}12")!!.messageId)
        val ack = Aprs.decodeTnc2("KF0XYZ>APRS::KF0ABC   :ack42")!!
        assertEquals(AprsPacket.Kind.ACK, ack.kind)
        assertEquals(AprsPacket.Kind.BULLETIN, Aprs.decodeTnc2("KF0ABC>APRS::BLN1     :Net tonight")!!.kind)
        assertEquals(":KF0XYZ-7 :hi ? there{3", Aprs.messageInfo("kf0xyz-7", "hi | there", "3"))
        assertEquals(":KF0ABC   :ack42", Aprs.ackInfo("KF0ABC", "42"))
    }

    @Test fun objectsWeatherThirdParty() {
        val o = Aprs.decodeTnc2("KF0ABC>APRS:;SHELTER  *092345z3944.40N/10459.40W;Red Cross")!!
        assertEquals(AprsPacket.Kind.OBJECT, o.kind)
        assertEquals("SHELTER", o.subject)
        assertEquals("Red Cross", o.comment)
        assertEquals("AID", Aprs.decodeTnc2("KF0ABC>APRS:)AID!3944.40N/10459.40W+First aid")!!.objectName)
        val w = Aprs.decodeTnc2("KF0WX>APRS:=3944.40N/10459.40W_220/004g005t077r000p000P000h50b09900wRSW")!!
        assertEquals(AprsPacket.Kind.WEATHER, w.kind)
        assertEquals(77, w.weather!!.temperatureF)
        assertEquals(220, w.weather!!.windDirection)
        assertEquals(990.0, w.weather!!.pressureMbar!!, 0.01)
        val t = Aprs.decodeTnc2("IGATE>APRS:}KF0ABC>APRS,TCPIP,IGATE*::KF0XYZ   :hi{1")!!
        assertEquals("KF0ABC", t.source)
        assertEquals(AprsPacket.Kind.MESSAGE, t.kind)
    }

    @Test fun positionEncoding() {
        val info = Aprs.positionInfo(39.74, -104.99, courseDegrees = 90, speedKnots = 36.0, comment = "Pocket Nomad")
        assertEquals("=3944.40N/10459.40W[090/036Pocket Nomad", info)
        assertEquals("DM79mr", HamGeo.maidenhead(39.74, -104.99))
    }

    @Test fun lzhufMatchesReference() {
        for (name in listOf("gettysburg.txt", "e.txt")) {
            val plain = fixture(name); val packed = fixture("$name.lzh")
            assertArrayEquals(name, plain, Lzhuf.decompress(packed))
            assertArrayEquals(name, packed, Lzhuf.compress(plain))
        }
        assertEquals(0, Lzhuf.decompress(Lzhuf.compress(ByteArray(0))).size)
        val bad = fixture("gettysburg.txt.lzh"); bad[40] = (bad[40].toInt() xor 0xFF).toByte()
        try { Lzhuf.decompress(bad); fail() } catch (_: Lzhuf.Failure) {}
    }

    @Test fun secureLogin() {
        assertEquals("72768415", WinlinkAuth.response("23753528", "FOOBAR"))
        assertEquals("95074758", WinlinkAuth.response("23753528", "FooBar"))
    }

    @Test fun winlinkMessageRoundTrip() {
        val m = WinlinkMessage.compose("kf0abc", listOf("KF0XYZ", "someone@example.com"), subject = "Café check-in",
            body = "All safe.\nAt the shelter.", dateMillis = 1_800_000_000_000).copy(attachments = listOf(WinlinkAttachment("note.txt", "hello".toByteArray())))
        assertEquals(listOf("KF0XYZ", "SMTP:someone@example.com"), m.to)
        val raw = m.encoded()
        assertTrue(String(raw).contains("Body: 26\r\n"))
        val back = WinlinkMessage.parse(raw)
        assertEquals(m.mid, back.mid)
        assertEquals("Café check-in", back.subject)
        assertEquals("All safe.\nAt the shelter.", back.body)
        assertEquals(m.attachments, back.attachments)
        assertEquals(m.dateMillis, back.dateMillis)
        assertEquals(m, WinlinkMessage.fromJson(m.toJson()))
    }

    @Test fun b2fAgainstScriptedGateway() = runBlocking {
        val toClient = HamByteChannel(); val toGateway = HamByteChannel()
        val outgoing = WinlinkMessage.compose("N0CALL", listOf("KF0XYZ"), subject = "Status", body = "Fine here.")
        val incoming = WinlinkMessage.compose("KF0XYZ", listOf("N0CALL"), subject = "Reply", body = "Good to hear.")
        val session = B2fSession("N0CALL", "FOOBAR", "DM79", "TEST-10", listOf(outgoing), toClient) { toGateway.feed(it) }
        var gatewayGot: WinlinkMessage? = null
        val gw = launch {
            toClient.feed("Welcome\r[WL2K-5.0-B2FWIHJM$]\r;PQ: 23753528\rCMS via TEST >\r".toByteArray())
            assertEquals(";FW: N0CALL", toGateway.readLine())
            assertTrue(toGateway.readLine().startsWith("[PocketNomad-"))
            assertEquals(";PR: 72768415", toGateway.readLine())
            assertEquals("; TEST-10 DE N0CALL (DM79)", toGateway.readLine())
            var sum = 0; var csize = 0
            var line = toGateway.readLine()
            while (line.startsWith("FC")) {
                for (c in line.toByteArray()) sum += c.toInt() and 0xFF
                sum += 13
                csize = line.split(" ")[4].toInt()
                line = toGateway.readLine()
            }
            assertEquals("F> %02X".format((-sum) and 0xFF), line)
            toClient.feed("FS +\r".toByteArray())
            assertEquals(1, toGateway.readByte())
            toGateway.readByte()
            assertEquals("Status", String(toGateway.readUntil(0)))
            toGateway.readUntil(0)
            val data = java.io.ByteArrayOutputStream()
            while (true) {
                val c = toGateway.readByte()
                if (c == 4) { toGateway.readByte(); break }
                repeat(toGateway.readByte()) { data.write(toGateway.readByte()) }
            }
            assertEquals(csize, data.size())
            gatewayGot = WinlinkMessage.parse(Lzhuf.decompress(data.toByteArray()))
            val raw = incoming.encoded(); val z = Lzhuf.compress(raw)
            val prop = "FC EM ${incoming.mid} ${raw.size} ${z.size} 0"
            var s2 = 0
            for (c in prop.toByteArray()) s2 += c.toInt() and 0xFF
            s2 += 13
            toClient.feed(("$prop\r" + "F> %02X".format((-s2) and 0xFF) + "\r").toByteArray())
            assertEquals("FS +", toGateway.readLine())
            val block = java.io.ByteArrayOutputStream()
            block.write(1); block.write("Reply".length + 3); block.write("Reply".toByteArray()); block.write(0); block.write('0'.code); block.write(0)
            var ck = 0
            var i = 0
            while (i < z.size) {
                val n = minOf(125, z.size - i)
                block.write(2); block.write(n)
                for (j in i until i + n) { block.write(z[j].toInt()); ck += z[j].toInt() and 0xFF }
                i += n
            }
            block.write(4); block.write((-ck) and 0xFF)
            toClient.feed(block.toByteArray())
            assertEquals("FF", toGateway.readLine())
            toClient.feed("FQ\r".toByteArray())
        }
        val out = session.run()
        gw.join()
        assertEquals(listOf(outgoing.mid), out.sent)
        assertEquals(listOf(incoming.mid), out.received.map { it.mid })
        assertEquals("Good to hear.", out.received[0].body)
        assertEquals("Fine here.", gatewayGot!!.body)
    }

    @Test fun b2fWithoutPasswordExplains() = runBlocking {
        val toClient = HamByteChannel()
        toClient.feed("[WL2K-5.0-B2FWIHJM$]\r;PQ: 1234\rCMS >\r".toByteArray())
        try {
            B2fSession("N0CALL", null, "", "TEST", emptyList(), toClient) {}.run()
            fail()
        } catch (e: B2fSession.ProtocolError) {
            assertTrue(e.message!!.contains("password"))
        }
    }
}
