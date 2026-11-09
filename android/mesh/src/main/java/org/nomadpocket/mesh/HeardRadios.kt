// HeardRadios.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Which radios the user's radios actually heard, worked out from the coverage
// recordings each time the map is drawn (so recordings made under older rules
// are re-read under these ones without rewriting the saved file).
//
// A MeshCore flood packet carries the hashes of the repeaters it passed; the
// last one is the repeater in radio range of us. A hash is only one to three
// bytes of a public key, so many radios across the mesh share it. It is
// credited to a known contact only when that contact's key starts with the
// hash, it is a repeater or room server (companions never repeat), its
// position is known, and it is within RANGE_METERS of where the phone was
// when the packet was heard (the nearest one). Otherwise it is an "unidentified repeater": named by its hash in
// text, never drawn at a guessed place.
//
// Saved recordings hold one node number per sample:
// - 0: nothing named the sender. Coverage only.
// - hops == 0: the sender itself, heard directly (a zero-hop advert or message).
// - hops > 0 on MeshCore: the last-hop repeater. New recordings store the hash
//   padded into a stand-in number (MeshCoreContact.nodeNumForHash); older ones
//   may hold a contact's number instead, whose first key byte is that hash.
// - hops unknown: a message sent along a stored route, whose last hop is not
//   known. Coverage only.

package org.nomadpocket.mesh

import org.nomadpocket.mesh.proto.Bytes
import org.nomadpocket.mesh.proto.MeshCoreContact
import org.nomadpocket.mesh.proto.MeshCoreContactType

/** A MeshCore repeater heard as the last hop that matched no known radio near where it was heard. */
data class UnidentifiedRepeater(
    /** The path hash, lowercase hex ("3f", "3fa1"). */
    val hash: String,
    /** Recordings credited to it. */
    val samples: Int,
    val lastHeardMillis: Long,
) {
    val label: String get() = "Repeater $hash, not identified"
}

/** The map's two radio layers plus what could not be placed. */
data class HeardRadios(
    /** "Heard directly": last-hop repeaters and zero-hop senders, each resolved to a known, located radio. */
    val direct: List<MapNode> = emptyList(),
    /** "Known on the mesh": other radios with a position, learned through relayed adverts. */
    val known: List<MapNode> = emptyList(),
    /** Last-hop repeaters heard but not identified; shown as text only. */
    val unidentified: List<UnidentifiedRepeater> = emptyList(),
    /** Recordings that name no radio (coverage points only). */
    val coverageOnly: Int = 0,
)

/** What one recording says about which radio was heard. */
sealed class HeardCredit {
    /** No sender known: a coverage point only, never a radio on the map. */
    data object CoverageOnly : HeardCredit()
    /** One of the user's own radios (never drawn). */
    data object Own : HeardCredit()
    /** A known radio with a position, heard directly. */
    data class Radio(val node: MeshNode) : HeardCredit()
    /** A last-hop repeater whose hash matched no known radio in range. */
    data class Unidentified(val hash: Bytes) : HeardCredit()
}

object HeardResolver {
    /** How far a repeater can plausibly be from the phone that heard it. */
    const val RANGE_METERS = 40_000.0

    /**
     * The last-hop hash a saved MeshCore node number stands for: a known
     * contact's first key byte, or the hash padded into a stand-in number
     * (1 to 3 bytes, trailing zero bytes dropped). A full node number with no
     * contact behind it gives its first key byte (the low byte).
     */
    fun hashOf(node: Long, contacts: Map<Long, MeshNode>): Bytes? {
        if (node == 0L) return null
        contacts[node]?.publicKeyPrefix?.takeIf { !it.isEmpty() }?.let { return it.prefix(1) }
        val size = when {
            node > 0xFFFFFFL -> 1
            node > 0xFFFFL -> 3
            node > 0xFFL -> 2
            else -> 1
        }
        return Bytes(ByteArray(size) { (node shr (8 * it)).toByte() })
    }

    /**
     * The known radio a last-hop hash most plausibly is, heard at (lat, lon):
     * a repeater or room server (companions never repeat) whose key starts
     * with the hash, position known, within range; the nearest. Null when none qualifies.
     */
    fun resolve(hash: Bytes, latitude: Double, longitude: Double, contacts: Collection<MeshNode>): MeshNode? {
        if (hash.isEmpty()) return null
        val inRange = contacts.mapNotNull { c ->
            val key = c.publicKeyPrefix ?: return@mapNotNull null
            val lat = c.latitude ?: return@mapNotNull null
            val lon = c.longitude ?: return@mapNotNull null
            if (key.isEmpty() || !key.startsWith(hash)) return@mapNotNull null
            if (c.meshCoreType != MeshCoreContactType.REPEATER && c.meshCoreType != MeshCoreContactType.ROOM) return@mapNotNull null
            val d = CoverageShare.distance(latitude, longitude, lat, lon)
            if (d <= RANGE_METERS) c to d else null
        }
        return inRange.minByOrNull { it.second }?.first
    }

    /** Which radio one recording credits, under the rules at the top of this file. */
    fun credit(
        record: CoverageRecord,
        contacts: Map<Long, MeshNode>,
        isOwnRadio: (Long, Firmware) -> Boolean = { _, _ -> false },
    ): HeardCredit {
        val node = record.node
        if (node == 0L) return HeardCredit.CoverageOnly
        if (isOwnRadio(node, record.firmware)) return HeardCredit.Own
        val hops = record.hops ?: return HeardCredit.CoverageOnly
        if (hops == 0L) {
            val n = contacts[node]?.takeIf { it.hasPosition } ?: return HeardCredit.CoverageOnly
            return HeardCredit.Radio(n)
        }
        // Meshtastic samples name the original sender, not the radio in range.
        if (record.firmware != Firmware.MESHCORE) return HeardCredit.CoverageOnly
        val hash = hashOf(node, contacts) ?: return HeardCredit.CoverageOnly
        if (isOwnRadio(MeshCoreContact.nodeNumForHash(hash.array), Firmware.MESHCORE)) return HeardCredit.Own
        val radio = resolve(hash, record.latitude, record.longitude, contacts.values)
            ?: return HeardCredit.Unidentified(hash)
        if (isOwnRadio(radio.num, Firmware.MESHCORE)) return HeardCredit.Own
        return HeardCredit.Radio(radio)
    }

    /**
     * Both layers from the recordings and each network's radios.
     * `contacts` holds every radio each link knows (blocked ones already left
     * out); `alsoDirect` adds radios known to be heard directly right now (zero
     * hops on the radio's own list). Own and opted-out radios are never included.
     */
    fun derive(
        samples: List<CoverageRecord>,
        contacts: Map<Firmware, Map<Long, MeshNode>>,
        isOwnRadio: (Long, Firmware) -> Boolean = { _, _ -> false },
        alsoDirect: List<Pair<Firmware, MeshNode>> = emptyList(),
    ): HeardRadios {
        fun shown(n: MeshNode, fw: Firmware) = n.hasPosition && !isOwnRadio(n.num, fw) && !OptOut.optsOut(n.displayName)
        val direct = LinkedHashMap<Pair<Firmware, Long>, MapNode>()
        fun addDirect(fw: Firmware, n: MeshNode, heardAt: Long?) {
            if (!shown(n, fw)) return
            val m = n.toMapNode(fw) ?: return
            val key = fw to n.num
            val latest = listOfNotNull(direct[key]?.lastHeardMillis, m.lastHeardMillis, heardAt).maxOrNull()
            direct[key] = m.copy(lastHeardMillis = latest)
        }
        val unidentified = mutableMapOf<String, UnidentifiedRepeater>()
        var coverageOnly = 0
        for (s in samples) {
            when (val c = credit(s, contacts[s.firmware] ?: emptyMap(), isOwnRadio)) {
                HeardCredit.CoverageOnly -> coverageOnly++
                HeardCredit.Own -> {}
                is HeardCredit.Radio -> if (shown(c.node, s.firmware)) addDirect(s.firmware, c.node, s.dateMillis) else coverageOnly++
                is HeardCredit.Unidentified -> {
                    val hex = c.hash.hex()
                    val was = unidentified[hex]
                    unidentified[hex] = UnidentifiedRepeater(hex, (was?.samples ?: 0) + 1, maxOf(was?.lastHeardMillis ?: Long.MIN_VALUE, s.dateMillis))
                }
            }
        }
        for ((fw, n) in alsoDirect) addDirect(fw, n, null)
        val byRecent = compareByDescending<MapNode> { it.lastHeardMillis ?: Long.MIN_VALUE }
        val known = contacts.flatMap { (fw, map) -> map.values.filter { shown(it, fw) && (fw to it.num) !in direct }.mapNotNull { it.toMapNode(fw) } }
        return HeardRadios(
            direct = direct.values.sortedWith(byRecent),
            known = known.sortedWith(byRecent),
            unidentified = unidentified.values.sortedWith(compareByDescending<UnidentifiedRepeater> { it.samples }.thenBy { it.hash }),
            coverageOnly = coverageOnly,
        )
    }
}
