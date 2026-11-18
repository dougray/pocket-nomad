// Winlink.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// Winlink email over packet: message format and the B2F client exchange with
// an RMS gateway. Port of Winlink.swift; protocol details follow wl2k-go
// (MIT, LA5NTA). See NOTICE.

package org.nomadpocket.ham

import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class WinlinkAttachment(val name: String, val data: ByteArray) {
    override fun equals(other: Any?) = other is WinlinkAttachment && name == other.name && data.contentEquals(other.data)
    override fun hashCode() = name.hashCode()
}

data class WinlinkMessage(
    val mid: String,
    val dateMillis: Long,
    val from: String,
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val subject: String,
    val body: String,
    val attachments: List<WinlinkAttachment> = emptyList(),
    val type: String = "Private",
    val mbo: String,
) {
    fun encoded(): ByteArray {
        val latin1 = Charsets.ISO_8859_1.newEncoder().canEncode(body)
        val bodyBytes = crlf(body.toByteArray(if (latin1) Charsets.ISO_8859_1 else Charsets.UTF_8))
        val h = StringBuilder()
        h.append("Mid: $mid\r\n")
        h.append("Body: ${bodyBytes.size}\r\n")
        h.append("Content-Transfer-Encoding: 8bit\r\n")
        h.append("Content-Type: text/plain; charset=${if (latin1) "ISO-8859-1" else "UTF-8"}\r\n")
        h.append("Date: ${fmt("yyyy/MM/dd HH:mm").format(Date(dateMillis))}\r\n")
        for (f in attachments) h.append("File: ${f.data.size} ${qEncode(f.name)}\r\n")
        h.append("From: $from\r\n")
        h.append("Mbo: $mbo\r\n")
        h.append("Subject: ${qEncode(subject)}\r\n")
        for (t in to) h.append("To: $t\r\n")
        for (c in cc) h.append("Cc: $c\r\n")
        h.append("Type: $type\r\n\r\n")
        val out = java.io.ByteArrayOutputStream()
        out.write(h.toString().toByteArray(Charsets.UTF_8))
        out.write(bodyBytes)
        if (attachments.isNotEmpty()) out.write(byteArrayOf(13, 10))
        for (f in attachments) { out.write(f.data); out.write(byteArrayOf(13, 10)) }
        return out.toByteArray()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("mid", mid); put("date", dateMillis); put("from", from); put("to", JSONArray(to)); put("cc", JSONArray(cc))
        put("subject", subject); put("body", body); put("type", type); put("mbo", mbo)
        put("files", JSONArray(attachments.map { JSONObject().put("name", it.name).put("data", Base64Compat.encode(it.data)) }))
    }

    companion object {
        fun newMid(): String {
            val a = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            val r = java.security.SecureRandom()
            return (1..12).map { a[r.nextInt(a.length)] }.joinToString("")
        }

        fun compose(from: String, to: List<String>, cc: List<String> = emptyList(), subject: String, body: String,
                    dateMillis: Long = System.currentTimeMillis()) =
            WinlinkMessage(newMid(), dateMillis, from.uppercase(), to.map(::normalizedAddress), cc.map(::normalizedAddress),
                subject, body, mbo = from.uppercase())

        fun normalizedAddress(a: String): String {
            val t = a.trim()
            if (t.uppercase().startsWith("SMTP:")) return "SMTP:" + t.substring(5)
            val at = t.indexOf('@')
            if (at >= 0) {
                if (t.substring(at + 1).lowercase() == "winlink.org") return t.substring(0, at).uppercase()
                return "SMTP:$t"
            }
            return t.uppercase()
        }

        fun displayAddress(a: String) = if (a.uppercase().startsWith("SMTP:")) a.substring(5) else a

        fun fmt(f: String) = SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        private val dateFormats = listOf("yyyy/MM/dd HH:mm", "yyyy.MM.dd HH:mm", "yyyy-MM-dd HH:mm", "yyyyMMddHHmmss",
            "EEE, d MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm Z")

        fun crlf(b: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream(b.size + b.size / 40)
            var prev = 0
            for (x in b) {
                val c = x.toInt() and 0xFF
                if (c == 10 && prev != 13) out.write(13)
                out.write(c)
                prev = c
            }
            return out.toByteArray()
        }

        fun parse(bytes: ByteArray): WinlinkMessage {
            var i = 0
            while (i < bytes.size && (bytes[i].toInt() and 0xFF) in listOf(9, 10, 13, 32)) i++
            val headers = ArrayList<Pair<String, String>>()
            while (true) {
                var nl = i
                while (nl < bytes.size && bytes[nl] != 10.toByte()) nl++
                if (nl >= bytes.size) throw IllegalArgumentException("no header")
                var line = String(bytes, i, nl - i, Charsets.UTF_8)
                if (line.endsWith("\r")) line = line.dropLast(1)
                i = nl + 1
                if (line.isEmpty()) break
                if ((line.startsWith(" ") || line.startsWith("\t")) && headers.isNotEmpty()) {
                    val last = headers.removeAt(headers.size - 1)
                    headers.add(last.first to last.second + " " + line.trim())
                    continue
                }
                val colon = line.indexOf(':')
                if (colon < 0) continue
                headers.add(line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim())
            }
            fun all(k: String) = headers.filter { it.first == k }.map { it.second }
            fun one(k: String) = all(k).firstOrNull()
            val mid = one("mid") ?: throw IllegalArgumentException("no Mid")
            val size = one("body")?.toIntOrNull() ?: (bytes.size - i)
            if (i + size > bytes.size) throw IllegalArgumentException("short")
            val bodyBytes = bytes.copyOfRange(i, i + size)
            i += size
            if (i + 1 < bytes.size && bytes[i] == 13.toByte() && bytes[i + 1] == 10.toByte()) i += 2
            val utf8 = (one("content-type") ?: "").lowercase().contains("utf-8")
            val body = String(bodyBytes, if (utf8) Charsets.UTF_8 else Charsets.ISO_8859_1)
            val files = ArrayList<WinlinkAttachment>()
            for (f in all("file")) {
                val parts = f.split(" ", limit = 2)
                val n = parts.getOrNull(0)?.toIntOrNull() ?: continue
                if (parts.size != 2 || i + n > bytes.size) continue
                files.add(WinlinkAttachment(qDecode(parts[1]), bytes.copyOfRange(i, i + n)))
                i += n
                if (i + 1 < bytes.size && bytes[i] == 13.toByte() && bytes[i + 1] == 10.toByte()) i += 2
            }
            var date = System.currentTimeMillis()
            one("date")?.let { d ->
                for (f in dateFormats) { try { date = fmt(f).parse(d)!!.time; break } catch (_: Exception) {} }
            }
            return WinlinkMessage(mid, date, one("from") ?: "", all("to"), all("cc"), qDecode(one("subject") ?: ""),
                body.replace("\r\n", "\n"), files, one("type") ?: "Private", one("mbo") ?: "")
        }

        fun fromJson(o: JSONObject): WinlinkMessage {
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val files = o.optJSONArray("files")?.let { a ->
                (0 until a.length()).map { val f = a.getJSONObject(it); WinlinkAttachment(f.getString("name"), Base64Compat.decode(f.getString("data"))) }
            } ?: emptyList()
            return WinlinkMessage(o.getString("mid"), o.getLong("date"), o.getString("from"), list("to"), list("cc"),
                o.getString("subject"), o.getString("body"), files, o.optString("type", "Private"), o.optString("mbo"))
        }

        fun qEncode(s: String): String {
            if (s.all { it.code in 0x20..0x7E }) return s
            val sb = StringBuilder("=?utf-8?q?")
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                when {
                    c.toChar().isLetterOrDigit() && c < 0x80 -> sb.append(c.toChar())
                    c == 0x20 -> sb.append('_')
                    else -> sb.append("=%02X".format(c))
                }
            }
            return sb.append("?=").toString()
        }

        fun qDecode(s: String): String {
            if (!s.contains("=?")) return s
            val re = Regex("=\\?([^?]+)\\?([qQbB])\\?([^?]*)\\?=")
            return re.replace(s.replace(Regex("\\?=\\s+=\\?"), "?==?")) { m ->
                val charset = m.groupValues[1].lowercase()
                val text = m.groupValues[3]
                val bytes = if (m.groupValues[2].lowercase() == "b") {
                    try { Base64Compat.decode(text) } catch (_: Exception) { ByteArray(0) }
                } else {
                    val out = java.io.ByteArrayOutputStream()
                    var i = 0
                    while (i < text.length) {
                        val c = text[i]
                        if (c == '_') out.write(32)
                        else if (c == '=' && i + 2 < text.length && text.substring(i + 1, i + 3).toIntOrNull(16) != null) {
                            out.write(text.substring(i + 1, i + 3).toInt(16)); i += 2
                        } else out.write(c.code)
                        i++
                    }
                    out.toByteArray()
                }
                String(bytes, if (charset.contains("8859")) Charsets.ISO_8859_1 else Charsets.UTF_8)
            }
        }
    }
}

object WinlinkAuth {
    private val SALT = intArrayOf(
        77, 197, 101, 206, 190, 249, 93, 200, 51, 243, 93, 237, 71, 94, 239, 138, 68, 108, 70, 185, 225, 137,
        217, 16, 51, 122, 193, 48, 194, 195, 198, 175, 172, 169, 70, 84, 61, 62, 104, 186, 114, 52, 61, 168,
        66, 129, 192, 208, 187, 249, 232, 193, 41, 113, 41, 45, 240, 16, 29, 228, 208, 228, 61, 20,
    ).map { it.toByte() }.toByteArray()

    fun response(challenge: String, password: String): String {
        val sum = MessageDigest.getInstance("MD5").digest(challenge.toByteArray() + password.toByteArray() + SALT)
        var pr = sum[3].toInt() and 0x3F
        for (i in 2 downTo 0) pr = (pr shl 8) or (sum[i].toInt() and 0xFF)
        val s = "%08d".format(pr)
        return s.takeLast(8)
    }
}

/** Bytes from the link, read with suspend calls. Closing wakes the reader with the reason. */
class HamByteChannel {
    class Closed(reason: String) : Exception(reason)

    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    private var buf = ByteArray(0)
    private var head = 0
    @Volatile private var closedReason: String? = null

    fun feed(bytes: ByteArray) { chunks.trySend(bytes) }

    fun close(reason: String) {
        if (closedReason == null) closedReason = reason
        chunks.close()
    }

    private suspend fun fill() {
        while (head >= buf.size) {
            val r = chunks.receiveCatching()
            val next = r.getOrNull() ?: throw Closed(closedReason ?: "Connection closed.")
            buf = next; head = 0
        }
    }

    suspend fun readByte(): Int { fill(); return buf[head++].toInt() and 0xFF }

    suspend fun readLine(): String {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = readByte()
            if (b == 13) return out.toString(Charsets.UTF_8.name())
            if (b == 10) continue
            out.write(b)
            if (out.size() > 1024) return out.toString(Charsets.UTF_8.name())
        }
    }

    suspend fun readUntil(stop: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = readByte()
            if (b == stop) return out.toByteArray()
            out.write(b)
            if (out.size() > 1024) throw Closed("Malformed block header from the gateway.")
        }
    }
}

class B2fSession(
    mycall: String,
    private val password: String?,
    private val locator: String,
    target: String,
    outbound: List<WinlinkMessage>,
    private val input: HamByteChannel,
    private val write: (ByteArray) -> Unit,
) {
    class ProtocolError(message: String) : Exception(message)

    data class Outcome(
        val received: MutableList<WinlinkMessage> = ArrayList(),
        val sent: MutableList<String> = ArrayList(),
        val alreadyDelivered: MutableList<String> = ArrayList(),
        val deferred: MutableList<String> = ArrayList(),
    )

    private class Proposal(val code: Char, val mid: String, val size: Int, val compressedSize: Int,
                           var data: ByteArray = ByteArray(0), var title: String = "", var answer: Char = '=', var offset: Int = 0)

    private val mycall = mycall.uppercase()
    private val target = target.uppercase()
    private val outbound = outbound.toMutableList()
    var log: (String) -> Unit = {}
    var progress: (String, Int, Int) -> Unit = { _, _, _ -> }
    var knownMids: MutableSet<String> = HashSet()
    var onReceived: (WinlinkMessage) -> Unit = {}
    var onSent: (String) -> Unit = {}
    private val outcome = Outcome()
    private var remoteNoMsgs = false
    private var quit = false
    private val awaitingConfirm = ArrayList<String>()

    companion object {
        const val APP_NAME = "PocketNomad"
        const val APP_VERSION = "1.0"
    }

    suspend fun run(): Outcome {
        handshake()
        var myTurn = true
        while (!quit) {
            if (myTurn) sendTurn() else receiveTurn()
            myTurn = !myTurn
        }
        return outcome
    }

    private fun send(line: String) {
        log(">$line")
        write((line + "\r").toByteArray(Charsets.UTF_8))
    }

    private suspend fun nextLine(): String {
        val line = input.readLine()
        log("<$line")
        if (line.startsWith("***")) {
            val msg = line.trimStart('*').trim()
            if (msg.lowercase().contains("secure login failed")) {
                throw ProtocolError("Winlink rejected the password (secure login failed). Check it in Ham settings.")
            }
            throw ProtocolError("Gateway: $msg")
        }
        return line
    }

    private suspend fun handshake() {
        var challenge: String? = null
        var sawSid = false
        while (true) {
            val line = nextLine()
            if (line.startsWith("[") && line.endsWith("]")) {
                sawSid = true
                val code = line.substringAfterLast('-').dropLast(1).uppercase()
                if (!code.contains("B2")) throw ProtocolError("This station does not speak Winlink B2F.")
            } else if (line.startsWith(";PQ:")) {
                challenge = line.substring(4).trim()
            } else if (line.endsWith(">")) {
                if (!sawSid) throw ProtocolError("No Winlink greeting from $target. Is it an RMS gateway?")
                break
            }
        }
        send(";FW: $mycall")
        send("[$APP_NAME-$APP_VERSION-B2FHM$]")
        if (challenge != null) {
            if (password.isNullOrEmpty()) throw ProtocolError("This gateway asks for your Winlink password. Add it in Ham settings.")
            send(";PR: " + WinlinkAuth.response(challenge, password))
        }
        send("; $target DE $mycall ($locator)")
    }

    private suspend fun sendTurn() {
        if (outbound.isEmpty()) {
            if (remoteNoMsgs) { send("FQ"); quit = true } else send("FF")
            return
        }
        val props = outbound.take(5).map { m ->
            val raw = m.encoded()
            val z = Lzhuf.compress(raw)
            Proposal('C', m.mid, raw.size, z.size, z, WinlinkMessage.qEncode(m.subject.ifEmpty { "No title" }).take(80))
        }
        var checksum = 0
        for (p in props) {
            val line = "FC EM ${p.mid} ${p.size} ${p.compressedSize} 0"
            send(line)
            for (c in line.toByteArray()) checksum += c.toInt() and 0xFF
            checksum += 13
        }
        send("F> %02X".format((-checksum) and 0xFF))
        var reply = ""
        while (reply.isEmpty()) {
            val line = nextLine()
            if (line.startsWith("FS ")) reply = line.substring(3)
            else if (line.startsWith(";") || line.isEmpty()) continue
            else throw ProtocolError("Expected an answer to our messages, got: $line")
        }
        parseAnswers(reply, props)
        for (p in props) {
            when (p.answer) {
                '+' -> { writeBlock(p); outcome.sent.add(p.mid); awaitingConfirm.add(p.mid) }
                '-' -> { outcome.alreadyDelivered.add(p.mid); onSent(p.mid) }
                else -> outcome.deferred.add(p.mid)
            }
        }
        val offered = props.map { it.mid }.toSet()
        outbound.removeAll { it.mid in offered }
    }

    private fun parseAnswers(s: String, props: List<Proposal>) {
        var i = 0
        var k = 0
        while (k < s.length) {
            val c = s[k++]
            if (i >= props.size) throw ProtocolError("The gateway answered more messages than we offered.")
            when (c) {
                'Y', 'y', '+' -> props[i].answer = '+'
                'N', 'n', 'R', 'r', '-' -> props[i].answer = '-'
                'L', 'l', '=', 'H', 'h' -> props[i].answer = '='
                'A', 'a', '!' -> {
                    val start = k
                    while (k < s.length && s[k].isDigit()) k++
                    props[i].answer = '+'
                    val off = s.substring(start, k).toIntOrNull() ?: 0
                    props[i].offset = if (off <= 999_999 && off < props[i].compressedSize) off else 0
                }
                else -> throw ProtocolError("Unexpected answer '$c' from the gateway.")
            }
            i++
        }
    }

    private fun writeBlock(p: Proposal) {
        val title = p.title.toByteArray()
        val offset = p.offset.toString().toByteArray()
        val out = java.io.ByteArrayOutputStream()
        out.write(1); out.write(title.size + offset.size + 2); out.write(title); out.write(0); out.write(offset); out.write(0)
        var sum = 0
        var i = p.offset
        while (i < p.data.size) {
            val n = minOf(125, p.data.size - i)
            out.write(2); out.write(n)
            for (j in i until i + n) { out.write(p.data[j].toInt()); sum += p.data[j].toInt() and 0xFF }
            i += n
        }
        out.write(4); out.write((-sum) and 0xFF)
        log(">[${p.mid}: ${p.data.size - p.offset} bytes]")
        write(out.toByteArray())
        progress("Sending ${p.mid}", p.data.size, p.data.size)
    }

    private suspend fun receiveTurn() {
        val props = ArrayList<Proposal>()
        var checksum = 0
        loop@ while (true) {
            val line = nextLine()
            for (mid in awaitingConfirm) onSent(mid)
            awaitingConfirm.clear()
            if (line.isEmpty() || line.startsWith(";")) continue
            if (line.length < 2 || line[0] != 'F') throw ProtocolError("Unexpected line from the gateway: $line")
            when (line.substring(0, 2)) {
                "FA", "FB", "FC", "FD" -> {
                    for (c in line.toByteArray()) checksum += c.toInt() and 0xFF
                    checksum += 13
                    val parts = line.substring(3).split(" ")
                    val p = Proposal(line[1], parts.getOrElse(1) { "" }, parts.getOrNull(2)?.toIntOrNull() ?: 0,
                        parts.getOrNull(3)?.toIntOrNull() ?: 0)
                    p.answer = when {
                        p.code != 'C' || p.mid.isEmpty() -> '='
                        p.mid in knownMids || props.any { it.mid == p.mid } -> '-'
                        else -> '+'
                    }
                    props.add(p)
                }
                "FF" -> { remoteNoMsgs = true; break@loop }
                "FQ" -> { quit = true; return }
                "F>" -> {
                    val theirs = line.substring(2).trim().toIntOrNull(16) ?: -1
                    if (theirs != ((-checksum) and 0xFF)) throw ProtocolError("Checksum error in the gateway's message list.")
                    if (props.isEmpty()) { remoteNoMsgs = true; return }
                    remoteNoMsgs = false
                    send("FS " + props.map { it.answer }.joinToString(""))
                    break@loop
                }
                else -> throw ProtocolError("Unknown command from the gateway: $line")
            }
        }
        for (p in props.filter { it.answer == '+' }) {
            readBlock(p)
            val msg = WinlinkMessage.parse(Lzhuf.decompress(p.data))
            outcome.received.add(msg)
            knownMids.add(p.mid)
            onReceived(msg)
        }
    }

    private suspend fun readBlock(p: Proposal) {
        val first = input.readByte()
        if (first == '*'.code) throw ProtocolError("Gateway: " + input.readLine().trim('*', ' '))
        if (first != 1) throw ProtocolError("Bad start of message block from the gateway.")
        input.readByte()
        p.title = WinlinkMessage.qDecode(String(input.readUntil(0), Charsets.UTF_8))
        input.readUntil(0)
        val data = java.io.ByteArrayOutputStream()
        var sum = 0
        while (true) {
            when (input.readByte()) {
                2 -> {
                    var n = input.readByte()
                    if (n == 0) n = 256
                    repeat(n) { val b = input.readByte(); data.write(b); sum = (sum + b) and 0xFF }
                    progress("Receiving ${p.title}", data.size(), p.compressedSize)
                }
                4 -> {
                    val ck = input.readByte()
                    if ((sum + ck) and 0xFF != 0) throw ProtocolError("Checksum error in a received message.")
                    if (data.size() != p.compressedSize) throw ProtocolError("A received message was the wrong length.")
                    p.data = data.toByteArray()
                    log("<[${p.mid}: ${data.size()} bytes]")
                    return
                }
                else -> throw ProtocolError("Unexpected byte in a message block from the gateway.")
            }
        }
    }
}
