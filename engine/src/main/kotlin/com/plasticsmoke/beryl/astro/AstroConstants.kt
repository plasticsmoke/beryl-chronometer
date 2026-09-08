package com.plasticsmoke.beryl.astro

import kotlin.math.PI

/**
 * Astronomical constants and helpers, ported from chronometer-web `src/astronomy/astro-constants.ts`.
 * Time intervals are ESTimeInterval (seconds since 2001-01-01 UTC). Angles are radians.
 */

/** Julian date of 1990 Jan 0.0 (1989 Dec 31 0h UT). */
internal const val kECJulianDateOf1990Epoch = 2447891.5

/** ESTimeInterval of 1990 Jan 0.0. */
internal const val kEC1990Epoch = -347241600.0

/** Julian date of J2000.0 (2000 Jan 1 12h TT). */
internal const val kECJulianDateOf2000Epoch = 2451545.0

/** Julian days per Julian century. */
internal const val kECJulianDaysPerCentury = 36525.0

/** Ratio of UT units to GST units (sidereal day / solar day). */
internal const val kECUTUnitsPerGSTUnit = 0.9972695663

/** 1 AU in kilometers. */
internal const val kECAUInKilometers = 149597870.691

/** Standard refraction at the horizon (34 arcmin). */
internal val kECRefractionAtHorizonX = (34.0 / 60) * PI / 180

/** Latitude clamp to avoid the azimuth singularity at the poles (89°). */
internal val kECLimitingAzimuthLatitude = 89 * PI / 180

/** Lunar synodic period in seconds. */
internal const val kECLunarCycleInSeconds = 29.530589 * 24 * 3600

/** Standard twilight / golden-hour sun-center altitudes (radians). */
internal val kECCivilTwilightAltitude = -6 * PI / 180
internal val kECNauticalTwilightAltitude = -12 * PI / 180
internal val kECAstroTwilightAltitude = -18 * PI / 180
internal val kECGoldenHourAltitude = 6 * PI / 180

/** Planet numbers (iOS ECPlanetNumber: Sun=0, Moon=1, Mercury=2, Venus=3, Earth=4, Mars=5, ...). */
internal const val PLANET_SUN = 0
internal const val PLANET_MOON = 1
internal const val PLANET_MERCURY = 2
internal const val PLANET_VENUS = 3
internal const val PLANET_EARTH = 4
internal const val PLANET_MARS = 5
internal const val PLANET_JUPITER = 6
internal const val PLANET_SATURN = 7
/** Sentinel "planet" that inverts the day/night ring to show night leaves (iOS ECPlanetMidnightSun). */
internal const val PLANET_MIDNIGHT_SUN = 11

/** WB lunar precision levels (index into the per-precision count arrays). */
internal const val WB_LOW = 0
internal const val WB_MID = 1
internal const val WB_FULL = 2

/** Sentinels for "no rise/set" (finite, out of the normal time range; JS NaN!==NaN workaround). */
internal const val ALWAYS_ABOVE_HORIZON = 1e18
internal const val ALWAYS_BELOW_HORIZON = -1e18
internal fun isAlwaysAbove(v: Double) = v == ALWAYS_ABOVE_HORIZON
internal fun isAlwaysBelow(v: Double) = v == ALWAYS_BELOW_HORIZON
internal fun isNoRiseSet(v: Double) = isAlwaysAbove(v) || isAlwaysBelow(v)

/** Planet radii in AU (sun, moon). */
internal val planetRadiiInAU = doubleArrayOf(
    695500.0 / kECAUInKilometers,   // 0 Sun
    1737.10 / kECAUInKilometers,    // 1 Moon
    2439.7 / kECAUInKilometers,     // 2 Mercury
    6051.8 / kECAUInKilometers,     // 3 Venus
    6371.0 / kECAUInKilometers,     // 4 Earth
    3389.5 / kECAUInKilometers,     // 5 Mars
    69911.0 / kECAUInKilometers,    // 6 Jupiter
    58232.0 / kECAUInKilometers,    // 7 Saturn
)

/** Non-negative modulo (matches ESUtil::fmod): result in [0, y) for positive y. */
internal fun esFmod(x: Double, y: Double): Double {
    var r = x % y
    if (r < 0) r += y
    return r
}
