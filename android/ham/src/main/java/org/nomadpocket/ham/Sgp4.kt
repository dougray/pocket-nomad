// Sgp4.kt
// Pocket Nomad - Satellites module
// SPDX-License-Identifier: Apache-2.0
//
// The SGP4 orbit model (near-Earth only) for NORAD two-line element sets, after
// Hoots & Roehrich, "Spacetrack Report No. 3", with the corrections published in
// Vallado et al., "Revisiting Spacetrack Report #3" (AIAA 2006-6753), whose
// reference implementation is in the public domain. WGS-72 constants. Port of
// SGP4.swift; verified against the Python sgp4 library and Vallado's test
// satellite. Deep-space objects (period of 225 minutes or more) aren't supported.

package org.nomadpocket.ham

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class Tle(val name: String, val line1: String, val line2: String) {
    val noradId: Int get() = line1.drop(2).take(5).trim().toIntOrNull() ?: 0
    val epochYear: Int get() { val y = line1.drop(18).take(2).toIntOrNull() ?: 0; return if (y < 57) 2000 + y else 1900 + y }
    val epochDay: Double get() = line1.drop(20).take(12).trim().toDoubleOrNull() ?: 0.0
    /** Unix milliseconds of the element epoch. */
    val epochMillis: Long get() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(epochYear, 0, 1, 0, 0, 0) }
        return cal.timeInMillis + ((epochDay - 1) * 86_400_000).toLong()
    }

    val isValid: Boolean get() =
        line1.length >= 69 && line2.length >= 69 && line1.startsWith("1 ") && line2.startsWith("2 ") &&
            checksum(line1) == line1[68].digitToIntOrNull() && checksum(line2) == line2[68].digitToIntOrNull()

    companion object {
        private fun checksum(s: String): Int = s.take(68).sumOf { c -> if (c.isDigit()) c.digitToInt() else if (c == '-') 1 else 0 } % 10

        /** Reads three-line sets (name, line 1, line 2), as CelesTrak sends them. Bad sets are skipped. */
        fun parse(text: String): List<Tle> {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val out = ArrayList<Tle>()
            var i = 0
            while (i + 2 < lines.size) {
                if (lines[i + 1].startsWith("1 ") && lines[i + 2].startsWith("2 ")) {
                    val t = Tle(lines[i], lines[i + 1], lines[i + 2])
                    if (t.isValid) out.add(t)
                    i += 3
                } else i++
            }
            return out
        }
    }
}

class Sgp4 private constructor(
    val epochJulianDate: Double,
    private val bstar: Double, private val ecco: Double, private val argpo: Double, private val inclo: Double, private val mo: Double,
    private val no: Double, private val nodeo: Double,
    private val isimp: Boolean,
    private val aycof: Double, private val con41: Double, private val cc1: Double, private val cc4: Double, private val cc5: Double,
    private val d2: Double, private val d3: Double, private val d4: Double, private val delmo: Double, private val eta: Double,
    private val argpdot: Double, private val omgcof: Double, private val sinmao: Double, private val t2cof: Double, private val t3cof: Double,
    private val t4cof: Double, private val t5cof: Double, private val x1mth2: Double, private val x7thm1: Double, private val mdot: Double,
    private val nodedot: Double, private val xlcof: Double, private val xmcof: Double, private val nodecf: Double,
) {
    class State(val x: Double, val y: Double, val z: Double, val vx: Double, val vy: Double, val vz: Double)

    /** Position (km) and velocity (km/s) in the TEME frame, [minutes] after the element epoch. Null if the orbit has decayed. */
    fun propagate(minutes: Double): State? {
        val t = minutes
        val xmdf = mo + mdot * t
        val argpdf = argpo + argpdot * t
        val nodedf = nodeo + nodedot * t
        var argpm = argpdf
        var mm = xmdf
        val t2 = t * t
        var nodem = nodedf + nodecf * t2
        var tempa = 1 - cc1 * t
        var tempe = bstar * cc4 * t
        var templ = t2cof * t2
        if (!isimp) {
            val delomg = omgcof * t
            val delmtemp = 1 + eta * cos(xmdf)
            val delm = xmcof * (delmtemp * delmtemp * delmtemp - delmo)
            val temp = delomg + delm
            mm = xmdf + temp
            argpm = argpdf - temp
            val t3 = t2 * t; val t4 = t3 * t
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4
            tempe += bstar * cc5 * (sin(mm) - sinmao)
            templ += t3cof * t3 + t4 * (t4cof + t * t5cof)
        }
        var nm = no
        var em = ecco
        if (nm <= 0) return null
        val am = (XKE / nm).pow(2.0 / 3.0) * tempa * tempa
        nm = XKE / am.pow(1.5)
        em -= tempe
        if (em >= 1 || em < -0.001) return null
        if (em < 1e-6) em = 1e-6
        mm += no * templ
        var xlm = mm + argpm + nodem
        nodem %= TWO_PI
        argpm %= TWO_PI
        xlm %= TWO_PI
        mm = (xlm - argpm - nodem) % TWO_PI

        val sinip = sin(inclo); val cosip = cos(inclo)
        val axnl = em * cos(argpm)
        var temp = 1 / (am * (1 - em * em))
        val aynl = em * sin(argpm) + temp * aycof
        val xl = mm + argpm + nodem + temp * xlcof * axnl

        val u = (xl - nodem) % TWO_PI
        var eo1 = u
        var tem5 = 9999.9
        var ktr = 1
        var sineo1 = 0.0; var coseo1 = 0.0
        while (abs(tem5) >= 1e-12 && ktr <= 10) {
            sineo1 = sin(eo1); coseo1 = cos(eo1)
            tem5 = 1 - coseo1 * axnl - sineo1 * aynl
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
            if (abs(tem5) >= 0.95) tem5 = if (tem5 > 0) 0.95 else -0.95
            eo1 += tem5
            ktr++
        }
        val ecose = axnl * coseo1 + aynl * sineo1
        val esine = axnl * sineo1 - aynl * coseo1
        val el2 = axnl * axnl + aynl * aynl
        val pl = am * (1 - el2)
        if (pl < 0) return null
        val rl = am * (1 - ecose)
        val rdotl = sqrt(am) * esine / rl
        val rvdotl = sqrt(pl) / rl
        val betal = sqrt(1 - el2)
        temp = esine / (1 + betal)
        val sinu = am / rl * (sineo1 - aynl - axnl * temp)
        val cosu = am / rl * (coseo1 - axnl + aynl * temp)
        var su = atan2(sinu, cosu)
        val sin2u = (cosu + cosu) * sinu
        val cos2u = 1 - 2 * sinu * sinu
        temp = 1 / pl
        val temp1 = 0.5 * J2 * temp
        val temp2 = temp1 * temp
        val mrt = rl * (1 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u
        su -= 0.25 * temp2 * x7thm1 * sin2u
        val xnode = nodem + 1.5 * temp2 * cosip * sin2u
        val xinc = inclo + 1.5 * temp2 * cosip * sinip * cos2u
        val mvt = rdotl - nm * temp1 * x1mth2 * sin2u / XKE
        val rvdot = rvdotl + nm * temp1 * (x1mth2 * cos2u + 1.5 * con41) / XKE
        val sinsu = sin(su); val cossu = cos(su); val snod = sin(xnode); val cnod = cos(xnode); val sini = sin(xinc); val cosi = cos(xinc)
        val xmx = -snod * cosi; val xmy = cnod * cosi
        val ux = xmx * sinsu + cnod * cossu; val uy = xmy * sinsu + snod * cossu; val uz = sini * sinsu
        val vx = xmx * cossu - cnod * sinsu; val vy = xmy * cossu - snod * sinsu; val vz = sini * cossu
        if (mrt < 1) return null
        val mr = mrt * RADIUS_KM
        val vk = RADIUS_KM * XKE / 60
        return State(mr * ux, mr * uy, mr * uz, (mvt * ux + rvdot * vx) * vk, (mvt * uy + rvdot * vy) * vk, (mvt * uz + rvdot * vz) * vk)
    }

    fun propagateAt(millis: Long): State? = propagate((julianDate(millis) - epochJulianDate) * 1440)

    companion object {
        private const val MU = 398600.8
        const val RADIUS_KM = 6378.135
        private val XKE = 60.0 / sqrt(RADIUS_KM * RADIUS_KM * RADIUS_KM / MU)
        private const val J2 = 0.001082616
        private const val J3 = -0.00000253881
        private const val J4 = -0.00000165597
        private const val J3OJ2 = J3 / J2
        private const val TWO_PI = 2 * PI
        private const val DEG2RAD = PI / 180

        fun julianDate(year: Int, month: Int, day: Int, hour: Double): Double {
            val y = year.toDouble(); val m = month.toDouble(); val d = day.toDouble()
            return 367 * y - floor(7 * (y + floor((m + 9) / 12)) / 4) + floor(275 * m / 9) + d + 1_721_013.5 + hour / 24
        }

        /** Julian date (UT) of a Unix time in milliseconds. */
        fun julianDate(millis: Long): Double = millis / 86_400_000.0 + 2_440_587.5

        /** Greenwich mean sidereal time in radians (IAU-82). */
        fun gmst(jdUt1: Double): Double {
            val t = (jdUt1 - 2_451_545.0) / 36_525
            var temp = -6.2e-6 * t * t * t + 0.093104 * t * t + (876_600.0 * 3600.0 + 8_640_184.812866) * t + 67_310.54841
            temp = (temp * DEG2RAD / 240) % TWO_PI
            return if (temp < 0) temp + TWO_PI else temp
        }

        /** Null for deep-space objects and unusable elements. */
        fun create(tle: Tle): Sgp4? {
            if (!tle.isValid) return null
            fun field(line: String, from: Int, len: Int) = line.drop(from).take(len).trim()
            val incl = field(tle.line2, 8, 8).toDoubleOrNull() ?: return null
            val node = field(tle.line2, 17, 8).toDoubleOrNull() ?: return null
            val ecc = ("0." + field(tle.line2, 26, 7)).toDoubleOrNull() ?: return null
            val argp = field(tle.line2, 34, 8).toDoubleOrNull() ?: return null
            val m = field(tle.line2, 43, 8).toDoubleOrNull() ?: return null
            val revs = field(tle.line2, 52, 11).toDoubleOrNull() ?: return null
            fun implied(s0: String): Double {
                val s = s0.trim()
                if (s.length < 2) return 0.0
                var sign = 1.0; var i = 0
                if (s[0] == '-') { sign = -1.0; i = 1 } else if (s[0] == '+') i = 1
                val rest = s.substring(i)
                val expAt = rest.indexOfLast { it == '-' || it == '+' }
                if (expAt <= 0) return 0.0
                val mant = ("0." + rest.substring(0, expAt).trim()).toDoubleOrNull() ?: 0.0
                val exp = rest.substring(expAt).toDoubleOrNull() ?: 0.0
                return sign * mant * 10.0.pow(exp)
            }
            val bstar = implied(field(tle.line1, 53, 8))
            val inclo = incl * DEG2RAD; val nodeo = node * DEG2RAD; val argpo = argp * DEG2RAD; val mo = m * DEG2RAD
            val noKozai = revs * TWO_PI / 1440
            val epochJd = julianDate(tle.epochYear, 1, 1, 0.0) + tle.epochDay - 1

            val eccsq = ecc * ecc
            val omeosq = 1 - eccsq
            val rteosq = sqrt(omeosq)
            val cosio = cos(inclo); val cosio2 = cosio * cosio
            val ak = (XKE / noKozai).pow(2.0 / 3.0)
            val d1 = 0.75 * J2 * (3 * cosio2 - 1) / (rteosq * omeosq)
            var del = d1 / (ak * ak)
            val adel = ak * (1 - del * del - del * (1.0 / 3.0 + 134 * del * del / 81))
            del = d1 / (adel * adel)
            val n = noKozai / (1 + del)
            if (TWO_PI / n >= 225) return null

            val ao = (XKE / n).pow(2.0 / 3.0)
            val sinio = sin(inclo)
            val po = ao * omeosq
            val c42 = 1 - 5 * cosio2
            val c41 = -c42 - cosio2 - cosio2
            val posq = po * po
            val rp = ao * (1 - ecc)

            val ss = 78 / RADIUS_KM + 1
            val qzms2t = ((120 - 78) / RADIUS_KM).pow(4)
            val perige = (rp - 1) * RADIUS_KM
            var sfour = ss
            var qzms24 = qzms2t
            if (perige < 156) {
                sfour = perige - 78
                if (perige < 98) sfour = 20.0
                qzms24 = ((120 - sfour) / RADIUS_KM).pow(4)
                sfour = sfour / RADIUS_KM + 1
            }
            val pinvsq = 1 / posq
            val tsi = 1 / (ao - sfour)
            val eta = ao * ecc * tsi
            val etasq = eta * eta
            val eeta = ecc * eta
            val psisq = abs(1 - etasq)
            val coef = qzms24 * tsi.pow(4)
            val coef1 = coef / psisq.pow(3.5)
            val cc2 = coef1 * n * (ao * (1 + 1.5 * etasq + eeta * (4 + etasq)) + 0.375 * J2 * tsi / psisq * c41 * (8 + 3 * etasq * (8 + etasq)))
            val cc1 = bstar * cc2
            var cc3 = 0.0
            if (ecc > 1e-4) cc3 = -2 * coef * tsi * J3OJ2 * n * sinio / ecc
            val x1m = 1 - cosio2
            val cc4 = 2 * n * coef1 * ao * omeosq * (eta * (2 + 0.5 * etasq) + ecc * (0.5 + 2 * etasq)
                - J2 * tsi / (ao * psisq) * (-3 * c41 * (1 - 2 * eeta + etasq * (1.5 - 0.5 * eeta))
                + 0.75 * x1m * (2 * etasq - eeta * (1 + etasq)) * cos(2 * argpo)))
            val cc5 = 2 * coef1 * ao * omeosq * (1 + 2.75 * (etasq + eeta) + eeta * etasq)
            val cosio4 = cosio2 * cosio2
            val temp1 = 1.5 * J2 * pinvsq * n
            val temp2 = 0.5 * temp1 * J2 * pinvsq
            val temp3 = -0.46875 * J4 * pinvsq * pinvsq * n
            val mdot = n + 0.5 * temp1 * rteosq * c41 + 0.0625 * temp2 * rteosq * (13 - 78 * cosio2 + 137 * cosio4)
            val argpdot = -0.5 * temp1 * c42 + 0.0625 * temp2 * (7 - 114 * cosio2 + 395 * cosio4) + temp3 * (3 - 36 * cosio2 + 49 * cosio4)
            val xhdot1 = -temp1 * cosio
            val nodedot = xhdot1 + (0.5 * temp2 * (4 - 19 * cosio2) + 2 * temp3 * (3 - 7 * cosio2)) * cosio
            val omgcof = bstar * cc3 * cos(argpo)
            val xmcof = if (ecc > 1e-4) -(2.0 / 3.0) * coef * bstar / eeta else 0.0
            val nodecf = 3.5 * omeosq * xhdot1 * cc1
            val t2cof = 1.5 * cc1
            val xlcof = if (abs(cosio + 1) > 1.5e-12) -0.25 * J3OJ2 * sinio * (3 + 5 * cosio) / (1 + cosio) else -0.25 * J3OJ2 * sinio * (3 + 5 * cosio) / 1.5e-12
            val aycof = -0.5 * J3OJ2 * sinio
            val delmotemp = 1 + eta * cos(mo)
            val delmo = delmotemp * delmotemp * delmotemp
            val sinmao = sin(mo)
            val x7thm1 = 7 * cosio2 - 1
            val isimp = rp < (220 / RADIUS_KM + 1)
            var d2 = 0.0; var d3 = 0.0; var d4 = 0.0; var t3cof = 0.0; var t4cof = 0.0; var t5cof = 0.0
            if (!isimp) {
                val cc1sq = cc1 * cc1
                d2 = 4 * ao * tsi * cc1sq
                val temp = d2 * tsi * cc1 / 3
                d3 = (17 * ao + sfour) * temp
                d4 = 0.5 * temp * ao * tsi * (221 * ao + 31 * sfour) * cc1
                t3cof = d2 + 2 * cc1sq
                t4cof = 0.25 * (3 * d3 + cc1 * (12 * d2 + 10 * cc1sq))
                t5cof = 0.2 * (3 * d4 + 12 * cc1 * d3 + 6 * d2 * d2 + 15 * cc1sq * (2 * d2 + cc1sq))
            }
            return Sgp4(epochJd, bstar, ecc, argpo, inclo, mo, n, nodeo, isimp, aycof, c41, cc1, cc4, cc5, d2, d3, d4, delmo, eta, argpdot, omgcof,
                sinmao, t2cof, t3cof, t4cof, t5cof, x1m, x7thm1, mdot, nodedot, xlcof, xmcof, nodecf)
        }
    }
}
