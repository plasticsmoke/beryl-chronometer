package com.plasticsmoke.beryl.astro

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Willmann-Bell planet positions (Mercury, Venus, Mars inner; Jupiter, Saturn outer).
 * Ported from iOS esastro `ESWillmannBell.cpp` (WB_<planet> heliocentric long/lat, radius,
 * aberration + WB_convertGeocentric), with series coefficients from [PlanetTables]. Cross-checked vs
 * chronometer-web wb-planets.ts. The Sun mean longitude/radius and nutation/obliquity reuse the
 * existing [wbSunLongitudeRaw]/[wbSunRadius]/[wbNutationObliquity] helpers.
 *
 * Time argument `u` = hundred-centuries since the J2000 TDT epoch = jc/100.
 */

class PlanetPosition(
    val apparentLongitude: Double,
    val apparentLatitude: Double,
    val geocentricDistanceAU: Double,
    val rightAscension: Double,
    val declination: Double,
)

private fun innerSum(table: Array<DoubleArray>, u: Double, useCos: Boolean): Double {
    var s = 0.0
    for (d in table) s += d[0] * if (useCos) cos(d[1] + u * d[2]) else sin(d[1] + u * d[2])
    return s
}

// ---------------- Mercury ----------------
private fun mercuryHelioLong(u: Double): Double {
    val U2 = u * u; val U3 = u * U2; val U4 = U2 * U2; val U5 = u * U4
    var L = innerSum(PlanetTables.mercuryLong, u, false)
    L = L * 1e-7 + 4.4429839 + 260881.4701279 * u +
        1e-6 * (409894.2 + 2435 * u - 1408 * U2 + 114 * U3 + 233 * U4 - 88 * U5) *
        sin(3.053817 + 260878.756773 * u - 0.001093 * U2 - 0.00093 * U3 + 0.00043 * U4 + 0.00014 * U5)
    L = esFmod(L, 2 * PI); if (L < 0) L += 2 * PI; return L
}
private fun mercuryHelioLat(u: Double) = innerSum(PlanetTables.mercuryLat, u, false) * 1e-7
private fun mercuryRadius(u: Double) = 0.3952020 + 1e-7 * innerSum(PlanetTables.mercuryRad, u, true)
private fun mercuryLongAberration(u: Double) =
    1e-7 * (-1261 + 1485 * cos(2.649 + 198048.273 * u) + 305 * cos(5.71 + 458927.03 * u) + 230 * cos(5.30 + 396096.55 * u))
private fun mercuryLatAberration(u: Double) = 190e-7 * cos(0.42 + 260879.41 * u)

// ---------------- Venus ----------------
private fun venusHelioLong(u: Double): Double {
    val U2 = u * u; val U3 = u * U2; val U4 = U2 * U2; val U5 = u * U4; val U6 = U3 * U3
    var L = innerSum(PlanetTables.venusLong, u, false)
    L = L * 1e-7 + 3.2184413 + 102135.2937764 * u +
        1e-6 * (13539.7 - 9570.0 * u + 1987 * U2 + 927 * U3 + 230 * U4 - 51 * U5 + 10 * U6) *
        sin(0.88074 + 102132.84648 * u + 0.24082 * U2 + 0.1004 * U3 + 0.0355 * U4 - 0.0017 * U5 - 0.0151 * U6) +
        1e-6 * (898.9 + 112.4 * u - 170 * U2 + 113 * U3 + 34 * U4 - 79 * U5 + 56 * U6) *
        sin(0.5941 + 204267.3130 * u + 0.014 * U2 + 0.123 * U3 - 0.146 * U4 + 0.052 * U5)
    L = esFmod(L, 2 * PI); if (L < 0) L += 2 * PI; return L
}
private fun venusHelioLat(u: Double): Double {
    val U2 = u * u; val U3 = u * U2; val U4 = U2 * U2
    var L = innerSum(PlanetTables.venusLat, u, false)
    L = L * 1e-7 +
        1e-7 * (4011 - 2713 * u + 490 * U2 + 290 * U3 + 90 * U4) * sin(2.7182 + 204266.568 * u + 0.225 * U2 + 0.102 * U3 + 0.035 * U4) +
        1e-7 * (101 + 26 * u - 64 * U2) * sin(2.66 + 306400.49 * u + 0.45 * U2)
    return L
}
private fun venusRadius(u: Double): Double {
    val U2 = u * u; val U3 = u * U2; val U4 = U2 * U2; val U5 = U2 * U3; val U6 = U3 * U3
    var R = innerSum(PlanetTables.venusRad, u, true)
    R = R * 1e-7 + 0.7235481 +
        1e-7 * (48982 - 34549 * u + 7096 * U2 + 3360 * U3 + 890 * U4 - 210 * U5) * cos(4.02152 + 102132.84695 * u + 0.2420 * U2 + 0.0994 * U3 + 0.0351 * U4 - 0.0013 * U5 - 0.015 * U6) +
        1e-7 * (166 - 234 * u + 131 * U2) * cos(4.90 + 204265.69 * u + 0.48 * U2 + 0.20 * U3)
    return R
}
private fun venusLongAberration(u: Double) =
    1e-7 * (-1304 + 1016 * cos(1.423 + 39302.097 * u) + 224 * cos(2.85 + 78604.19 * u) + 98 * cos(4.27 + 117906.29 * u))

// ---------------- Mars ----------------
private fun marsHelioLong(u: Double): Double {
    val U2 = u * u; val U3 = u * U2; val U4 = U2 * U2; val U5 = u * U4; val U6 = U3 * U3
    var L = innerSum(PlanetTables.marsLong, u, false)
    L = L * 1e-7 + 6.2458611 + 33408.5620646 * u +
        1e-6 * (186563.7 + 18135.0 * u - 1332 * U2 - 704 * U3 - 65 * U4 - 89 * U5 + 9 * U6) *
        sin(0.337967 + 33405.348759 * u + 0.031676 * U2 - 0.007354 * U3 + 0.001143 * U4 - 0.00029 * U5 - 0.00010 * U6)
    L = esFmod(L, 2 * PI); if (L < 0) L += 2 * PI; return L
}
private fun marsHelioLat(u: Double): Double {
    val U2 = u * u; val U3 = u * U2; val U4 = U2 * U2; val U5 = U2 * U3; val U6 = U3 * U3; val U7 = U3 * U4
    var L = innerSum(PlanetTables.marsLat, u, false)
    L = L * 1e-7 +
        1e-7 * (319714 - 10277 * u + 24272 * U2 - 2420 * U3 - 10850 * U4 + 3880 * U5 + 5310 * U6 - 1050 * U7) * sin(5.339102 + 33407.21879 * u + 0.04800 * U2 - 0.04831 * U3 + 0.01402 * U4 + 0.0290 * U5 - 0.0073 * U6 - 0.0112 * U7) +
        1e-7 * (29803 + 1904 * u + 1865 * U2 - 60 * U3 - 950 * U4 + 220 * U5 + 270 * U6) * sin(5.67694 + 66812.5668 * u + 0.0803 * U2 - 0.0536 * U3 + 0.0147 * U4 + 0.028 * U5) +
        1e-7 * (3137 + 472 * u + 111 * U2 + 70 * U3) * sin(6.0173 + 100217.928 * u + 0.093 * U2 - 0.086 * U3 + 0.037 * U4)
    return L
}
private fun marsRadius(u: Double): Double {
    val U2 = u * u; val U3 = u * U2; val U4 = U2 * U2; val U5 = U2 * U3; val U6 = U3 * U3
    var R = innerSum(PlanetTables.marsRad, u, true)
    R = R * 1e-7 + 1.529856 +
        1e-6 * (141849.5 + 13651.8 * u - 1230 * U2 - 378 * U3 + 187 * U4 - 153 * U5 - 73 * U6) * cos(3.479698 + 33405.349560 * u + 0.030669 * U2 - 0.00909 * U3 + 0.00223 * U4 + 0.00083 * U5 - 0.00048 * U6) +
        1e-6 * (6607.8 + 1272.8 * u - 53 * U2 - 46 * U3 + 14 * U4 - 12 * U5 + 99 * U6) * cos(3.81781 + 66810.6991 * u + 0.0613 * U2 - 0.0182 * U3 + 0.0044 * U4 + 0.0012 * U5 + 0.002 * U6)
    return R
}
private fun marsLongAberration(u: Double) =
    1e-7 * (-1052 + 877 * cos(1.834 + 29424.634 * u) + 187 * cos(3.67 + 58849.27 * u) + 84 * cos(3.49 + 33405.34 * u))

// ---------------- Outer planets (Jupiter, Saturn) ----------------
private fun makeVs(v: Double): DoubleArray {
    val vs = DoubleArray(7)
    vs[1] = v; vs[2] = v * v; vs[3] = vs[2] * v; vs[4] = vs[2] * vs[2]; vs[5] = vs[3] * vs[2]; vs[6] = vs[3] * vs[3]
    return vs
}
/** Chebyshev-like evaluation of one of the 7-coeff blocks (offset 0=long, 7=lat, 14=rad). */
private fun calcOuter(vs: DoubleArray, c: DoubleArray, off: Int) =
    c[off] + c[off + 1] * vs[1] + c[off + 2] * vs[2] + c[off + 3] * vs[3] + c[off + 4] * vs[4] + c[off + 5] * vs[5] + c[off + 6] * vs[6]

private class OuterDatum(val coeffs: DoubleArray?, val v: Double)
/** Locate the JD-range entry containing [u]'s Julian day; returns its coeff row + normalized V. */
private fun findOuter(u: Double, data: Array<DoubleArray>, jd: Array<DoubleArray>): OuterDatum {
    val n = data.size
    val jdv = u * 3652500 + 2451545
    val firstJD = jd[0][0]; val lastJD = jd[n - 1][1]
    if (jdv < firstJD || jdv > lastJD) return OuterDatum(null, Double.NaN)
    val fract = (jdv - firstJD) / (lastJD - 4 - firstJD)
    var indx = (fract * n).toInt()
    if (indx < 0) indx = 0 else if (indx > n - 1) indx = n - 1
    if (jdv < jd[indx][0]) {
        indx--
    } else if (indx < n - 1 && jdv >= jd[indx + 1][0]) {
        indx++
    }
    return OuterDatum(data[indx], (jdv - jd[indx][0]) / 2000)
}
private fun outerLong(u: Double, data: Array<DoubleArray>, jd: Array<DoubleArray>): Double {
    val d = findOuter(u, data, jd); if (d.coeffs == null) return 0.0
    var L = calcOuter(makeVs(d.v), d.coeffs, 0); L = esFmod(L, 2 * PI); if (L < 0) L += 2 * PI; return L
}
private fun outerLat(u: Double, data: Array<DoubleArray>, jd: Array<DoubleArray>): Double {
    val d = findOuter(u, data, jd); if (d.coeffs == null) return 0.0
    return calcOuter(makeVs(d.v), d.coeffs, 7)
}
private fun outerRad(u: Double, data: Array<DoubleArray>, jd: Array<DoubleArray>): Double {
    val d = findOuter(u, data, jd); if (d.coeffs == null) return 0.0
    return calcOuter(makeVs(d.v), d.coeffs, 14)
}
private fun jupiterLongAberration(u: Double) =
    1e-7 * (-527 + 978 * cos(1.154 + 57533.849 * u) + 89 * cos(2.30 + 115067.70 * u) + 46 * cos(4.64 + 62830.76 * u) + 45 * cos(0.76 + 52236.94 * u))
private fun saturnLongAberration(u: Double) =
    1e-7 * (-373 + 986 * cos(0.880 + 60697.768 * u) + 54 * cos(3.31 + 62830.76 * u) + 52 * cos(1.59 + 58564.78 * u) + 51 * cos(1.76 + 121395.54 * u))

// ---------------- Geocentric conversion (WB_convertGeocentric) ----------------
private fun convertGeocentric(
    helioLong: Double, helioLat: Double, helioRadius: Double,
    sunMeanLong: Double, sunMeanRadius: Double,
    longAberration: Double, latAberration: Double, obliquity: Double, nutation: Double,
): PlanetPosition {
    val xSun = sunMeanRadius * cos(sunMeanLong); val ySun = sunMeanRadius * sin(sunMeanLong)
    val xH = helioRadius * cos(helioLat) * cos(helioLong)
    val yH = helioRadius * cos(helioLat) * sin(helioLong)
    val zH = helioRadius * sin(helioLat)
    val xG = xSun + xH; val yG = ySun + yH; val zG = zH
    val dist = sqrt(xG * xG + yG * yG + zG * zG)
    val meanLong = atan2(yG, xG)
    val meanLat = atan2(zG, sqrt(xG * xG + yG * yG))
    var appLong = meanLong + longAberration + nutation
    if (appLong < 0) appLong += 2 * PI else if (appLong > 2 * PI) appLong -= 2 * PI
    val appLat = meanLat + latAberration
    val cosO = cos(obliquity); val sinO = sin(obliquity)
    val sinLat = sin(appLat); val cosLat = cos(appLat); val sinLong = sin(appLong)
    val decl = asin(cosO * sinLat + sinO * cosLat * sinLong)
    val y = cosO * cosLat * sinLong - sinO * sinLat
    val x = cosLat * cos(appLong)
    var ra = esFmod(atan2(y, x), 2 * PI); if (ra < 0) ra += 2 * PI
    return PlanetPosition(appLong, appLat, dist, ra, decl)
}

/**
 * Heliocentric ecliptic longitude (radians) of a body at time [u]=jc/100 — used by Firenze's orrery.
 * Earth is π opposite the Sun's geocentric longitude; the rest use their WB heliocentric series.
 */
fun heliocentricLongitudeOfPlanet(planetNumber: Int, u: Double): Double = when (planetNumber) {
    PLANET_EARTH -> { var h = esFmod(PI + wbSunLongitudeRaw(u), 2 * PI); if (h < 0) h += 2 * PI; h }
    PLANET_MERCURY -> mercuryHelioLong(u)
    PLANET_VENUS -> venusHelioLong(u)
    PLANET_MARS -> marsHelioLong(u)
    PLANET_JUPITER -> outerLong(u, PlanetTables.jupiterData, PlanetTables.jupiterJD)
    PLANET_SATURN -> outerLong(u, PlanetTables.saturnData, PlanetTables.saturnJD)
    else -> 0.0
}

/** Apparent geocentric position (RA/decl/dist) of a WB planet (Mercury..Saturn) at time [u]=jc/100. */
fun wbPlanetApparentPosition(planetNumber: Int, u: Double): PlanetPosition {
    val sunLong = wbSunLongitudeRaw(u); val sunRad = wbSunRadius(u)
    val no = wbNutationObliquity(u)
    fun geo(hl: Double, hb: Double, hr: Double, la: Double, ba: Double) =
        convertGeocentric(hl, hb, hr, sunLong, sunRad, la, ba, no.obliquity, no.nutation)
    return when (planetNumber) {
        PLANET_MERCURY -> geo(mercuryHelioLong(u), mercuryHelioLat(u), mercuryRadius(u), mercuryLongAberration(u), mercuryLatAberration(u))
        PLANET_VENUS -> geo(venusHelioLong(u), venusHelioLat(u), venusRadius(u), venusLongAberration(u), 0.0)
        PLANET_MARS -> geo(marsHelioLong(u), marsHelioLat(u), marsRadius(u), marsLongAberration(u), 0.0)
        PLANET_JUPITER -> geo(outerLong(u, PlanetTables.jupiterData, PlanetTables.jupiterJD), outerLat(u, PlanetTables.jupiterData, PlanetTables.jupiterJD), outerRad(u, PlanetTables.jupiterData, PlanetTables.jupiterJD), jupiterLongAberration(u), 0.0)
        PLANET_SATURN -> geo(outerLong(u, PlanetTables.saturnData, PlanetTables.saturnJD), outerLat(u, PlanetTables.saturnData, PlanetTables.saturnJD), outerRad(u, PlanetTables.saturnData, PlanetTables.saturnJD), saturnLongAberration(u), 0.0)
        else -> error("wbPlanetApparentPosition: not a WB planet number: $planetNumber")
    }
}
