// SGP4.swift
// Pocket Nomad - Satellites module
// SPDX-License-Identifier: Apache-2.0
//
// The SGP4 orbit model (near-Earth only) for NORAD two-line element sets, after
// Hoots & Roehrich, "Spacetrack Report No. 3", with the corrections published
// in Vallado et al., "Revisiting Spacetrack Report #3" (AIAA 2006-6753),
// whose reference implementation is in the public domain. WGS-72 constants.
//
// Deep-space objects (orbital period 225 minutes or more) need the SDP4 terms
// and are not supported: `init` returns nil for them. Everything amateur
// radio cares about in low Earth orbit (the space station, FM and linear
// satellites, CubeSats) is near-Earth. Verified against the Python `sgp4`
// library and Vallado's published test satellite (see SatelliteTests).

import Foundation

struct TLE: Codable, Equatable, Sendable, Identifiable {
    var name: String
    var line1: String
    var line2: String

    var id: Int { noradID }
    var noradID: Int { Int(line1.dropFirst(2).prefix(5).trimmingCharacters(in: .whitespaces)) ?? 0 }

    /// Days since the start of the epoch year, and the year.
    var epochYear: Int {
        let y = Int(line1.dropFirst(18).prefix(2)) ?? 0
        return y < 57 ? 2000 + y : 1900 + y
    }
    var epochDay: Double { Double(line1.dropFirst(20).prefix(12).trimmingCharacters(in: .whitespaces)) ?? 0 }
    var epoch: Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        let jan1 = cal.date(from: DateComponents(year: epochYear, month: 1, day: 1))!
        return jan1.addingTimeInterval((epochDay - 1) * 86_400)
    }

    /// Checks the two lines' checksums and shape.
    var isValid: Bool {
        guard line1.count >= 69, line2.count >= 69, line1.hasPrefix("1 "), line2.hasPrefix("2 ") else { return false }
        return Self.checksum(line1) == Self.digit(line1, 68) && Self.checksum(line2) == Self.digit(line2, 68)
    }

    private static func digit(_ s: String, _ i: Int) -> Int { Int(String(Array(s)[i])) ?? -1 }
    private static func checksum(_ s: String) -> Int {
        var sum = 0
        for c in Array(s).prefix(68) {
            if let d = c.wholeNumberValue { sum += d } else if c == "-" { sum += 1 }
        }
        return sum % 10
    }

    /// Reads three-line sets (name, line 1, line 2), as CelesTrak sends them. Bad sets are skipped.
    static func parse(_ text: String) -> [TLE] {
        let lines = text.components(separatedBy: .newlines).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        var out: [TLE] = []
        var i = 0
        while i + 2 < lines.count {
            if lines[i + 1].hasPrefix("1 "), lines[i + 2].hasPrefix("2 ") {
                let t = TLE(name: lines[i], line1: lines[i + 1], line2: lines[i + 2])
                if t.isValid { out.append(t) }
                i += 3
            } else {
                i += 1
            }
        }
        return out
    }
}

struct SGP4: Sendable {
    // WGS-72
    private static let mu = 398600.8
    private static let radiusKm = 6378.135
    private static let xke = 60.0 / (radiusKm * radiusKm * radiusKm / mu).squareRoot()
    private static let j2 = 0.001082616
    private static let j3 = -0.00000253881
    private static let j4 = -0.00000165597
    private static let j3oj2 = j3 / j2
    private static let twoPi = 2 * Double.pi
    private static let deg2rad = Double.pi / 180

    let epochJulianDate: Double
    // Mean elements at epoch.
    private let bstar, ecco, argpo, inclo, mo, no, nodeo: Double
    // Initialisation results.
    private let isimp: Bool
    private let aycof, con41, cc1, cc4, cc5, d2, d3, d4, delmo, eta, argpdot, omgcof, sinmao, t2cof, t3cof, t4cof, t5cof: Double
    private let x1mth2, x7thm1, mdot, nodedot, xlcof, xmcof, nodecf: Double

    /// nil for deep-space objects and unusable elements.
    init?(_ tle: TLE) {
        guard tle.isValid else { return nil }
        func field(_ line: String, _ from: Int, _ len: Int) -> String {
            String(Array(line).dropFirst(from).prefix(len)).trimmingCharacters(in: .whitespaces)
        }
        guard let incl = Double(field(tle.line2, 8, 8)), let node = Double(field(tle.line2, 17, 8)),
              let ecc = Double("0." + field(tle.line2, 26, 7)), let argp = Double(field(tle.line2, 34, 8)),
              let m = Double(field(tle.line2, 43, 8)), let revs = Double(field(tle.line2, 52, 11)) else { return nil }
        // "28098-4" means 0.28098e-4; a leading sign may be present.
        func implied(_ s: String) -> Double {
            let t = s.trimmingCharacters(in: .whitespaces)
            guard t.count >= 2 else { return 0 }
            let chars = Array(t)
            var sign = 1.0
            var i = 0
            if chars[0] == "-" { sign = -1; i = 1 } else if chars[0] == "+" { i = 1 }
            let rest = String(chars[i...])
            guard let expAt = rest.lastIndex(where: { $0 == "-" || $0 == "+" }), expAt != rest.startIndex else { return 0 }
            let mant = Double("0." + rest[..<expAt].trimmingCharacters(in: .whitespaces)) ?? 0
            let exp = Double(rest[expAt...]) ?? 0
            return sign * mant * pow(10, exp)
        }
        bstar = implied(field(tle.line1, 53, 8))
        ecco = ecc
        inclo = incl * Self.deg2rad
        nodeo = node * Self.deg2rad
        argpo = argp * Self.deg2rad
        mo = m * Self.deg2rad
        let noKozai = revs * Self.twoPi / 1440
        // Epoch as a Julian date.
        let yearStart = Self.julianDate(year: tle.epochYear, month: 1, day: 1, hour: 0)
        epochJulianDate = yearStart + tle.epochDay - 1

        // Un-Kozai the mean motion (Vallado's initl).
        let xke = Self.xke, j2 = Self.j2
        let eccsq = ecc * ecc
        let omeosq = 1 - eccsq
        let rteosq = omeosq.squareRoot()
        let cosio = cos(inclo), cosio2 = cosio * cosio
        let ak = pow(xke / noKozai, 2.0 / 3.0)
        let d1 = 0.75 * j2 * (3 * cosio2 - 1) / (rteosq * omeosq)
        var del = d1 / (ak * ak)
        let adel = ak * (1 - del * del - del * (1.0 / 3.0 + 134 * del * del / 81))
        del = d1 / (adel * adel)
        let n = noKozai / (1 + del)
        no = n
        guard 2 * Double.pi / n < 225 else { return nil } // deep space

        let ao = pow(xke / n, 2.0 / 3.0)
        let sinio = sin(inclo)
        let po = ao * omeosq
        let c42 = 1 - 5 * cosio2
        let c41 = -c42 - cosio2 - cosio2
        con41 = c41
        let posq = po * po
        let rp = ao * (1 - ecc)

        let ss = 78 / Self.radiusKm + 1
        let qzms2t = pow((120 - 78) / Self.radiusKm, 4)
        let perige = (rp - 1) * Self.radiusKm
        var sfour = ss
        var qzms24 = qzms2t
        if perige < 156 {
            sfour = perige - 78
            if perige < 98 { sfour = 20 }
            qzms24 = pow((120 - sfour) / Self.radiusKm, 4)
            sfour = sfour / Self.radiusKm + 1
        }
        let pinvsq = 1 / posq
        let tsi = 1 / (ao - sfour)
        let etaL = ao * ecc * tsi
        eta = etaL
        let etasq = etaL * etaL
        let eeta = ecc * etaL
        let psisq = abs(1 - etasq)
        let coef = qzms24 * pow(tsi, 4)
        let coef1 = coef / pow(psisq, 3.5)
        let cc2 = coef1 * n * (ao * (1 + 1.5 * etasq + eeta * (4 + etasq)) + 0.375 * j2 * tsi / psisq * c41 * (8 + 3 * etasq * (8 + etasq)))
        let cc1L = bstar * cc2
        cc1 = cc1L
        var cc3 = 0.0
        if ecc > 1e-4 { cc3 = -2 * coef * tsi * Self.j3oj2 * n * sinio / ecc }
        let x1m = 1 - cosio2
        x1mth2 = x1m
        cc4 = 2 * n * coef1 * ao * omeosq * (etaL * (2 + 0.5 * etasq) + ecc * (0.5 + 2 * etasq)
            - j2 * tsi / (ao * psisq) * (-3 * c41 * (1 - 2 * eeta + etasq * (1.5 - 0.5 * eeta))
            + 0.75 * x1m * (2 * etasq - eeta * (1 + etasq)) * cos(2 * argpo)))
        cc5 = 2 * coef1 * ao * omeosq * (1 + 2.75 * (etasq + eeta) + eeta * etasq)
        let cosio4 = cosio2 * cosio2
        let temp1 = 1.5 * j2 * pinvsq * n
        let temp2 = 0.5 * temp1 * j2 * pinvsq
        let temp3 = -0.46875 * Self.j4 * pinvsq * pinvsq * n
        mdot = n + 0.5 * temp1 * rteosq * c41 + 0.0625 * temp2 * rteosq * (13 - 78 * cosio2 + 137 * cosio4)
        argpdot = -0.5 * temp1 * c42 + 0.0625 * temp2 * (7 - 114 * cosio2 + 395 * cosio4) + temp3 * (3 - 36 * cosio2 + 49 * cosio4)
        let xhdot1 = -temp1 * cosio
        nodedot = xhdot1 + (0.5 * temp2 * (4 - 19 * cosio2) + 2 * temp3 * (3 - 7 * cosio2)) * cosio
        omgcof = bstar * cc3 * cos(argpo)
        xmcof = ecc > 1e-4 ? -(2.0 / 3.0) * coef * bstar / eeta : 0
        nodecf = 3.5 * omeosq * xhdot1 * cc1L
        t2cof = 1.5 * cc1L
        xlcof = abs(cosio + 1) > 1.5e-12 ? -0.25 * Self.j3oj2 * sinio * (3 + 5 * cosio) / (1 + cosio)
                                         : -0.25 * Self.j3oj2 * sinio * (3 + 5 * cosio) / 1.5e-12
        aycof = -0.5 * Self.j3oj2 * sinio
        let delmotemp = 1 + etaL * cos(mo)
        delmo = delmotemp * delmotemp * delmotemp
        sinmao = sin(mo)
        x7thm1 = 7 * cosio2 - 1
        isimp = rp < (220 / Self.radiusKm + 1)
        if !isimp {
            let cc1sq = cc1L * cc1L
            let d2L = 4 * ao * tsi * cc1sq
            let temp = d2L * tsi * cc1L / 3
            let d3L = (17 * ao + sfour) * temp
            let d4L = 0.5 * temp * ao * tsi * (221 * ao + 31 * sfour) * cc1L
            d2 = d2L
            d3 = d3L
            d4 = d4L
            t3cof = d2L + 2 * cc1sq
            t4cof = 0.25 * (3 * d3L + cc1L * (12 * d2L + 10 * cc1sq))
            t5cof = 0.2 * (3 * d4L + 12 * cc1L * d3L + 6 * d2L * d2L + 15 * cc1sq * (2 * d2L + cc1sq))
        } else {
            d2 = 0; d3 = 0; d4 = 0; t3cof = 0; t4cof = 0; t5cof = 0
        }
    }

    /// Position (km) and velocity (km/s) in the TEME frame, `minutes` after the element epoch. Nil if the orbit has decayed.
    func propagate(minutes t: Double) -> (position: SIMD3<Double>, velocity: SIMD3<Double>)? {
        let xke = Self.xke, j2 = Self.j2
        let xmdf = mo + mdot * t
        let argpdf = argpo + argpdot * t
        let nodedf = nodeo + nodedot * t
        var argpm = argpdf
        var mm = xmdf
        let t2 = t * t
        var nodem = nodedf + nodecf * t2
        var tempa = 1 - cc1 * t
        var tempe = bstar * cc4 * t
        var templ = t2cof * t2
        if !isimp {
            let delomg = omgcof * t
            let delmtemp = 1 + eta * cos(xmdf)
            let delm = xmcof * (delmtemp * delmtemp * delmtemp - delmo)
            let temp = delomg + delm
            mm = xmdf + temp
            argpm = argpdf - temp
            let t3 = t2 * t, t4 = t3 * t
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4
            tempe += bstar * cc5 * (sin(mm) - sinmao)
            templ += t3cof * t3 + t4 * (t4cof + t * t5cof)
        }
        var nm = no
        var em = ecco
        guard nm > 0 else { return nil }
        let am = pow(xke / nm, 2.0 / 3.0) * tempa * tempa
        nm = xke / pow(am, 1.5)
        em -= tempe
        guard em < 1, em >= -0.001 else { return nil }
        if em < 1e-6 { em = 1e-6 }
        mm += no * templ
        var xlm = mm + argpm + nodem
        nodem = nodem.truncatingRemainder(dividingBy: Self.twoPi)
        argpm = argpm.truncatingRemainder(dividingBy: Self.twoPi)
        xlm = xlm.truncatingRemainder(dividingBy: Self.twoPi)
        mm = (xlm - argpm - nodem).truncatingRemainder(dividingBy: Self.twoPi)

        let sinip = sin(inclo), cosip = cos(inclo)
        let ep = em
        let axnl = ep * cos(argpm)
        var temp = 1 / (am * (1 - ep * ep))
        let aynl = ep * sin(argpm) + temp * aycof
        let xl = mm + argpm + nodem + temp * xlcof * axnl

        let u = (xl - nodem).truncatingRemainder(dividingBy: Self.twoPi)
        var eo1 = u
        var tem5 = 9999.9
        var ktr = 1
        var sineo1 = 0.0, coseo1 = 0.0
        while abs(tem5) >= 1e-12, ktr <= 10 {
            sineo1 = sin(eo1)
            coseo1 = cos(eo1)
            tem5 = 1 - coseo1 * axnl - sineo1 * aynl
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
            if abs(tem5) >= 0.95 { tem5 = tem5 > 0 ? 0.95 : -0.95 }
            eo1 += tem5
            ktr += 1
        }
        let ecose = axnl * coseo1 + aynl * sineo1
        let esine = axnl * sineo1 - aynl * coseo1
        let el2 = axnl * axnl + aynl * aynl
        let pl = am * (1 - el2)
        guard pl >= 0 else { return nil }
        let rl = am * (1 - ecose)
        let rdotl = am.squareRoot() * esine / rl
        let rvdotl = pl.squareRoot() / rl
        let betal = (1 - el2).squareRoot()
        temp = esine / (1 + betal)
        let sinu = am / rl * (sineo1 - aynl - axnl * temp)
        let cosu = am / rl * (coseo1 - axnl + aynl * temp)
        var su = atan2(sinu, cosu)
        let sin2u = (cosu + cosu) * sinu
        let cos2u = 1 - 2 * sinu * sinu
        temp = 1 / pl
        let temp1 = 0.5 * j2 * temp
        let temp2 = temp1 * temp
        let mrt = rl * (1 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u
        su -= 0.25 * temp2 * x7thm1 * sin2u
        let xnode = nodem + 1.5 * temp2 * cosip * sin2u
        let xinc = inclo + 1.5 * temp2 * cosip * sinip * cos2u
        let mvt = rdotl - nm * temp1 * x1mth2 * sin2u / xke
        let rvdot = rvdotl + nm * temp1 * (x1mth2 * cos2u + 1.5 * con41) / xke
        let sinsu = sin(su), cossu = cos(su), snod = sin(xnode), cnod = cos(xnode), sini = sin(xinc), cosi = cos(xinc)
        let xmx = -snod * cosi, xmy = cnod * cosi
        let ux = xmx * sinsu + cnod * cossu, uy = xmy * sinsu + snod * cossu, uz = sini * sinsu
        let vx = xmx * cossu - cnod * sinsu, vy = xmy * cossu - snod * sinsu, vz = sini * cossu
        guard mrt >= 1 else { return nil } // decayed
        let mr = mrt * Self.radiusKm
        let vkmps = Self.radiusKm * xke / 60
        return (SIMD3(mr * ux, mr * uy, mr * uz),
                SIMD3((mvt * ux + rvdot * vx) * vkmps, (mvt * uy + rvdot * vy) * vkmps, (mvt * uz + rvdot * vz) * vkmps))
    }

    func propagate(at date: Date) -> (position: SIMD3<Double>, velocity: SIMD3<Double>)? {
        propagate(minutes: (Self.julianDate(date) - epochJulianDate) * 1440)
    }

    // MARK: Time

    static func julianDate(year: Int, month: Int, day: Int, hour: Double) -> Double {
        let y = Double(year), m = Double(month), d = Double(day)
        return 367 * y - floor(7 * (y + floor((m + 9) / 12)) / 4) + floor(275 * m / 9) + d + 1_721_013.5 + hour / 24
    }

    /// Julian date (UT) of a moment.
    static func julianDate(_ date: Date) -> Double { date.timeIntervalSince1970 / 86_400 + 2_440_587.5 }

    /// Greenwich mean sidereal time in radians (IAU-82), for turning TEME into Earth-fixed coordinates.
    static func gmst(_ jdUT1: Double) -> Double {
        let t = (jdUT1 - 2_451_545.0) / 36_525
        var temp = -6.2e-6 * t * t * t + 0.093104 * t * t + (876_600 * 3600 + 8_640_184.812866) * t + 67_310.54841
        temp = (temp * deg2rad / 240).truncatingRemainder(dividingBy: twoPi)
        return temp < 0 ? temp + twoPi : temp
    }
}
