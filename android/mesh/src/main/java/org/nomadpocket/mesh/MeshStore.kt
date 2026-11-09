// MeshStore.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Saves messages, nodes and radio info as JSON in the app's private files
// directory (files/mesh/mesh.json). Ported from MeshStore.swift.

package org.nomadpocket.mesh

import org.json.JSONArray
import org.json.JSONObject
import org.nomadpocket.mesh.proto.Bytes
import org.nomadpocket.mesh.proto.MeshCoreContactType
import java.io.File

data class MeshSnapshot(
    val messages: List<MeshMessage> = emptyList(),
    val nodes: List<MeshNode> = emptyList(),
    val radio: MeshRadioInfo = MeshRadioInfo(),
    /** Last read time per conversation key, for unread badges. */
    val lastRead: Map<String, Long> = emptyMap(),
)

class MeshStore(val directory: File) {
    private val file: File get() = File(directory, "mesh.json")

    fun load(): MeshSnapshot = try {
        if (!file.exists()) MeshSnapshot() else decode(JSONObject(file.readText()))
    } catch (_: Exception) {
        MeshSnapshot()
    }

    fun save(snapshot: MeshSnapshot) {
        val trimmed = snapshot.copy(messages = capped(snapshot.messages))
        directory.mkdirs()
        atomicWrite(file, encode(trimmed).toString())
    }

    companion object {
        const val MAX_MESSAGES = 2000

        /** Keeps the newest `limit`. */
        fun capped(messages: List<MeshMessage>, limit: Int = MAX_MESSAGES): List<MeshMessage> =
            if (messages.size <= limit) messages else messages.takeLast(limit)

        fun encode(s: MeshSnapshot): JSONObject = JSONObject().apply {
            put("messages", JSONArray().apply { s.messages.forEach { put(encodeMessage(it)) } })
            put("nodes", JSONArray().apply { s.nodes.forEach { put(encodeNode(it)) } })
            put("radio", encodeRadio(s.radio))
            put("lastRead", JSONObject().apply { s.lastRead.forEach { (k, v) -> put(k, v) } })
        }

        fun decode(o: JSONObject): MeshSnapshot {
            val messages = o.optJSONArray("messages")?.let { a -> (0 until a.length()).mapNotNull { runCatching { decodeMessage(a.getJSONObject(it)) }.getOrNull() } } ?: emptyList()
            val nodes = o.optJSONArray("nodes")?.let { a -> (0 until a.length()).mapNotNull { runCatching { decodeNode(a.getJSONObject(it)) }.getOrNull() } } ?: emptyList()
            val radio = o.optJSONObject("radio")?.let { decodeRadio(it) } ?: MeshRadioInfo()
            val lastRead = mutableMapOf<String, Long>()
            o.optJSONObject("lastRead")?.let { lr -> lr.keys().forEach { k -> lastRead[k] = lr.getLong(k) } }
            return MeshSnapshot(messages, nodes, radio, lastRead)
        }

        private fun encodeDelivery(d: MeshDeliveryState): JSONObject = JSONObject().apply {
            when (d) {
                MeshDeliveryState.Sent -> put("state", "sent")
                MeshDeliveryState.HeardByMesh -> put("state", "heardByMesh")
                MeshDeliveryState.NotRepeated -> put("state", "notRepeated")
                MeshDeliveryState.Delivered -> put("state", "delivered")
                MeshDeliveryState.Received -> put("state", "received")
                is MeshDeliveryState.Failed -> { put("state", "failed"); put("reason", d.reason) }
            }
        }

        private fun decodeDelivery(o: JSONObject): MeshDeliveryState = when (o.optString("state")) {
            "sent" -> MeshDeliveryState.Sent
            "heardByMesh" -> MeshDeliveryState.HeardByMesh
            "notRepeated" -> MeshDeliveryState.NotRepeated
            "delivered" -> MeshDeliveryState.Delivered
            "failed" -> MeshDeliveryState.Failed(o.optString("reason"))
            else -> MeshDeliveryState.Received
        }

        private fun encodeMessage(m: MeshMessage) = JSONObject().apply {
            put("id", m.id); put("packetID", m.packetID); put("from", m.from); put("to", m.to)
            put("channel", m.channel); put("text", m.text); put("date", m.dateMillis)
            put("isOutgoing", m.isOutgoing); put("delivery", encodeDelivery(m.delivery))
            m.rxSNR?.let { put("rxSNR", it.toDouble()) }
            m.hopsAway?.let { put("hopsAway", it) }
            m.senderName?.let { put("senderName", it) }
        }

        private fun decodeMessage(o: JSONObject) = MeshMessage(
            id = o.getString("id"), packetID = o.getLong("packetID"), from = o.getLong("from"), to = o.getLong("to"),
            channel = o.getLong("channel"), text = o.getString("text"), dateMillis = o.getLong("date"),
            isOutgoing = o.getBoolean("isOutgoing"), delivery = decodeDelivery(o.getJSONObject("delivery")),
            rxSNR = if (o.has("rxSNR")) o.getDouble("rxSNR").toFloat() else null,
            hopsAway = if (o.has("hopsAway")) o.getLong("hopsAway") else null,
            senderName = if (o.has("senderName")) o.getString("senderName") else null,
        )

        private fun encodeNode(n: MeshNode) = JSONObject().apply {
            put("num", n.num); put("userID", n.userID); put("longName", n.longName); put("shortName", n.shortName)
            put("hwModel", n.hwModel); put("hasPublicKey", n.hasPublicKey)
            n.lastHeardMillis?.let { put("lastHeard", it) }
            n.snr?.let { put("snr", it.toDouble()) }
            n.hopsAway?.let { put("hopsAway", it) }
            n.latitude?.let { put("latitude", it) }
            n.longitude?.let { put("longitude", it) }
            n.altitude?.let { put("altitude", it) }
            n.batteryLevel?.let { put("batteryLevel", it) }
            n.publicKeyPrefix?.let { put("publicKeyPrefix", it.hex()) }
            n.meshCoreType?.let { put("meshCoreType", it.raw) }
            n.role?.let { put("role", it) }
        }

        private fun decodeNode(o: JSONObject) = MeshNode(
            num = o.getLong("num"), userID = o.optString("userID"), longName = o.optString("longName"),
            shortName = o.optString("shortName"), hwModel = o.optLong("hwModel"),
            lastHeardMillis = if (o.has("lastHeard")) o.getLong("lastHeard") else null,
            snr = if (o.has("snr")) o.getDouble("snr").toFloat() else null,
            hopsAway = if (o.has("hopsAway")) o.getLong("hopsAway") else null,
            latitude = if (o.has("latitude")) o.getDouble("latitude") else null,
            longitude = if (o.has("longitude")) o.getDouble("longitude") else null,
            altitude = if (o.has("altitude")) o.getInt("altitude") else null,
            batteryLevel = if (o.has("batteryLevel")) o.getLong("batteryLevel") else null,
            hasPublicKey = o.optBoolean("hasPublicKey"),
            publicKeyPrefix = if (o.has("publicKeyPrefix")) Bytes.fromHex(o.getString("publicKeyPrefix")) else null,
            meshCoreType = if (o.has("meshCoreType")) MeshCoreContactType.of(o.getInt("meshCoreType")) else null,
            role = if (o.has("role")) o.getLong("role") else null,
        )

        private fun encodeRadio(r: MeshRadioInfo) = JSONObject().apply {
            r.firmware?.let { put("firmware", it.rawValue) }
            put("myNodeNum", r.myNodeNum); put("firmwareVersion", r.firmwareVersion)
            r.region?.let { put("region", it) }
            r.regionName?.let { put("regionName", it) }
            r.hopLimit?.let { put("hopLimit", it) }
            r.meshCoreParams?.let { put("mcFreq", it.frequencyMHz); put("mcBw", it.bandwidthKHz); put("mcSf", it.spreadingFactor); put("mcCr", it.codingRate) }
            r.txPowerDbm?.let { put("txPower", it) }
            r.maxTxPowerDbm?.let { put("maxTxPower", it) }
            r.modemPreset?.let { put("modemPreset", it) }
            r.usePreset?.let { put("usePreset", it) }
            r.txEnabled?.let { put("txEnabled", it) }
            put("primaryChannelName", r.primaryChannelName)
            put("channelNames", JSONObject().apply { r.channelNames.forEach { (k, v) -> put(k.toString(), v) } })
        }

        private fun decodeRadio(o: JSONObject): MeshRadioInfo {
            val names = mutableMapOf<Int, String>()
            o.optJSONObject("channelNames")?.let { c -> c.keys().forEach { k -> k.toIntOrNull()?.let { names[it] = c.getString(k) } } }
            return MeshRadioInfo(
                firmware = firmwareOf(o.optString("firmware", "")),
                myNodeNum = o.optLong("myNodeNum"), firmwareVersion = o.optString("firmwareVersion"),
                region = if (o.has("region")) o.getLong("region") else null,
                regionName = if (o.has("regionName")) o.getString("regionName") else null,
                hopLimit = if (o.has("hopLimit")) o.getLong("hopLimit") else null,
                meshCoreParams = if (o.has("mcFreq")) MeshCoreRadioParams(o.getDouble("mcFreq"), o.getDouble("mcBw"), o.getInt("mcSf"), o.getInt("mcCr")) else null,
                txPowerDbm = if (o.has("txPower")) o.getInt("txPower") else null,
                maxTxPowerDbm = if (o.has("maxTxPower")) o.getInt("maxTxPower") else null,
                modemPreset = if (o.has("modemPreset")) o.getLong("modemPreset") else null,
                usePreset = if (o.has("usePreset")) o.getBoolean("usePreset") else null,
                txEnabled = if (o.has("txEnabled")) o.getBoolean("txEnabled") else null,
                primaryChannelName = o.optString("primaryChannelName"), channelNames = names,
            )
        }
    }
}

/** Writes via a temp file and rename, so a crash never leaves half a file. */
internal fun atomicWrite(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
    }
}
