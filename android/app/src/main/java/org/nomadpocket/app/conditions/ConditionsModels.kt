// ConditionsModels.kt
// Pocket Nomad - Conditions module
// SPDX-License-Identifier: Apache-2.0
//
// Snapshots of free, keyless public data: USGS earthquakes, National Weather
// Service alerts, Open-Meteo forecasts, NOAA space weather and NOAA Weather
// Radio transmitters. Downloaded when the user taps Update, then read from the
// phone, so they work with no signal. Port of the iOS Conditions module.

package org.nomadpocket.app.conditions

import org.json.JSONArray
import org.json.JSONObject

data class Quake(
    val id: String, val magnitude: Double, val place: String, val timeMillis: Long, val latitude: Double, val longitude: Double,
    val depthKm: Double, val url: String? = null, val tsunami: Boolean = false, val felt: Int? = null, val significant: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("mag", magnitude).put("place", place).put("time", timeMillis)
        .put("lat", latitude).put("lon", longitude).put("depth", depthKm).put("url", url).put("tsunami", tsunami)
        .put("felt", felt).put("sig", significant)
    companion object {
        fun fromJson(o: JSONObject) = Quake(o.getString("id"), o.getDouble("mag"), o.getString("place"), o.getLong("time"),
            o.getDouble("lat"), o.getDouble("lon"), o.getDouble("depth"), o.optString("url").ifEmpty { null },
            o.optBoolean("tsunami"), if (o.has("felt") && !o.isNull("felt")) o.optInt("felt") else null, o.optBoolean("sig"))
    }
}

data class WeatherAlert(
    val id: String, val event: String, val severity: String, val urgency: String, val headline: String, val detail: String,
    val instruction: String?, val sender: String, val areas: String, val effectiveMillis: Long?, val expiresMillis: Long?,
) {
    /** 0 = most severe, for sorting. */
    val rank: Int get() = when (severity) { "Extreme" -> 0; "Severe" -> 1; "Moderate" -> 2; "Minor" -> 3; else -> 4 }
    fun isExpired(now: Long) = expiresMillis?.let { it < now } ?: false
    fun toJson(): JSONObject = JSONObject().put("id", id).put("event", event).put("severity", severity).put("urgency", urgency)
        .put("headline", headline).put("detail", detail).put("instruction", instruction).put("sender", sender).put("areas", areas)
        .put("effective", effectiveMillis).put("expires", expiresMillis)
    companion object {
        fun fromJson(o: JSONObject) = WeatherAlert(o.getString("id"), o.getString("event"), o.optString("severity", "Unknown"),
            o.optString("urgency", "Unknown"), o.optString("headline"), o.optString("detail"), o.optString("instruction").ifEmpty { null },
            o.optString("sender"), o.optString("areas"), if (o.has("effective") && !o.isNull("effective")) o.optLong("effective") else null,
            if (o.has("expires") && !o.isNull("expires")) o.optLong("expires") else null)
    }
}

data class ForecastHour(
    val timeMillis: Long, val temperatureC: Double, val precipitationMm: Double, val precipitationChance: Int?,
    val windKmh: Double, val gustKmh: Double, val windDirection: Int, val code: Int,
)

data class Forecast(val latitude: Double, val longitude: Double, val hours: List<ForecastHour>) {
    fun toJson(): JSONObject = JSONObject().put("lat", latitude).put("lon", longitude).put("hours", JSONArray(hours.map {
        JSONObject().put("t", it.timeMillis).put("temp", it.temperatureC).put("rain", it.precipitationMm).put("chance", it.precipitationChance)
            .put("wind", it.windKmh).put("gust", it.gustKmh).put("dir", it.windDirection).put("code", it.code)
    }))
    companion object {
        fun fromJson(o: JSONObject): Forecast {
            val a = o.getJSONArray("hours")
            return Forecast(o.getDouble("lat"), o.getDouble("lon"), (0 until a.length()).map { i ->
                val h = a.getJSONObject(i)
                ForecastHour(h.getLong("t"), h.getDouble("temp"), h.getDouble("rain"), if (h.isNull("chance")) null else h.optInt("chance"),
                    h.getDouble("wind"), h.getDouble("gust"), h.getInt("dir"), h.getInt("code"))
            })
        }
    }
}

data class KpPoint(val timeMillis: Long, val kp: Double, val predicted: Boolean)

data class SpaceWeather(
    val kpNow: Double, val kpTimeMillis: Long, val kp: List<KpPoint>, val radio: Int, val radiation: Int, val geomagnetic: Int, val geomagneticOutlook: Int,
) {
    fun toJson(): JSONObject = JSONObject().put("kpNow", kpNow).put("kpTime", kpTimeMillis).put("radio", radio).put("rad", radiation)
        .put("geo", geomagnetic).put("outlook", geomagneticOutlook)
        .put("kp", JSONArray(kp.map { JSONObject().put("t", it.timeMillis).put("kp", it.kp).put("p", it.predicted) }))
    companion object {
        fun fromJson(o: JSONObject): SpaceWeather {
            val a = o.getJSONArray("kp")
            return SpaceWeather(o.getDouble("kpNow"), o.getLong("kpTime"),
                (0 until a.length()).map { val p = a.getJSONObject(it); KpPoint(p.getLong("t"), p.getDouble("kp"), p.getBoolean("p")) },
                o.getInt("radio"), o.getInt("rad"), o.getInt("geo"), o.getInt("outlook"))
        }
    }
}

data class WeatherRadioStation(
    val callsign: String, val name: String, val state: String, val frequency: String, val latitude: Double, val longitude: Double,
    val powerWatts: Int, val status: String, val counties: List<String>,
) {
    fun toJson(): JSONObject = JSONObject().put("call", callsign).put("name", name).put("state", state).put("freq", frequency)
        .put("lat", latitude).put("lon", longitude).put("watts", powerWatts).put("status", status).put("counties", JSONArray(counties))
    companion object {
        fun fromJson(o: JSONObject): WeatherRadioStation {
            val c = o.getJSONArray("counties")
            return WeatherRadioStation(o.getString("call"), o.getString("name"), o.getString("state"), o.getString("freq"), o.getDouble("lat"),
                o.getDouble("lon"), o.getInt("watts"), o.getString("status"), (0 until c.length()).map { c.getString(it) })
        }
    }
}

/** One download of one source, and when it was fetched. */
data class Snapshot<T>(val fetchedAtMillis: Long, val value: T)
