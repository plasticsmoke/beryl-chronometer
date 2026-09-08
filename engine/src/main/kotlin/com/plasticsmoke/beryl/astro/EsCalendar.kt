package com.plasticsmoke.beryl.astro

import kotlin.math.floor

/**
 * Hybrid Julian/Gregorian calendar, ported from chronometer-web `src/astronomy/es-calendar.ts`
 * (itself ported from ESCalendar.cpp in the estime library).
 *
 * Convention: Gregorian for dates on/after 1582-10-15, Julian from 1 BCE to then, and a
 * proleptic Julian calendar before 1 BCE. Days 1582-10-05..14 do not exist. Times are
 * ESTimeInterval (seconds since 2001-01-01 00:00:00 UTC).
 *
 * Scope: the functions Milano and the near-term calendar faces need. The Gregorian↔hybrid
 * conversions and calendar-aware month/year stepping from es-calendar.ts are deferred until
 * a face exercises them.
 */

// --- Constants from ESCalendarPvt.hpp ---
private const val kECJulianDayOf1990Epoch = 2447891.5
// kEC1990Epoch is shared from AstroConstants.kt (same package).
private const val kECAverageDaysInGregorianYear = 365.2425
private const val kECDaysInGregorianCycle = kECAverageDaysInGregorianYear * 400  // 146097
private const val kECDaysInJulianCycle = 365.25 * 4                              // 1461
private const val kECDaysInNonLeapCentury = 36525.0

/** ESTimeInterval of 1582-10-15 00:00:00 UTC — the Julian/Gregorian switchover. */
const val kECJulianGregorianSwitchoverTimeInterval = -13197600000.0

data class EsDateComponents(
    val era: Int,       // 0 = BCE, 1 = CE
    val year: Int,      // always positive; era disambiguates
    val month: Int,     // 1–12
    val day: Int,       // 1–31
    val hour: Int,      // 0–23
    val minute: Int,    // 0–59
    val seconds: Double, // 0–59.999…
)

/** JS `Math.round` semantics (round half toward +Infinity), as used by the original. */
private fun jsRound(x: Double): Int = floor(x + 0.5).toInt()

/**
 * Decompose an ESTimeInterval (UTC) into calendar components.
 * Ported from ESCalendar_UTCComponentsFromTimeInterval().
 *
 * The date fields (era/year/month/day) are exact. The time-of-day fields (hour/minute/seconds)
 * are reconstructed from the day's fractional remainder and inherit the original algorithm's
 * limited sub-day precision (e.g. a clean :15 can read 14:59.99…). This is harmless: watch faces
 * take time-of-day from the live clock (see WatchEnvironment.liveTime), never from here.
 */
fun utcComponentsFromTimeInterval(timeInterval: Double): EsDateComponents {
    val xRemainder: Double
    var signedYear: Double
    val x0: Double

    if (timeInterval < kECJulianGregorianSwitchoverTimeInterval) {
        // Julian calendar
        val x1F = 730793.0 + timeInterval / (24 * 3600)
        val x1 = floor(x1F)
        xRemainder = x1F - x1
        signedYear = floor((4 * x1 + 3) / kECDaysInJulianCycle)
        x0 = x1 - floor(kECDaysInJulianCycle * signedYear / 4.0)
    } else {
        // Gregorian calendar
        val x2F = 730791.0 + timeInterval / (24 * 3600)
        val x2 = floor(x2F)
        xRemainder = x2F - x2

        val century = floor((4 * x2 + 3) / kECDaysInGregorianCycle)
        val x1 = x2 - floor(kECDaysInGregorianCycle * century / 4.0)
        val yearWithinCentury = floor((100 * x1 + 99) / kECDaysInNonLeapCentury)
        signedYear = (100 * century) + yearWithinCentury
        x0 = x1 - floor(kECDaysInNonLeapCentury * yearWithinCentury / 100.0)
    }

    val monthI = floor((5 * x0 + 461) / 153)
    val month: Double
    if (monthI > 12) {
        month = monthI - 12
        signedYear++
    } else {
        month = monthI
    }

    val era: Int
    val year: Int
    if (signedYear <= 0) {
        era = 0
        year = (1 - signedYear).toInt()
    } else {
        era = 1
        year = signedYear.toInt()
    }

    val dayF = x0 - floor((153 * monthI - 457) / 5.0) + 1
    val day = jsRound(dayF)

    val hoursF = xRemainder * 24
    val hoursI = floor(hoursF)
    val minutesF = (hoursF - hoursI) * 60
    val minutesI = floor(minutesF)
    val seconds = (minutesF - minutesI) * 60

    return EsDateComponents(era, year, month.toInt(), day, hoursI.toInt(), minutesI.toInt(), seconds)
}

/**
 * Convert calendar components (UTC) to an ESTimeInterval.
 * Ported from ESCalendar_timeIntervalFromUTCComponents().
 */
fun timeIntervalFromUTCComponents(
    era: Int, year: Int, month: Int, day: Int,
    hour: Int, minute: Int, seconds: Double,
): Double {
    var signedYear = (if (era == 0) 1 - year else year).toDouble()
    val monthI: Double
    if (month < 3) {
        monthI = (month + 12).toDouble()
        signedYear--
    } else {
        monthI = month.toDouble()
    }

    var j: Double
    if (era == 0 ||
        year < 1582 ||
        (year == 1582 && (month < 10 || (month == 10 && day < 15)))
    ) {
        // Julian
        j = 1721116.5 + floor(1461 * signedYear / 4.0)
    } else {
        // Gregorian
        val c = floor(signedYear / 100.0)
        val x = signedYear - 100 * c
        j = 1721118.5 + floor(146097 * c / 4.0) + floor(36525 * x / 100.0)
    }
    j += floor((153 * monthI - 457) / 5.0) + day

    return (j - kECJulianDayOf1990Epoch) * 24 * 3600 + kEC1990Epoch +
        hour * 3600 + minute * 60 + seconds
}

/**
 * Number of days in the given month (February resolved via the calendar system).
 * Ported from ESCalendar_daysInMonth().
 */
fun daysInMonth(era: Int, year: Int, month: Int): Int = when (month) {
    1 -> 31
    2 -> {
        val firstOfFeb = timeIntervalFromUTCComponents(era, year, 2, 1, 0, 0, 0.0)
        val firstOfMar = timeIntervalFromUTCComponents(era, year, 3, 1, 0, 0, 0.0)
        jsRound((firstOfMar - firstOfFeb) / (24 * 3600))
    }
    3 -> 31
    4 -> 30
    5 -> 31
    6 -> 30
    7 -> 31
    8 -> 31
    9 -> 30
    10 -> 31
    11 -> 30
    12 -> 31
    else -> 0
}

/** Decompose an ESTimeInterval into local components using a tz offset (seconds, east-positive). */
fun localComponentsFromTimeInterval(timeInterval: Double, tzOffsetSeconds: Double): EsDateComponents =
    utcComponentsFromTimeInterval(timeInterval + tzOffsetSeconds)

/** Convert local components back to an ESTimeInterval. */
fun timeIntervalFromLocalComponents(
    tzOffsetSeconds: Double,
    era: Int, year: Int, month: Int, day: Int,
    hour: Int, minute: Int, seconds: Double,
): Double = timeIntervalFromUTCComponents(era, year, month, day, hour, minute, seconds) - tzOffsetSeconds

/**
 * Weekday (0=Sunday … 6=Saturday) via epoch arithmetic — independent of which calendar
 * system is in effect. Ported from ESCalendar_localWeekdayFromTimeInterval().
 */
fun weekdayFromTimeInterval(dateInterval: Double, tzOffsetSeconds: Double): Int {
    val localNow = dateInterval + tzOffsetSeconds
    val localNowDays = localNow / (24 * 3600)
    // non-negative modulo: (x % m + m) % m
    val weekday = ((localNowDays + 1) % 7 + 7) % 7
    return floor(weekday).toInt()
}
