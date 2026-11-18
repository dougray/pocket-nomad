// Favorites.kt
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// Places the user stars: Home, Camp, Rally point, Car and anything else. Kept on the
// phone only. Port of Favorites.swift.

package org.nomadpocket.app.map

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class FavoriteKind(val label: String, val quick: Boolean) {
    HOME("Home", true), CAMP("Camp", true), RALLY("Rally point", true), CAR("Car", true),
    WATER("Water", false), SHELTER("Shelter", false), OTHER("Favorite", false);
}

data class Favorite(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: FavoriteKind = FavoriteKind.OTHER,
    val latitude: Double,
    val longitude: Double,
    val note: String = "",
    val created: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind.name).put("lat", latitude).put("lon", longitude)
        .put("note", note).put("created", created)

    companion object {
        fun fromJson(o: JSONObject) = Favorite(o.getString("id"), o.getString("name"),
            runCatching { FavoriteKind.valueOf(o.optString("kind")) }.getOrDefault(FavoriteKind.OTHER),
            o.getDouble("lat"), o.getDouble("lon"), o.optString("note"), o.optLong("created"))
    }
}

class FavoritesStore(private val file: File) {
    var favorites: List<Favorite> = load()
        private set

    private fun load(): List<Favorite> = try {
        val a = JSONArray(file.readText()); (0 until a.length()).map { Favorite.fromJson(a.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    private fun save() { runCatching { file.parentFile?.mkdirs(); file.writeText(JSONArray(favorites.map { it.toJson() }).toString()) } }

    /** Quick spots first (Home, Camp, Rally point, Car), then the rest by name. */
    val sorted: List<Favorite>
        get() = favorites.sortedWith(compareBy({ if (it.kind.quick) it.kind.ordinal else 99 }, { it.name.lowercase() }))

    fun add(name: String, kind: FavoriteKind = FavoriteKind.OTHER, latitude: Double, longitude: Double, note: String = ""): Favorite {
        val kept = if (kind.quick) favorites.filterNot { it.kind == kind } else favorites // one Home, one Car...
        val f = Favorite(name = name.trim().ifEmpty { kind.label }, kind = kind, latitude = latitude, longitude = longitude, note = note)
        favorites = kept + f
        save()
        return f
    }

    fun remove(id: String) { favorites = favorites.filterNot { it.id == id }; save() }
    fun rename(id: String, name: String) { if (name.isNotBlank()) { favorites = favorites.map { if (it.id == id) it.copy(name = name.trim()) else it }; save() } }
    fun setNote(id: String, note: String) { favorites = favorites.map { if (it.id == id) it.copy(note = note) else it }; save() }

    /** A favorite within [metres] of a position (so starring the same spot twice is a no-op). */
    fun near(latitude: Double, longitude: Double, metres: Double = 20.0): Favorite? =
        favorites.firstOrNull { KnownRadioParser.distance(it.latitude, it.longitude, latitude, longitude) <= metres }

    fun isFavorite(latitude: Double, longitude: Double) = near(latitude, longitude) != null

    /** Stars a place, or un-stars it if it already is one. Returns whether it is now a favorite. */
    fun toggle(name: String, latitude: Double, longitude: Double): Boolean {
        near(latitude, longitude)?.let { remove(it.id); return false }
        add(name, latitude = latitude, longitude = longitude)
        return true
    }

    fun search(text: String): List<Favorite> {
        val q = text.trim()
        if (q.isEmpty()) return sorted
        return sorted.filter { it.name.contains(q, true) || it.kind.label.contains(q, true) || it.note.contains(q, true) }
    }

    fun exportGeoJson(): String {
        val features = JSONArray(favorites.map {
            JSONObject().put("type", "Feature")
                .put("properties", JSONObject().put("name", it.name).put("kind", it.kind.label).put("note", it.note))
                .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray(listOf(it.longitude, it.latitude))))
        })
        return JSONObject().put("type", "FeatureCollection").put("features", features).toString(2)
    }

    companion object {
        @Volatile private var shared: FavoritesStore? = null
        fun get(context: android.content.Context): FavoritesStore =
            shared ?: synchronized(this) { shared ?: FavoritesStore(File(context.applicationContext.filesDir, "favorites.json")).also { shared = it } }
    }
}
