package com.plasticsmoke.beryl.astro

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Equatorial-coordinate wrappers, ported from chronometer-web `src/astronomy/es-coordinates.ts`
 * (sun functions; moon to follow). Cache-free.
 */

/** Ecliptic (lat, lon) + obliquity → equatorial (RA, decl). Ported from raAndDeclO. */
internal fun raAndDeclO(eclipticLatitude: Double, eclipticLongitude: Double, obliquity: Double): RaDecl {
    val sinDelta = sin(eclipticLatitude) * cos(obliquity) +
        cos(eclipticLatitude) * sin(obliquity) * sin(eclipticLongitude)
    val declination = asin(sinDelta)
    val y = sin(eclipticLongitude) * cos(obliquity) - tan(eclipticLatitude) * sin(obliquity)
    val x = cos(eclipticLongitude)
    return RaDecl(atan2(y, x), declination)
}

/** True obliquity of the ecliptic (radians) for the given Julian centuries (P03). */
internal fun generalObliquity(julianCenturiesSince2000Epoch: Double): Double {
    val t = julianCenturiesSince2000Epoch
    val t2 = t * t; val t3 = t * t2; val t4 = t2 * t2; val t5 = t2 * t3
    val e0 = 84381.406
    val eA = e0 - 46.836769 * t - 0.0001831 * t2 + 0.00200340 * t3 - 0.000000576 * t4 - 0.0000000434 * t5
    return eA * PI / (3600 * 180)
}

/** General precession in RA since J2000 (radians) for the given Julian centuries. */
internal fun generalPrecessionSinceJ2000(julianCenturiesSince2000Epoch: Double): Double {
    val t = julianCenturiesSince2000Epoch
    val t2 = t * t
    val t3 = t * t2
    val t4 = t2 * t2
    val t5 = t2 * t3
    val arcSeconds = 5028.796195 * t + 1.1054348 * t2 + 0.00007964 * t3 -
        0.000023857 * t4 - 0.0000000383 * t5
    return arcSeconds * PI / (3600 * 180)
}

internal data class TopocentricResult(val hPrime: Double, val declPrime: Double)

/** Topocentric parallax correction (Meeus ch. 11 & 40). [observerAltitude] in metres. */
internal fun topocentricParallax(
    ra: Double, decl: Double, h: Double, distInAU: Double,
    observerLatitude: Double, observerAltitude: Double,
): TopocentricResult {
    val bOverA = 0.99664719
    val u = atan(bOverA * tan(observerLatitude))
    val delta = observerAltitude / 6378140
    val rhoSinPhiPrime = bOverA * sin(u) + delta * sin(observerLatitude)
    val rhoCosPhiPrime = cos(u) + delta * cos(observerLatitude)
    val sinPi = sin(8.794 / 3600 * PI / 180) / distInAU
    val a = cos(decl) * sin(h)
    val b = cos(decl) * cos(h) - rhoCosPhiPrime * sinPi
    val c = sin(decl) - rhoSinPhiPrime * sinPi
    val q = sqrt(a * a + b * b + c * c)
    var hPrime = atan2(a, b)
    if (hPrime < 0) hPrime += 2 * PI
    return TopocentricResult(hPrime, asin(c / q))
}

data class RaDecl(val rightAscension: Double, val declination: Double)

/** Sun right ascension and declination (apparent, of-date) for a UT date interval. */
fun sunRAandDecl(dateInterval: Double): RaDecl {
    val jc = julianCenturiesSince2000EpochForDateInterval(dateInterval).julianCenturiesSince2000Epoch
    val r = wbSunRAAndDecl(jc / 100)
    return RaDecl(r.rightAscension, r.declination)
}

/** Sun apparent ecliptic longitude (of-date), radians. */
fun sunEclipticLongitudeForDate(dateInterval: Double): Double {
    val jc = julianCenturiesSince2000EpochForDateInterval(dateInterval).julianCenturiesSince2000Epoch
    return wbSunLongitudeApparent(jc / 100)
}

data class MoonRaDecl(val rightAscension: Double, val declination: Double, val moonEclipticLongitude: Double)

/** Moon RA, declination, and ecliptic longitude (of-date) for a UT date interval. */
fun moonRAAndDecl(dateInterval: Double): MoonRaDecl {
    // Note: the moon series takes julianCenturies directly (the sun series takes /100).
    val jc = julianCenturiesSince2000EpochForDateInterval(dateInterval).julianCenturiesSince2000Epoch
    val r = wbMoonRAAndDecl(jc)
    return MoonRaDecl(r.rightAscension, r.declination, r.longitude)
}

/** Moon apparent ecliptic latitude (of-date), radians. */
fun moonEclipticLatitude(dateInterval: Double): Double {
    val jc = julianCenturiesSince2000EpochForDateInterval(dateInterval).julianCenturiesSince2000Epoch
    return wbMoonRAAndDecl(jc).latitude
}

/** RA and declination (apparent, of-date) of any body: sun, moon, or WB planet (Mercury..Saturn). */
fun raAndDeclOfPlanet(planetNumber: Int, dateInterval: Double): RaDecl = when (planetNumber) {
    PLANET_SUN -> sunRAandDecl(dateInterval)
    PLANET_MOON -> moonRAAndDecl(dateInterval).let { RaDecl(it.rightAscension, it.declination) }
    else -> {
        val jc = julianCenturiesSince2000EpochForDateInterval(dateInterval).julianCenturiesSince2000Epoch
        val p = wbPlanetApparentPosition(planetNumber, jc / 100)
        RaDecl(p.rightAscension, p.declination)
    }
}

// --- Angular size, parallax, and the altitude at rise/set (Meeus h0) ---

internal fun distanceOfPlanetInAU(planetNumber: Int, jc: Double, p: Int): Double = when (planetNumber) {
    PLANET_SUN -> wbSunRadius(jc / 100)
    PLANET_MOON -> wbMoonDistance(jc, p) / kECAUInKilometers
    else -> wbPlanetApparentPosition(planetNumber, jc / 100).geocentricDistanceAU
}

internal data class SizeParallax(val angularSize: Double, val parallax: Double)

internal fun planetSizeAndParallax(planetNumber: Int, distanceInAU: Double): SizeParallax {
    val radiusInAU = planetRadiiInAU[planetNumber]
    val angularSize = 2 * kotlin.math.atan(radiusInAU / distanceInAU)
    val parallax = kotlin.math.asin(kotlin.math.sin(8.794 / 3600 * kotlin.math.PI / 180) / distanceInAU)
    return SizeParallax(angularSize, parallax)
}

/** Meeus's h0: the (geocentric) altitude at rise/set, accounting for size, parallax, refraction. */
internal fun altitudeAtRiseSet(jc: Double, planetNumber: Int, wantGeocentricAltitude: Boolean, p: Int): Double {
    val dist = distanceOfPlanetInAU(planetNumber, jc, p)
    val sp = planetSizeAndParallax(planetNumber, dist)
    return (if (wantGeocentricAltitude) sp.parallax else 0.0) - kECRefractionAtHorizonX - sp.angularSize / 2.0
}
