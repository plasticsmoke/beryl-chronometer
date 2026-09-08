package com.plasticsmoke.beryl.astro

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Altitude / azimuth and local sidereal time, ported from chronometer-web
 * `src/astronomy/es-astro.ts` (sun/moon subset; planets deferred — they need the full
 * Willmann-Bell planet theory). Cache-free.
 */

/** Local apparent sidereal time (radians) for a UT date interval and observer longitude. */
fun localSiderealTime(dateInterval: Double, observerLongitude: Double): Double =
    convertGSTtoLST(convertUTToGSTP03(dateInterval), observerLongitude)

/**
 * Altitude (or azimuth) of the sun/moon, ported from planetAltAz. [correctForParallax] applies
 * topocentric parallax (moon only); [altNotAz] selects altitude (true) vs azimuth (false).
 */
internal fun planetAltAz(
    planetNumber: Int,
    dateInterval: Double,
    observerLatitudeIn: Double,
    observerLongitude: Double,
    correctForParallax: Boolean,
    altNotAz: Boolean,
): Double {
    var observerLatitude = observerLatitudeIn
    if (observerLatitude > kECLimitingAzimuthLatitude) observerLatitude = kECLimitingAzimuthLatitude
    else if (observerLatitude < -kECLimitingAzimuthLatitude) observerLatitude = -kECLimitingAzimuthLatitude

    val radecl = raAndDeclOfPlanet(planetNumber, dateInterval)
    var ra = radecl.rightAscension
    var decl = radecl.declination

    val lst = localSiderealTime(dateInterval, observerLongitude)
    var hourAngle = lst - ra

    if (correctForParallax) {
        val jc = julianCenturiesSince2000EpochForDateInterval(dateInterval).julianCenturiesSince2000Epoch
        val dist = distanceOfPlanetInAU(planetNumber, jc, WB_FULL)
        val tp = topocentricParallax(ra, decl, hourAngle, dist, observerLatitude, 0.0)
        decl = tp.declPrime
        hourAngle = tp.hPrime
    }

    val sinAlt = sin(decl) * sin(observerLatitude) + cos(decl) * cos(observerLatitude) * cos(hourAngle)
    return if (altNotAz) {
        asin(sinAlt)
    } else {
        atan2(
            -cos(decl) * cos(observerLatitude) * sin(hourAngle),
            sin(decl) - sin(observerLatitude) * sinAlt,
        )
    }
}

/**
 * Azimuth (radians) of the point where the ecliptic reaches its highest altitude — drives Firenze's
 * back horizon mask. Ported from iOS ESAstronomy.cpp calculateHighestEcliptic (azimuth branch).
 */
fun azimuthOfHighestEclipticAltitude(dateInterval: Double, lat: Double, lon: Double): Double {
    val jc = julianCenturiesSince2000EpochForDateInterval(dateInterval).julianCenturiesSince2000Epoch
    val obliquity = wbNutationObliquity(jc / 100).obliquity
    val lst = localSiderealTime(dateInterval, lon)
    val sinObliquity = sin(obliquity); val cosObliquity = cos(obliquity)
    val sinLst = sin(lst); val cosObsLat = cos(lat); val sinObsLat = sin(lat)
    var eclipticLongitude = atan2(-cos(lst), sinObliquity * kotlin.math.tan(lat) + cosObliquity * sinLst) + PI / 2
    val sinEclipLong = sin(eclipticLongitude)
    val declination = asin(sinObliquity * sinEclipLong)
    val rightAscension = atan2(cosObliquity * sinEclipLong, cos(eclipticLongitude))
    val hourAngle = lst - rightAscension
    val sinAlt = sin(declination) * sinObsLat + cos(declination) * cosObsLat * cos(hourAngle)
    var azimuth = atan2(-cos(declination) * cosObsLat * sin(hourAngle), sin(declination) - sinObsLat * sinAlt)
    azimuth = esFmod(if (sinAlt < 0) azimuth + PI else azimuth, 2 * PI)
    if (azimuth < 0) azimuth += 2 * PI
    return azimuth
}

fun sunAltitude(dateInterval: Double, lat: Double, lon: Double): Double =
    planetAltAz(PLANET_SUN, dateInterval, lat, lon, false, true)

fun sunAzimuth(dateInterval: Double, lat: Double, lon: Double): Double =
    planetAltAz(PLANET_SUN, dateInterval, lat, lon, false, false)

fun moonAltitude(dateInterval: Double, lat: Double, lon: Double): Double =
    planetAltAz(PLANET_MOON, dateInterval, lat, lon, true, true)

fun moonAzimuth(dateInterval: Double, lat: Double, lon: Double): Double =
    planetAltAz(PLANET_MOON, dateInterval, lat, lon, true, false)
