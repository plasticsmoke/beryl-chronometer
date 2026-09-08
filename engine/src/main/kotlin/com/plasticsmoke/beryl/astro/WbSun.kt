package com.plasticsmoke.beryl.astro

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Willmann-Bell Sun series, ported from chronometer-web `src/astronomy/wb-sun.ts`
 * (cache parameters dropped — values are recomputed). [u] is julianCenturies/100.
 */

/** Raw sun ecliptic longitude (no aberration/nutation), radians. */
internal fun wbSunLongitudeRaw(u: Double): Double {
    var longitude = 0.0
    for (i in 0 until SunData.n) {
        longitude += SunData.li[i] * sin(SunData.ali[i] + SunData.bli[i] * u)
    }
    longitude = 1e-7 * longitude + 4.9353929 + 62833.1961680 * u
    return esFmod(longitude, PI * 2)
}

/** Sun radius vector, AU. */
internal fun wbSunRadius(u: Double): Double {
    var radius = 0.0
    for (i in 0 until SunData.n) {
        radius += SunData.ri[i] * cos(SunData.ali[i] + SunData.bli[i] * u)
    }
    return 1e-7 * radius + 1.0001026
}

internal fun wbSunLongitudeAberration(u: Double): Double =
    1e-7 * (-993 + 17 * cos(3.10 + 62830.14 * u))

internal data class NutationObliquity(val nutation: Double, val obliquity: Double)

internal fun wbNutationObliquity(u: Double): NutationObliquity {
    val u2 = u * u
    val a1 = 2.18 - 3375.70 * u + 0.36 * u2
    val a2 = 3.51 + 125666.39 * u + 0.10 * u2
    val nutation = 1e-7 * (-834 * sin(a1) - 64 * sin(a2))
    val u3 = u * u2; val u4 = u2 * u2; val u5 = u * u4
    val obliquity = 0.4090928 + 1e-7 * (
        -226938 * u - 75 * u2 + 96926 * u3 - 2491 * u4 - 12104 * u5 +
            446 * cos(a1) + 28 * cos(a2)
        )
    return NutationObliquity(nutation, obliquity)
}

internal data class SunRaDecl(val rightAscension: Double, val declination: Double, val apparentLongitude: Double)

internal fun wbSunRAAndDecl(u: Double): SunRaDecl {
    val longitude = wbSunLongitudeRaw(u)
    val aberration = wbSunLongitudeAberration(u)
    val (nutation, obliquity) = wbNutationObliquity(u)
    var apparentLongitude = esFmod(longitude + aberration + nutation, PI * 2)
    if (apparentLongitude < 0) apparentLongitude += PI * 2

    val declination = asin(sin(obliquity) * sin(apparentLongitude))
    var rightAscension = atan2(cos(obliquity) * sin(apparentLongitude), cos(apparentLongitude))
    if (rightAscension < 0) rightAscension += PI * 2

    return SunRaDecl(rightAscension, declination, apparentLongitude)
}

/** Apparent sun ecliptic longitude (with aberration + nutation), radians. */
internal fun wbSunLongitudeApparent(u: Double): Double {
    val longitude = wbSunLongitudeRaw(u)
    val aberration = wbSunLongitudeAberration(u)
    val (nutation, _) = wbNutationObliquity(u)
    var apparentLongitude = esFmod(longitude + aberration + nutation, PI * 2)
    if (apparentLongitude < 0) apparentLongitude += PI * 2
    return apparentLongitude
}
