// HiddenRadios.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Radios the user marked "This is mine: hide it from the map": their other
// phones and their own repeaters. A hidden radio counts as one of the user's
// own (MeshRadios.isOwnRadio), so it is never recorded as coverage, never
// drawn on the map's radio layers, never credited by the heard-radio resolver,
// left out of the coverage cells (private and shared) and dropped from the
// community-map layer. Kept on the phone only, in the mesh prefs as JSON.

package org.nomadpocket.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.nomadpocket.mesh.proto.Bytes
import org.nomadpocket.mesh.proto.MeshCoreContact

/** One hidden radio. `keyHex` is a MeshCore public-key prefix (lowercase hex), when known. */
data class HiddenRadio(
    val firmware: Firmware,
    val num: Long,
    val name: String,
    val keyHex: String? = null,
) {
    /** "<firmware>.<num>", e.g. "meshtastic.1002094074". */
    val id: String get() = key(firmware, num)

    /** The key bytes, when a usable MeshCore key prefix is known. */
    internal val keyBytes: Bytes?
        get() = keyHex?.takeIf { firmware == Firmware.MESHCORE }?.let { Bytes.fromHex(it) }?.takeIf { !it.isEmpty() }

    companion object {
        fun key(firmware: Firmware, num: Long): String = "${firmware.rawValue}.$num"
    }
}

class HiddenRadios(private val prefs: MeshPrefs) {
    private val _list = MutableStateFlow(load())

    /** Hidden radios, in the order they were hidden. */
    val flow: StateFlow<List<HiddenRadio>> = _list.asStateFlow()
    val list: List<HiddenRadio> get() = _list.value

    fun contains(firmware: Firmware, num: Long): Boolean = list.any { it.firmware == firmware && it.num == num }

    /** Hides a radio (or updates its name and key when already hidden). */
    fun hide(radio: HiddenRadio) {
        if (radio.num == 0L) return
        val clean = radio.copy(keyHex = radio.keyHex?.lowercase()?.takeIf { it.isNotEmpty() })
        val i = list.indexOfFirst { it.id == clean.id }
        val next = list.toMutableList()
        if (i >= 0) next[i] = clean.copy(keyHex = clean.keyHex ?: list[i].keyHex) else next.add(clean)
        save(next)
    }

    fun unhide(firmware: Firmware, num: Long) {
        if (!contains(firmware, num)) return
        save(list.filterNot { it.firmware == firmware && it.num == num })
    }

    /**
     * A hidden radio, on the network the recording or node is from: its
     * number, or on MeshCore a 1-3 byte path hash (MeshCoreContact.nodeNumForHash)
     * that is the start of a hidden radio's public key, the same rule
     * MeshRadios.isOwnRadio uses for the phone's own MeshCore key.
     */
    fun matches(node: Long, firmware: Firmware): Boolean = matches(list, node, firmware)

    /** What the community-map filter needs: hidden Meshtastic numbers and MeshCore key prefixes. */
    val own: OwnRadios get() = own(list)

    private fun load(): List<HiddenRadio> = prefs.getString(KEY)?.let { runCatching { decode(it) }.getOrNull() } ?: emptyList()

    private fun save(next: List<HiddenRadio>) {
        _list.value = next
        prefs.putString(KEY, encode(next))
    }

    companion object {
        const val KEY = "mesh.hiddenRadios"

        fun matches(list: List<HiddenRadio>, node: Long, firmware: Firmware): Boolean {
            if (node == 0L) return false
            for (h in list) {
                if (h.firmware != firmware) continue
                if (h.num == node) return true
                val key = h.keyBytes ?: continue
                if (MeshCoreContact.nodeNum(key.array) == node) return true
                if ((1..3).any { MeshCoreContact.nodeNumForHash(key.prefix(it).array) == node }) return true
            }
            return false
        }

        fun own(list: List<HiddenRadio>): OwnRadios = OwnRadios(
            nodeNums = list.filter { it.firmware == Firmware.MESHTASTIC }.map { it.num }.toSet(),
            meshCoreKeyPrefixes = list.filter { it.firmware == Firmware.MESHCORE }.mapNotNull { it.keyBytes?.hex() }.toSet(),
        )

        fun encode(list: List<HiddenRadio>): String = JSONArray().apply {
            list.forEach { h ->
                put(JSONObject().put("firmware", h.firmware.rawValue).put("num", h.num).put("name", h.name)
                    .apply { h.keyHex?.let { put("keyHex", it) } })
            }
        }.toString()

        fun decode(s: String): List<HiddenRadio> {
            val a = JSONArray(s)
            return (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val fw = Firmware.entries.firstOrNull { it.rawValue == o.optString("firmware") } ?: return@mapNotNull null
                HiddenRadio(fw, o.getLong("num"), o.optString("name"), o.optString("keyHex").takeIf { it.isNotEmpty() })
            }
        }
    }
}

/** Both sets together: the phone's own radios plus hidden ones. */
operator fun OwnRadios.plus(other: OwnRadios): OwnRadios =
    OwnRadios(nodeNums + other.nodeNums, meshCoreKeyPrefixes + other.meshCoreKeyPrefixes)
