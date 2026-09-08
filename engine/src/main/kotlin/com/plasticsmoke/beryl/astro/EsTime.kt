package com.plasticsmoke.beryl.astro

/**
 * Minimal time-interval utilities, ported from chronometer-web `src/astronomy/es-time.ts`.
 *
 * An "ESTimeInterval" is seconds since the Apple/NeXT reference date
 * (2001-01-01 00:00:00 UTC) — the same convention as Cocoa's NSTimeInterval.
 *
 * NOTE: the full es-time.ts also contains Delta-T / Julian-date machinery used by
 * the sun/moon astronomy. That is intentionally deferred until a face needs it;
 * Milano only needs the epoch conversion below.
 */

/** Seconds between the Unix epoch (1970-01-01) and the Apple epoch (2001-01-01), both UTC. */
const val APPLE_EPOCH_OFFSET = 978307200.0

/** Convert epoch milliseconds (UTC) to an ESTimeInterval (seconds since 2001-01-01 UTC). */
fun dateIntervalFromEpochMillis(millis: Long): Double = millis / 1000.0 - APPLE_EPOCH_OFFSET

/** Convert an ESTimeInterval back to epoch milliseconds (UTC). */
fun epochMillisFromDateInterval(interval: Double): Long =
    ((interval + APPLE_EPOCH_OFFSET) * 1000.0).toLong()

// ============================================================================
// Delta-T, Julian date, and Julian centuries since J2000.0 (ported from es-time.ts)
// ============================================================================

/**
 * Delta T from Espenak & Meeus (2006), "Five Millennium Canon of Solar Eclipses".
 * This is the default UT→ET correction used throughout the astronomy.
 */
internal fun espenakDeltaT(yearValue: Double): Double {
    return when {
        yearValue in 2005.0..2050.0 -> {
            val t = yearValue - 2000
            62.92 + 0.32217 * t + 0.005589 * t * t
        }
        yearValue < -500 || yearValue >= 2150 -> {
            val u = (yearValue - 1820) / 100
            -20 + 32 * u * u
        }
        yearValue < 500 -> {
            val u = yearValue / 100; val u2 = u * u; val u3 = u2 * u; val u4 = u2 * u2; val u5 = u3 * u2; val u6 = u3 * u3
            10583.6 - 1014.41 * u + 33.78311 * u2 - 5.952053 * u3 - 0.1798452 * u4 + 0.022174192 * u5 + 0.0090316521 * u6
        }
        yearValue < 1600 -> {
            val u = (yearValue - 1000) / 100; val u2 = u * u; val u3 = u2 * u; val u4 = u2 * u2; val u5 = u3 * u2; val u6 = u3 * u3
            1574.2 - 556.01 * u + 71.23472 * u2 + 0.319781 * u3 - 0.8503463 * u4 - 0.005050998 * u5 + 0.0083572073 * u6
        }
        yearValue < 1700 -> {
            val t = yearValue - 1600; val t2 = t * t; val t3 = t2 * t
            120 - 0.9808 * t - 0.01532 * t2 + t3 / 7129
        }
        yearValue < 1800 -> {
            val t = yearValue - 1700; val t2 = t * t; val t3 = t2 * t; val t4 = t2 * t2
            8.83 + 0.1603 * t - 0.0059285 * t2 + 0.00013336 * t3 - t4 / 1174000
        }
        yearValue < 1860 -> {
            val t = yearValue - 1800; val t2 = t * t; val t3 = t2 * t; val t4 = t2 * t2; val t5 = t3 * t2; val t6 = t3 * t3; val t7 = t4 * t3
            13.72 - 0.332447 * t + 0.0068612 * t2 + 0.0041116 * t3 - 0.00037436 * t4 +
                0.0000121272 * t5 - 0.0000001699 * t6 + 0.000000000875 * t7
        }
        yearValue < 1900 -> {
            val t = yearValue - 1860; val t2 = t * t; val t3 = t2 * t; val t4 = t2 * t2; val t5 = t3 * t2
            7.62 + 0.5737 * t - 0.251754 * t2 + 0.01680668 * t3 - 0.0004473624 * t4 + t5 / 233174
        }
        yearValue < 1920 -> {
            val t = yearValue - 1900; val t2 = t * t; val t3 = t2 * t; val t4 = t2 * t2
            -2.79 + 1.494119 * t - 0.0598939 * t2 + 0.0061966 * t3 - 0.000197 * t4
        }
        yearValue < 1941 -> {
            val t = yearValue - 1920; val t2 = t * t; val t3 = t2 * t
            21.20 + 0.84493 * t - 0.076100 * t2 + 0.0020936 * t3
        }
        yearValue < 1961 -> {
            val t = yearValue - 1950; val t2 = t * t; val t3 = t2 * t
            29.07 + 0.407 * t - t2 / 233 + t3 / 2547
        }
        yearValue < 1986 -> {
            val t = yearValue - 1975; val t2 = t * t; val t3 = t2 * t
            45.45 + 1.067 * t - t2 / 260 - t3 / 718
        }
        yearValue < 2005 -> {
            val t = yearValue - 2000; val t2 = t * t; val t3 = t2 * t; val t4 = t2 * t2; val t5 = t3 * t2
            63.86 + 0.3345 * t - 0.060374 * t2 + 0.0017275 * t3 + 0.000651814 * t4 + 0.00002373599 * t5
        }
        else -> {
            // 2050 < yearValue < 2150
            val t1 = (yearValue - 1820) / 100
            -20 + 32 * t1 * t1 - 0.5628 * (2150 - yearValue)
        }
    }
}

/** Convert an ESTimeInterval (Apple epoch seconds) to a Julian date. */
internal fun julianDateForDate(dateInterval: Double): Double {
    val secondsSince1990 = dateInterval - kEC1990Epoch
    return kECJulianDateOf1990Epoch + secondsSince1990 / (24 * 3600)
}

/** Prior UT midnight for a date interval (Apple epoch seconds). */
internal fun priorUTMidnightForDateInterval(dateInterval: Double): Double {
    val unixSec = dateInterval + APPLE_EPOCH_OFFSET
    val midnight = kotlin.math.floor(unixSec / 86400.0) * 86400.0
    return midnight - APPLE_EPOCH_OFFSET
}

internal data class JulianCenturiesResult(val julianCenturiesSince2000Epoch: Double, val deltaT: Double)

/** TDT/ET Julian centuries since J2000.0 for a UT date interval (cache-free port). */
internal fun julianCenturiesSince2000EpochForDateInterval(dateInterval: Double): JulianCenturiesResult {
    val utSeconds = dateInterval
    val instant = java.time.Instant.ofEpochMilli(((utSeconds + APPLE_EPOCH_OFFSET) * 1000.0).toLong())
    val year = instant.atZone(java.time.ZoneOffset.UTC).year
    val jan1Unix = java.time.LocalDate.of(year, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()
    val firstOfThisYearInterval = jan1Unix.toDouble() - APPLE_EPOCH_OFFSET
    val yearValue = year + (utSeconds - firstOfThisYearInterval) / (365.25 * 24 * 3600)
    val etSeconds = utSeconds + espenakDeltaT(yearValue)
    val deltaT = etSeconds - utSeconds
    val julianDaysSince2000 = julianDateForDate(etSeconds) - kECJulianDateOf2000Epoch
    return JulianCenturiesResult(julianDaysSince2000 / kECJulianDaysPerCentury, deltaT)
}
