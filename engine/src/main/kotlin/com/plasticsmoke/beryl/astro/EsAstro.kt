package com.plasticsmoke.beryl.astro

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-level astronomy API, ported from chronometer-web `src/astronomy/es-astro.ts` (sun/moon
 * subset). Altitude/azimuth and rise/set are added as needed; cache-free.
 */

/** Moon age (radians, 0=new) and illuminated fraction (phase, 0=new..1=full). */
data class MoonAgeResult(val age: Double, val phase: Double)

fun moonAge(dateInterval: Double): MoonAgeResult {
    val moonEclipticLongitude = moonRAAndDecl(dateInterval).moonEclipticLongitude
    val jc = julianCenturiesSince2000EpochForDateInterval(dateInterval).julianCenturiesSince2000Epoch
    val sunEclipticLong = wbSunLongitudeApparent(jc / 100)

    var age = moonEclipticLongitude - sunEclipticLong
    if (age < 0) age += PI * 2
    val phase = (1 - cos(age)) / 2
    return MoonAgeResult(age, phase)
}

/** Equation of time in seconds (apparent − mean solar), ported from EOTSeconds. */
fun eotSeconds(dateInterval: Double): Double {
    val noonD = floor(dateInterval / 86400) * 86400 + 43200
    val noonUT = if (noonD - dateInterval > 43200) noonD - 86400 else noonD
    val secondsFromNoon = dateInterval - noonUT
    val longitudeOfMeanSun = -secondsFromNoon * PI / (12 * 3600)
    val sunRA = sunRAandDecl(dateInterval).rightAscension
    val gast = sunRA - longitudeOfMeanSun
    val utDate = convertGSTtoUTclosest(gast, dateInterval)
    return dateInterval - utDate
}

private const val kECsinMoonEquatorEclipticAngle = 0.026917056028711
private const val kECcosMoonEquatorEclipticAngle = 0.999637670406006

/**
 * Rotation of the Moon's disc as it appears in the sky (radians, [0,2π)) — the moon-image angle.
 * Ported from es-astro.ts moonRelativeAngle (iOS ESAstronomy moonRelativeAngle, Meeus p373).
 */
fun moonRelativeAngle(dateInterval: Double, observerLatitude: Double, observerLongitude: Double): Double {
    val moon = moonRAAndDecl(dateInterval)
    val moonRA = moon.rightAscension
    val moonDecl = moon.declination

    val gst = convertUTToGSTP03(dateInterval)
    val lst = convertGSTtoLST(gst, observerLongitude)
    val moonHourAngle = lst - moonRA
    val sinAlt = sin(moonDecl) * sin(observerLatitude) + cos(moonDecl) * cos(observerLatitude) * cos(moonHourAngle)
    val moonAz = atan2(
        -cos(moonDecl) * cos(observerLatitude) * sin(moonHourAngle),
        sin(moonDecl) - sin(observerLatitude) * sinAlt,
    )
    val moonAlt = asin(sinAlt)
    val northAngle = northAngleForObject(moonAlt, moonAz, observerLatitude)

    val apparentGeocentricLongitude = moonRA - gst
    val apparentGeocentricLatitude = moonDecl
    val jc = julianCenturiesSince2000EpochForDateInterval(dateInterval).julianCenturiesSince2000Epoch
    val eclipticTrueObliquity = generalObliquity(jc)
    val node = wbMoonAscendingNodeLongitude(jc)

    val w = apparentGeocentricLongitude - node
    val b = asin(
        -sin(w) * cos(apparentGeocentricLatitude) * kECsinMoonEquatorEclipticAngle -
            sin(apparentGeocentricLatitude) * kECcosMoonEquatorEclipticAngle,
    )
    val x = kECsinMoonEquatorEclipticAngle * sin(node)
    val y = kECsinMoonEquatorEclipticAngle * cos(node) * cos(eclipticTrueObliquity) -
        kECcosMoonEquatorEclipticAngle * sin(eclipticTrueObliquity)
    val omega = atan2(x, y)
    val sinP = sqrt(x * x + y * y) * cos(moonRA - omega) / cos(b)
    val posAngle = asin(sinP)

    var angle = -northAngle - posAngle
    if (angle < 0) angle += 2 * PI else if (angle > 2 * PI) angle -= 2 * PI
    return angle
}

/** Rotation to orient the analemma in the observer's sky (radians, [0,2π)) = −sun north angle. */
fun sunSkyOrientationAngle(dateInterval: Double, observerLatitude: Double, observerLongitude: Double): Double {
    val sun = sunRAandDecl(dateInterval)
    val gst = convertUTToGSTP03(dateInterval)
    val lst = convertGSTtoLST(gst, observerLongitude)
    val sunHourAngle = lst - sun.rightAscension
    val sinAlt = sin(sun.declination) * sin(observerLatitude) + cos(sun.declination) * cos(observerLatitude) * cos(sunHourAngle)
    val sunAz = atan2(
        -cos(sun.declination) * cos(observerLatitude) * sin(sunHourAngle),
        sin(sun.declination) - sin(observerLatitude) * sinAlt,
    )
    val sunAlt = asin(sinAlt)
    val northAngle = northAngleForObject(sunAlt, sunAz, observerLatitude)
    var angle = -northAngle
    if (angle < 0) angle += 2 * PI else if (angle > 2 * PI) angle -= 2 * PI
    return angle
}

/** Right ascension of the Moon's ascending node (radians, [0,2π)). */
fun lunarAscendingNodeRA(dateInterval: Double): Double {
    val jc = julianCenturiesSince2000EpochForDateInterval(dateInterval).julianCenturiesSince2000Epoch
    val longitude = wbMoonAscendingNodeLongitude(jc)
    val obliquity = wbNutationObliquity(jc / 100).obliquity
    var ra = raAndDeclO(0.0, longitude, obliquity).rightAscension
    if (ra < 0) ra += 2 * PI
    return ra
}

// --- Closest moon-phase quarter times (for Chandra's phase day-number wheels) ---

/** One Newton step toward the date where the moon's age equals [targetAge]. */
private fun stepRefineMoonAgeTarget(dateInterval: Double, targetAge: Double): Double {
    val age = moonAge(dateInterval).age
    var deltaAge = targetAge - age
    if (deltaAge > PI) deltaAge -= 2 * PI else if (deltaAge < -PI) deltaAge += 2 * PI
    return dateInterval + deltaAge / (2 * PI) * kECLunarCycleInSeconds
}

/** Refine to the date where the moon's age equals [targetAge] (≤5 iterations). */
private fun refineMoonAgeTargetForDate(dateInterval: Double, targetAge: Double): Double {
    var tryDate = dateInterval
    for (i in 0 until 5) {
        val newDate = stepRefineMoonAgeTarget(tryDate, targetAge)
        if (abs(newDate - tryDate) < 0.1) return newDate
        tryDate = newDate
    }
    return tryDate
}

/** Time interval of the moon phase closest to [dateInterval] at the given [quarterAngle] (0/π/2/π/3π/2). */
fun closestQuarterPhaseTime(quarterAngle: Double, dateInterval: Double): Double {
    val age = moonAge(dateInterval).age
    val ageSinceQuarter = esFmod(age - quarterAngle, 2 * PI)
    val closestIsBack = ageSinceQuarter < PI - 0.01
    val guessDate = if (closestIsBack) {
        dateInterval - kECLunarCycleInSeconds * ageSinceQuarter / (2 * PI)
    } else {
        dateInterval + kECLunarCycleInSeconds * (2 * PI - ageSinceQuarter) / (2 * PI)
    }
    return refineMoonAgeTargetForDate(guessDate, quarterAngle)
}

/** Position angle of an object relative to the Sun (radians). */
internal fun positionAngle(sunRA: Double, sunDecl: Double, objRA: Double, objDecl: Double): Double =
    atan2(
        cos(sunDecl) * sin(sunRA - objRA),
        cos(objDecl) * sin(sunDecl) - sin(objDecl) * cos(sunDecl) * cos(sunRA - objRA),
    )

/** Great-circle initial course from (lat1,lon1) to (lat2,lon2). */
internal fun greatCircleCourse(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
    atan2(
        sin(lon1 - lon2) * cos(lat2),
        cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(lon1 - lon2),
    )

/** Angle from an object (alt,az) to the celestial north pole as seen by the observer. */
internal fun northAngleForObject(altitude: Double, azimuth: Double, observerLatitude: Double): Double =
    greatCircleCourse(altitude, azimuth, observerLatitude, 0.0)

/**
 * Rotation of the moon terminator as it appears in the sky (radians, [0,2π)).
 * Ported from es-astro.ts moonRelativePositionAngle.
 */
fun moonRelativePositionAngle(dateInterval: Double, observerLatitude: Double, observerLongitude: Double): Double {
    val sun = sunRAandDecl(dateInterval)
    val moon = moonRAAndDecl(dateInterval)
    var posAngle = positionAngle(sun.rightAscension, sun.declination, moon.rightAscension, moon.declination)

    val ageAngle = moonAge(dateInterval).age
    if (ageAngle > PI) {
        posAngle += if (posAngle > PI) -PI else PI
    }

    val gst = convertUTToGSTP03(dateInterval)
    val lst = convertGSTtoLST(gst, observerLongitude)
    val moonHourAngle = lst - moon.rightAscension
    val sinAlt = sin(moon.declination) * sin(observerLatitude) +
        cos(moon.declination) * cos(observerLatitude) * cos(moonHourAngle)
    val moonAz = atan2(
        -cos(moon.declination) * cos(observerLatitude) * sin(moonHourAngle),
        sin(moon.declination) - sin(observerLatitude) * sinAlt,
    )
    val moonAlt = asin(sinAlt)
    val northAngle = northAngleForObject(moonAlt, moonAz, observerLatitude)

    var angle = -northAngle - posAngle - PI / 2
    if (angle < 0) angle += 2 * PI else if (angle > 2 * PI) angle -= 2 * PI
    return angle
}
