// MeshPrefs.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// The few small settings the mesh code keeps (last radio, blocked radios,
// coverage switches), behind an interface so tests can use memory instead of
// SharedPreferences (the Android stand-in for UserDefaults).

package org.nomadpocket.mesh

import android.content.SharedPreferences
import androidx.core.content.edit

interface MeshPrefs {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
    fun contains(key: String): Boolean
    fun remove(key: String)
}

class SharedMeshPrefs(private val sp: SharedPreferences) : MeshPrefs {
    override fun getString(key: String): String? = sp.getString(key, null)
    override fun putString(key: String, value: String?) = sp.edit { if (value == null) remove(key) else putString(key, value) }
    override fun getBoolean(key: String, default: Boolean) = sp.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = sp.edit { putBoolean(key, value) }
    override fun getInt(key: String, default: Int) = sp.getInt(key, default)
    override fun putInt(key: String, value: Int) = sp.edit { putInt(key, value) }
    override fun getLong(key: String, default: Long) = sp.getLong(key, default)
    override fun putLong(key: String, value: Long) = sp.edit { putLong(key, value) }
    override fun contains(key: String) = sp.contains(key)
    override fun remove(key: String) = sp.edit { remove(key) }
}

/** In-memory prefs, for tests and previews. */
class MemoryMeshPrefs : MeshPrefs {
    private val map = mutableMapOf<String, Any>()
    override fun getString(key: String) = map[key] as? String
    override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun getLong(key: String, default: Long) = map[key] as? Long ?: default
    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun contains(key: String) = map.containsKey(key)
    override fun remove(key: String) { map.remove(key) }
}

/**
 * One radio slot's view of the prefs: every key gets a prefix ("meshcore."),
 * so each radio keeps its own remembered device, auto-reconnect and blocked list.
 */
class ScopedMeshPrefs(private val base: MeshPrefs, private val prefix: String) : MeshPrefs {
    override fun getString(key: String) = base.getString(prefix + key)
    override fun putString(key: String, value: String?) = base.putString(prefix + key, value)
    override fun getBoolean(key: String, default: Boolean) = base.getBoolean(prefix + key, default)
    override fun putBoolean(key: String, value: Boolean) = base.putBoolean(prefix + key, value)
    override fun getInt(key: String, default: Int) = base.getInt(prefix + key, default)
    override fun putInt(key: String, value: Int) = base.putInt(prefix + key, value)
    override fun getLong(key: String, default: Long) = base.getLong(prefix + key, default)
    override fun putLong(key: String, value: Long) = base.putLong(prefix + key, value)
    override fun contains(key: String) = base.contains(prefix + key)
    override fun remove(key: String) = base.remove(prefix + key)
}
