// ConditionsParsers.kt
// Pocket Nomad - Conditions module
// SPDX-License-Identifier: Apache-2.0
//
// Pure parsing and small calculations for the conditions snapshots, tested on
// saved real responses. No network here. Port of ConditionsParsers.swift.

package org.nomadpocket.app.conditions

import org.json.JSONArray
import org.json.JSONObject
import org.nomadpocket.app.map.KnownRadioParser
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object ConditionsParsers {
    class Failure(message: String) : Exception(message)

    /** Services that need a point get it rounded to 0.1 degree (about 11 km / 7 miles). */
    fun rounded(degrees: Double): Double = Math.round(degrees * 10) / 10.0

    private fun km(aLat: Double, aLon: Double, bLat: Double, bLon: Double) = KnownRadioParser.distance(aLat, aLon, bLat, bLon) / 1000.0
    fun distanceKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double) = km(aLat, aLon, bLat, bLon)

    private fun JSONObject.num(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null

    // MARK: USGS earthquakes

    fun parseQuakes(json: String, significant: Boolean = false): List<Quake> {
        val root = try { JSONObject(json) } catch (_: Exception) { throw Failure("Not an earthquake feed.") }
        val features = root.optJSONArray("features") ?: throw Failure("Not an earthquake feed.")
        return (0 until features.length()).mapNotNull { i ->
            val f = features.optJSONObject(i) ?: return@mapNotNull null
            val p = f.optJSONObject("properties") ?: return@mapNotNull null
            val g = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val mag = p.num("mag") ?: return@mapNotNull null
            val time = p.num("time")?.toLong() ?: return@mapNotNull null
            if (g.length() < 2) return@mapNotNull null
            Quake(f.optString("id", "$time"), mag, p.str("place") ?: "Unknown place", time, g.getDouble(1), g.getDouble(0),
                if (g.length() > 2) g.optDouble(2) else 0.0, p.str("url"), p.optInt("tsunami") == 1,
                if (p.has("felt") && !p.isNull("felt")) p.optInt("felt") else null, significant)
        }
    }

    fun nearby(quakes: List<Quake>, latitude: Double, longitude: Double, radiusKm: Double, minMagnitude: Double): List<Quake> =
        quakes.filter { it.significant || (it.magnitude >= minMagnitude && km(latitude, longitude, it.latitude, it.longitude) <= radiusKm) }
            .sortedByDescending { it.timeMillis }

    fun merge(week: List<Quake>, significant: List<Quake>): List<Quake> {
        val byId = LinkedHashMap<String, Quake>()
        week.forEach { byId[it.id] = it }
        significant.forEach { byId[it.id] = it }
        return byId.values.toList()
    }

    // MARK: National Weather Service alerts

    private fun isoMillis(s: String?): Long? {
        s ?: return null
        for (f in listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")) {
            try { return SimpleDateFormat(f, Locale.US).parse(s)!!.time } catch (_: Exception) {}
        }
        return null
    }

    fun parseAlerts(json: String): List<WeatherAlert> {
        val root = try { JSONObject(json) } catch (_: Exception) { throw Failure("Not an alerts feed.") }
        val features = root.optJSONArray("features") ?: throw Failure("Not an alerts feed.")
        return (0 until features.length()).mapNotNull { i ->
            val f = features.optJSONObject(i) ?: return@mapNotNull null
            val p = f.optJSONObject("properties") ?: return@mapNotNull null
            val event = p.str("event") ?: return@mapNotNull null
            val instruction = p.str("instruction")?.trim()?.ifEmpty { null }
            WeatherAlert(p.str("id") ?: f.str("id") ?: "$i-$event", event, p.str("severity") ?: "Unknown", p.str("urgency") ?: "Unknown",
                p.str("headline") ?: event, (p.str("description") ?: "").trim(), instruction, p.str("senderName") ?: "National Weather Service",
                p.str("areaDesc") ?: "", isoMillis(p.str("onset")) ?: isoMillis(p.str("effective")), isoMillis(p.str("ends")) ?: isoMillis(p.str("expires")))
        }.sortedWith(compareBy({ it.rank }, { it.expiresMillis ?: Long.MAX_VALUE }))
    }

    // MARK: Open-Meteo forecast

    fun parseForecast(json: String): Forecast {
        val root = try { JSONObject(json) } catch (_: Exception) { throw Failure("Not a forecast.") }
        val hourly = root.optJSONObject("hourly") ?: throw Failure("Not a forecast.")
        val times = hourly.optJSONArray("time") ?: throw Failure("Not a forecast.")
        val lat = root.num("latitude") ?: throw Failure("Not a forecast.")
        val lon = root.num("longitude") ?: throw Failure("Not a forecast.")
        val offsetMs = root.optInt("utc_offset_seconds", 0) * 1000L
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun v(k: String, i: Int): Double? = hourly.optJSONArray(k)?.let { if (i < it.length() && !it.isNull(i)) it.optDouble(i).takeIf { d -> !d.isNaN() } else null }
        val hours = ArrayList<ForecastHour>()
        for (i in 0 until times.length()) {
            // Local wall-clock time, shifted by the place's UTC offset to a true instant.
            val local = try { fmt.parse(times.getString(i))!!.time } catch (_: Exception) { continue }
            val temp = v("temperature_2m", i) ?: continue
            hours.add(ForecastHour(local - offsetMs, temp, v("precipitation", i) ?: 0.0, v("precipitation_probability", i)?.toInt(),
                v("wind_speed_10m", i) ?: 0.0, v("wind_gusts_10m", i) ?: 0.0, (v("wind_direction_10m", i) ?: 0.0).toInt(), (v("weather_code", i) ?: 0.0).toInt()))
        }
        if (hours.isEmpty()) throw Failure("The forecast was empty.")
        return Forecast(lat, lon, hours)
    }

    fun upcoming(f: Forecast, fromMillis: Long, count: Int): List<ForecastHour> =
        f.hours.filter { it.timeMillis >= fromMillis - 1_800_000 }.take(count)

    data class ForecastDay(val dayStartMillis: Long, val highC: Double, val lowC: Double, val rainMm: Double, val maxGustKmh: Double, val maxChance: Int, val code: Int)

    /** One row per calendar day in [tz]; the code is the most significant during daylight hours. */
    fun days(f: Forecast, tz: TimeZone): List<ForecastDay> {
        val cal = Calendar.getInstance(tz)
        fun dayStart(t: Long): Long { cal.timeInMillis = t; cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0); return cal.timeInMillis }
        fun hour(t: Long): Int { cal.timeInMillis = t; return cal.get(Calendar.HOUR_OF_DAY) }
        return f.hours.groupBy { dayStart(it.timeMillis) }.toSortedMap().map { (day, hours) ->
            val daylight = hours.filter { hour(it.timeMillis) in 7..19 }.ifEmpty { hours }
            ForecastDay(day, hours.maxOf { it.temperatureC }, hours.minOf { it.temperatureC }, hours.sumOf { it.precipitationMm },
                hours.maxOf { it.gustKmh }, hours.mapNotNull { it.precipitationChance }.maxOrNull() ?: 0, daylight.maxOf { it.code })
        }
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Clear"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"; 45, 48 -> "Fog"; 51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"; 61 -> "Light rain"; 63 -> "Rain"; 65 -> "Heavy rain"; 66, 67 -> "Freezing rain"
        71 -> "Light snow"; 73 -> "Snow"; 75 -> "Heavy snow"; 77 -> "Snow grains"; 80, 81 -> "Rain showers"; 82 -> "Violent rain showers"
        85, 86 -> "Snow showers"; 95 -> "Thunderstorm"; 96, 99 -> "Thunderstorm with hail"; else -> "Unknown"
    }

    fun compass(degrees: Int): String =
        listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((degrees % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]

    // MARK: NOAA space weather

    fun parseSpaceWeather(kp: String, scales: String, threeDay: String): SpaceWeather {
        val rows = try { JSONArray(kp) } catch (_: Exception) { throw Failure("Not a Kp list.") }
        if (rows.length() == 0) throw Failure("Not a Kp list.")
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val points = ArrayList<KpPoint>()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val t = try { fmt.parse(r.getString("time_tag"))!!.time } catch (_: Exception) { continue }
            val v = r.optDouble("Kp").takeIf { !it.isNaN() } ?: continue
            points.add(KpPoint(t, v, false))
        }
        val last = points.lastOrNull() ?: throw Failure("The Kp list was empty.")
        val all = points + parseKpForecast(threeDay).filter { it.timeMillis > last.timeMillis }
        val s = try { JSONObject(scales) } catch (_: Exception) { JSONObject() }
        fun scale(day: String, key: String): Int = s.optJSONObject(day)?.optJSONObject(key)?.optString("Scale")?.toIntOrNull() ?: 0
        val outlook = listOf("0", "1", "2", "3").maxOf { scale(it, "G") }
        return SpaceWeather(last.kp, last.timeMillis, all, scale("0", "R"), scale("0", "S"), scale("0", "G"), outlook)
    }

    fun parseKpForecast(text: String): List<KpPoint> {
        val lines = text.lines()
        val head = lines.firstOrNull { it.contains("NOAA Kp index breakdown") } ?: return emptyList()
        val m = Regex("([A-Z][a-z]{2}) (\\d{1,2})-([A-Z][a-z]{2}) (\\d{1,2}) (\\d{4})").find(head) ?: return emptyList()
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val month = months.indexOf(m.groupValues[1]).takeIf { it >= 0 } ?: return emptyList()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(m.groupValues[5].toInt(), month, m.groupValues[2].toInt(), 0, 0, 0)
        }
        val day0 = cal.timeInMillis
        val out = ArrayList<KpPoint>()
        for (line in lines) {
            val h = Regex("^(\\d{2})-(\\d{2})UT").find(line.trim()) ?: continue
            val hour = h.groupValues[1].toInt()
            val values = line.trim().substring(h.value.length).replace(Regex("\\([^)]*\\)"), "").trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
            values.forEachIndexed { d, v -> out.add(KpPoint(day0 + (d * 24L + hour) * 3_600_000, v, true)) }
        }
        return out.sortedBy { it.timeMillis }
    }

    fun hfNote(w: SpaceWeather) = when {
        w.radio >= 3 -> "Strong radio blackout on the sunlit side: HF is unreliable."
        w.radio >= 1 -> "Minor radio blackout: some HF fades on the sunlit side."
        w.geomagnetic >= 3 -> "Geomagnetic storm: HF is poor at high latitudes."
        w.kpNow >= 5 -> "Disturbed: HF is degraded at higher latitudes."
        else -> "HF radio conditions are normal."
    }

    fun gpsNote(w: SpaceWeather) = when {
        w.geomagnetic >= 4 || w.radiation >= 3 -> "GPS may be noticeably less accurate."
        w.geomagnetic >= 2 || w.kpNow >= 6 -> "GPS may be slightly less accurate."
        else -> "GPS should be normal."
    }

    fun auroraNote(w: SpaceWeather): String? =
        if (w.geomagneticOutlook >= 1) "Geomagnetic storm (G${w.geomagneticOutlook}) expected in the next three days." else null

    // MARK: NOAA Weather Radio

    fun parseWeatherRadio(js: String): List<WeatherRadioStation> {
        val fields = HashMap<String, HashMap<Int, String>>()
        val re = Regex("^([A-Z]+)\\[(\\d+)] = \"(.*)\";$", RegexOption.MULTILINE)
        for (m in re.findAll(js)) fields.getOrPut(m.groupValues[1]) { HashMap() }[m.groupValues[2].toInt()] = m.groupValues[3]
        val byCall = LinkedHashMap<String, WeatherRadioStation>()
        for (i in (fields["CALLSIGN"] ?: emptyMap()).keys.sorted()) {
            fun f(k: String) = fields[k]?.get(i)
            val call = f("CALLSIGN")?.takeIf { it.isNotEmpty() } ?: continue
            val freq = f("FREQ") ?: continue
            val lat = f("LAT")?.toDoubleOrNull() ?: continue
            val lon = f("LON")?.toDoubleOrNull() ?: continue
            if (lat == 0.0 && lon == 0.0) continue
            val same = f("SAME") ?: ""
            val old = byCall[call]
            if (old != null) {
                if (same.isNotEmpty() && same !in old.counties) byCall[call] = old.copy(counties = old.counties + same)
            } else {
                byCall[call] = WeatherRadioStation(call, f("SITENAME") ?: call, f("SITESTATE") ?: f("ST") ?: "", freq, lat, lon,
                    f("PWR")?.toIntOrNull() ?: 0, f("STATUS") ?: "", if (same.isEmpty()) emptyList() else listOf(same))
            }
        }
        return byCall.values.sortedBy { it.callsign }
    }

    fun nearestRadio(stations: List<WeatherRadioStation>, latitude: Double, longitude: Double, limit: Int): List<Pair<WeatherRadioStation, Double>> =
        stations.filter { it.status.uppercase() == "NORMAL" }.map { it to km(latitude, longitude, it.latitude, it.longitude) }
            .sortedBy { it.second }.take(limit)

    val radioChannels = listOf("162.400" to "WX2", "162.425" to "WX4", "162.450" to "WX5", "162.475" to "WX3", "162.500" to "WX6", "162.525" to "WX7", "162.550" to "WX1")

    fun age(whenMillis: Long, nowMillis: Long): String {
        val s = maxOf(0L, (nowMillis - whenMillis) / 1000)
        return when {
            s < 90 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }
}

/** Units follow the phone's region: °F, mph, inches and miles in the US; °C, km/h, mm and km elsewhere. */
class ConditionsFormat(val metric: Boolean) {
    fun temperature(c: Double) = if (metric) "${Math.round(c)}°C" else "${Math.round(c * 9 / 5 + 32)}°F"
    fun speed(kmh: Double) = if (metric) "${Math.round(kmh)} km/h" else "${Math.round(kmh * 0.621371)} mph"
    fun rain(mm: Double): String {
        if (metric) return if (mm < 0.1) "0 mm" else String.format(Locale.US, "%.1f mm", mm)
        val inches = mm / 25.4
        return if (inches < 0.01) "0 in" else String.format(Locale.US, "%.2f in", inches)
    }
    fun distance(km: Double): String {
        val v = if (metric) km else km * 0.621371
        val unit = if (metric) "km" else "mi"
        return if (v < 10) String.format(Locale.US, "%.1f %s", v, unit) else "${Math.round(v)} $unit"
    }
    fun radius(km: Int) = if (metric) "$km km" else "${Math.round(km * 0.621371)} mi"

    companion object {
        fun current(): ConditionsFormat = ConditionsFormat(Locale.getDefault().country != "US" && Locale.getDefault().country != "LR" && Locale.getDefault().country != "MM")
    }
}
