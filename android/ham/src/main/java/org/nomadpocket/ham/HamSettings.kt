// HamSettings.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// The station's settings. A valid callsign plus the licence confirmation
// decide whether anything is ever transmitted; otherwise listen only.
// The Winlink password is encrypted with an Android Keystore key and never
// leaves the phone except as the Winlink login answer. Port of HamSettings.swift.

package org.nomadpocket.ham

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Key-value storage, so tests can run without Android. */
interface HamPrefs {
    fun string(key: String): String?
    fun putString(key: String, value: String?)
}

class SharedHamPrefs(context: Context) : HamPrefs {
    private val sp = context.getSharedPreferences("ham", Context.MODE_PRIVATE)
    override fun string(key: String): String? = sp.getString(key, null)
    override fun putString(key: String, value: String?) { sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
}

class MemoryHamPrefs : HamPrefs {
    val map = HashMap<String, String>()
    override fun string(key: String) = map[key]
    override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

/** Encrypts small secrets. The app uses the Android Keystore; tests use a stand-in. */
interface SecretBox {
    fun seal(plain: String): String
    fun open(sealed: String): String?
}

class KeystoreSecretBox : SecretBox {
    private val alias = "org.nomadpocket.ham.winlink"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }

    override fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.getEncoder().encodeToString(c.iv + c.doFinal(plain.toByteArray()))
    }

    override fun open(sealed: String): String? = try {
        val all = Base64.getDecoder().decode(sealed)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        String(c.doFinal(all.copyOfRange(12, all.size)))
    } catch (_: Exception) { null }
}

data class HamSavedStation(val id: String = java.util.UUID.randomUUID().toString(), val call: String, val path: String = "", val note: String = "") {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("call", call).put("path", path).put("note", note)
    companion object {
        fun fromJson(o: JSONObject) = HamSavedStation(o.optString("id"), o.getString("call"), o.optString("path"), o.optString("note"))
        fun list(s: String?): List<HamSavedStation> = s?.let { j -> JSONArray(j).let { a -> (0 until a.length()).map { fromJson(a.getJSONObject(it)) } } } ?: emptyList()
        fun json(l: List<HamSavedStation>) = JSONArray(l.map { it.toJson() }).toString()
    }
}

class HamSettings(private val prefs: HamPrefs, private val secrets: SecretBox) {
    private fun str(k: String, d: String) = prefs.string(k) ?: d
    private fun int(k: String, d: Int) = prefs.string(k)?.toIntOrNull() ?: d
    private fun bool(k: String, d: Boolean) = prefs.string(k)?.toBooleanStrictOrNull() ?: d

    var callsign by mutableStateOf(str("callsign", ""))
    var licensed by mutableStateOf(bool("licensed", false))
    var aprsSsid by mutableStateOf(int("aprsSsid", 7))
    var aprsPath by mutableStateOf(str("aprsPath", Aprs.DEFAULT_PATH))
    var symbol by mutableStateOf(str("symbol", "/["))
    var beaconComment by mutableStateOf(str("beaconComment", "Pocket Nomad"))
    var beaconMinutes by mutableStateOf(int("beaconMinutes", 0))
    var txDelay by mutableStateOf(int("txDelay", 30))
    var persistence by mutableStateOf(int("persistence", 63))
    var slotTime by mutableStateOf(int("slotTime", 10))
    var window by mutableStateOf(int("window", 4))
    var paclen by mutableStateOf(int("paclen", 128))
    var acceptIncoming by mutableStateOf(bool("acceptIncoming", false))
    var autoAck by mutableStateOf(bool("autoAck", true))
    var gateways by mutableStateOf(HamSavedStation.list(prefs.string("gateways")))
    var nodes by mutableStateOf(HamSavedStation.list(prefs.string("nodes")))
    var lastDevice by mutableStateOf(prefs.string("lastDevice"))
    /** APRS message numbers (1-99999), kept across launches so ids don't repeat soon. */
    var lastMsgCounter: Int
        get() = int("msgCounter", 0)
        set(v) = prefs.putString("msgCounter", v.toString())
    var hasWinlinkPassword by mutableStateOf(prefs.string("winlinkSealed") != null)
        private set

    /** Writes every setting back (called after edits). */
    fun save() {
        prefs.putString("callsign", callsign); prefs.putString("licensed", licensed.toString())
        prefs.putString("aprsSsid", aprsSsid.toString()); prefs.putString("aprsPath", aprsPath)
        prefs.putString("symbol", symbol); prefs.putString("beaconComment", beaconComment)
        prefs.putString("beaconMinutes", beaconMinutes.toString()); prefs.putString("txDelay", txDelay.toString())
        prefs.putString("persistence", persistence.toString()); prefs.putString("slotTime", slotTime.toString())
        prefs.putString("window", window.toString()); prefs.putString("paclen", paclen.toString())
        prefs.putString("acceptIncoming", acceptIncoming.toString()); prefs.putString("autoAck", autoAck.toString())
        prefs.putString("gateways", HamSavedStation.json(gateways)); prefs.putString("nodes", HamSavedStation.json(nodes))
        prefs.putString("lastDevice", lastDevice)
    }

    val baseCall: String get() = callsign.trim().uppercase().substringBefore('-')
    val callsignValid: Boolean get() = isValidCallsign(baseCall)
    val canTransmit: Boolean get() = licensed && callsignValid
    val aprsAddress: Ax25Address? get() = if (canTransmit) Ax25Address(baseCall, aprsSsid.coerceIn(0, 15)) else null
    val stationAddress: Ax25Address? get() = if (canTransmit) Ax25Address(baseCall) else null
    val linkConfig: Ax25LinkConfig get() = Ax25LinkConfig(window = window.coerceIn(1, 7), paclen = paclen.coerceIn(32, 256))

    var winlinkPassword: String?
        get() = prefs.string("winlinkSealed")?.let { secrets.open(it) }
        set(v) {
            prefs.putString("winlinkSealed", v?.takeIf { it.isNotEmpty() }?.let { secrets.seal(it) })
            hasWinlinkPassword = !v.isNullOrEmpty()
        }

    companion object {
        fun isValidCallsign(c: String) = c.length <= 6 && Regex("^[A-Z0-9]{1,3}[0-9][A-Z0-9]{0,3}[A-Z]$").matches(c)
    }
}
