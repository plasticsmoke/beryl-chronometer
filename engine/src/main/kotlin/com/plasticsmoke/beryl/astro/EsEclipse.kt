package com.plasticsmoke.beryl.astro

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Solar/lunar eclipse classification and separation, ported from chronometer-web
 * `src/astronomy/es-astro.ts` calculateEclipse. Drives the Geneva back's eclipse separation hand
 * and eclipse-kind wheel. Cache-free.
 */

// EclipseKind values (match ECPlanetNumber/iOS ordering).
internal const val ECLIPSE_NONE_SOLAR = 0
internal const val ECLIPSE_NONE_LUNAR = 1
internal const val ECLIPSE_SOLAR_NOT_UP = 2
internal const val ECLIPSE_PARTIAL_SOLAR = 3
internal const val ECLIPSE_ANNULAR_SOLAR = 4
internal const val ECLIPSE_TOTAL_SOLAR = 5
internal const val ECLIPSE_LUNAR_NOT_UP = 6
internal const val ECLIPSE_PARTIAL_LUNAR = 7
internal const val ECLIPSE_TOTAL_LUNAR = 8

data class EclipseResult(
    val abstractSeparation: Double,
    val angularSeparation: Double,
    val shadowAngularSize: Double,
    val eclipseKind: Int,
)

/** Angular separation (radians) between two equatorial positions. */
private fun angularSeparation(ra1: Double, decl1: Double, ra2: Double, decl2: Double): Double {
    val sinDecl1 = sin(decl1); val cosDecl1 = cos(decl1)
    val sinDecl2 = sin(decl2); val cosDecl2 = cos(decl2)
    val sinRADelta = sin(ra2 - ra1); val cosRADelta = cos(ra2 - ra1)
    val x = cosDecl1 * sinDecl2 - sinDecl1 * cosDecl2 * cosRADelta
    val y = cosDecl2 * sinRADelta
    val z = sinDecl1 * sinDecl2 + cosDecl1 * cosDecl2 * cosRADelta
    return atan2(sqrt(x * x + y * y), z)
}

/** Angular radius of Earth's umbral shadow at the Moon's distance (Meeus). */
private fun umbralAngularRadius(moonParallax: Double, sunAngularRadius: Double, sunParallax: Double): Double =
    1.01 * moonParallax - sunAngularRadius + sunParallax

/** Classify any solar/lunar eclipse in progress and the abstract 0..3 separation. */
fun calculateEclipse(dateInterval: Double, observerLatitude: Double, observerLongitude: Double): EclipseResult {
    val gst = convertUTToGSTP03(dateInterval)
    val lst = convertGSTtoLST(gst, observerLongitude)
    val jc = julianCenturiesSince2000EpochForDateInterval(dateInterval).julianCenturiesSince2000Epoch

    val sun = sunRAandDecl(dateInterval)
    val sunDistAU = distanceOfPlanetInAU(PLANET_SUN, jc, WB_FULL)
    val sunSP = planetSizeAndParallax(PLANET_SUN, sunDistAU)
    val sunAngularSize = sunSP.angularSize
    val sunParallax = sunSP.parallax

    val moon = moonRAAndDecl(dateInterval)
    val moonDistAU = distanceOfPlanetInAU(PLANET_MOON, jc, WB_FULL)
    val moonSP = planetSizeAndParallax(PLANET_MOON, moonDistAU)
    val moonAngularSize = moonSP.angularSize
    val moonParallax = moonSP.parallax

    val raDelta = esFmod(abs(moon.rightAscension - sun.rightAscension), 2 * PI)

    val physicalSeparation: Double
    val separationAtPartial: Double
    val separationAtTotal: Double
    var eclipseKind: Int
    val solarNotLunar: Boolean
    var shadowAngularSize = 0.0

    if (raDelta < PI / 2) {
        // Near new moon — possible solar eclipse (topocentric positions).
        val sunTopo = topocentricParallax(sun.rightAscension, sun.declination, lst - sun.rightAscension, sunDistAU, observerLatitude, 0.0)
        val sunTopoRA = lst - sunTopo.hPrime
        val moonTopo = topocentricParallax(moon.rightAscension, moon.declination, lst - moon.rightAscension, moonDistAU, observerLatitude, 0.0)
        val moonTopoRA = lst - moonTopo.hPrime

        physicalSeparation = angularSeparation(sunTopoRA, sunTopo.declPrime, moonTopoRA, moonTopo.declPrime)
        separationAtPartial = sunAngularSize / 2 + moonAngularSize / 2
        separationAtTotal = moonAngularSize / 2 - sunAngularSize / 2
        val separationAtAnnular = sunAngularSize / 2 - moonAngularSize / 2

        val sunAlt = planetAltAz(PLANET_SUN, dateInterval, observerLatitude, observerLongitude, true, true)
        val altAtRS = altitudeAtRiseSet(jc, PLANET_SUN, false, WB_FULL)
        eclipseKind = when {
            sunAlt < altAtRS -> ECLIPSE_SOLAR_NOT_UP
            physicalSeparation > separationAtPartial -> ECLIPSE_NONE_SOLAR
            physicalSeparation < separationAtAnnular -> ECLIPSE_ANNULAR_SOLAR
            physicalSeparation > separationAtTotal -> ECLIPSE_PARTIAL_SOLAR
            else -> ECLIPSE_TOTAL_SOLAR
        }
        solarNotLunar = true
    } else {
        // Near full moon — possible lunar eclipse (Earth's shadow at anti-sun point).
        shadowAngularSize = 2 * umbralAngularRadius(moonParallax, sunAngularSize / 2, sunParallax)
        var shadowRA = sun.rightAscension + PI
        if (shadowRA > 2 * PI) shadowRA -= 2 * PI
        val shadowDecl = -sun.declination

        physicalSeparation = angularSeparation(shadowRA, shadowDecl, moon.rightAscension, moon.declination)
        separationAtPartial = moonAngularSize / 2 + shadowAngularSize / 2
        separationAtTotal = shadowAngularSize / 2 - moonAngularSize / 2

        val moonAlt = planetAltAz(PLANET_MOON, dateInterval, observerLatitude, observerLongitude, true, true)
        val altAtRS = altitudeAtRiseSet(jc, PLANET_MOON, false, WB_FULL)
        eclipseKind = when {
            moonAlt < altAtRS -> ECLIPSE_LUNAR_NOT_UP
            physicalSeparation > separationAtPartial -> ECLIPSE_NONE_LUNAR
            physicalSeparation > separationAtTotal -> ECLIPSE_PARTIAL_LUNAR
            else -> ECLIPSE_TOTAL_LUNAR
        }
        solarNotLunar = false
    }

    var abstractSeparation = 1 + (physicalSeparation - separationAtTotal) / (separationAtPartial - separationAtTotal)
    if (abstractSeparation < 0) {
        abstractSeparation = 0.0
    } else if (abstractSeparation > 3) {
        abstractSeparation = 3.0
        eclipseKind = if (solarNotLunar) ECLIPSE_NONE_SOLAR else ECLIPSE_NONE_LUNAR
    }

    return EclipseResult(abstractSeparation, physicalSeparation, shadowAngularSize, eclipseKind)
}
