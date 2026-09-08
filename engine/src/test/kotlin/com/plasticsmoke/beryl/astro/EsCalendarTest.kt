package com.plasticsmoke.beryl.astro

import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Validates the hybrid calendar port. For the Gregorian range (on/after 1582-10-15) the
 * proleptic Gregorian calendar in java.time is an independent oracle; fixed points and the
 * Julian/Gregorian switchover are checked against known history.
 */
class EsCalendarTest {

    @Test fun appleEpochZeroIsMonday() {
        val c = utcComponentsFromTimeInterval(0.0)  // 2001-01-01 00:00:00 UTC
        assertEquals(1, c.era)
        assertEquals(2001, c.year)
        assertEquals(1, c.month)
        assertEquals(1, c.day)
        assertEquals(0, c.hour)
        assertEquals(0, c.minute)
        assertEquals(0.0, c.seconds, 1e-9)
        assertEquals(1, weekdayFromTimeInterval(0.0, 0.0))  // Monday
    }

    @Test fun unixEpochIsThursday() {
        val interval = -978307200.0  // 1970-01-01 00:00:00 UTC
        val c = utcComponentsFromTimeInterval(interval)
        assertEquals(1970, c.year)
        assertEquals(1, c.month)
        assertEquals(1, c.day)
        assertEquals(4, weekdayFromTimeInterval(interval, 0.0))  // Thursday
    }

    @Test fun matchesJavaTimeAcrossGregorianRange() {
        val samples = listOf(
            "1583-01-01T00:00:00Z",
            "1999-07-04T06:15:00Z",
            "2000-02-29T23:59:59Z",  // leap day
            "2024-12-31T12:00:00Z",
            "2026-06-11T14:30:45Z",
            "2100-03-01T00:00:00Z",  // century non-leap boundary
        )
        for (iso in samples) {
            val inst = Instant.parse(iso)
            val interval = dateIntervalFromEpochMillis(inst.toEpochMilli())
            val c = utcComponentsFromTimeInterval(interval)
            val z = inst.atZone(ZoneOffset.UTC)
            // Date-level fields are exact and are what the watch actually consumes.
            // (Time-of-day fields carry the original's sub-day float precision — see
            // utcComponentsFromTimeInterval's doc — so they're not asserted here.)
            assertEquals(z.year, c.year, "year $iso")
            assertEquals(z.monthValue, c.month, "month $iso")
            assertEquals(z.dayOfMonth, c.day, "day $iso")
            // java DayOfWeek: MON=1..SUN=7;  es: SUN=0..SAT=6  → value % 7
            assertEquals(z.dayOfWeek.value % 7, weekdayFromTimeInterval(interval, 0.0), "weekday $iso")
        }
    }

    @Test fun roundTripUtcComponents() {
        val interval = dateIntervalFromEpochMillis(Instant.parse("2026-06-11T14:30:45Z").toEpochMilli())
        val c = utcComponentsFromTimeInterval(interval)
        val back = timeIntervalFromUTCComponents(c.era, c.year, c.month, c.day, c.hour, c.minute, c.seconds)
        assertEquals(interval, back, 1e-3)
    }

    @Test fun julianGregorianSwitchover() {
        // The switchover constant is 1582-10-15 00:00 UTC (first Gregorian day).
        val greg = utcComponentsFromTimeInterval(kECJulianGregorianSwitchoverTimeInterval)
        assertEquals(1582, greg.year)
        assertEquals(10, greg.month)
        assertEquals(15, greg.day)
        // One day earlier in the hybrid calendar is Julian 1582-10-04 (Oct 5–14 don't exist).
        val before = utcComponentsFromTimeInterval(kECJulianGregorianSwitchoverTimeInterval - 24 * 3600)
        assertEquals(1582, before.year)
        assertEquals(10, before.month)
        assertEquals(4, before.day)
    }

    @Test fun daysInMonthLeapRules() {
        assertEquals(31, daysInMonth(1, 2026, 1))
        assertEquals(28, daysInMonth(1, 2025, 2))
        assertEquals(29, daysInMonth(1, 2024, 2))   // leap
        assertEquals(28, daysInMonth(1, 1900, 2))   // Gregorian non-leap century
        assertEquals(29, daysInMonth(1, 2000, 2))   // /400 leap century
        assertEquals(30, daysInMonth(1, 2026, 4))
        assertEquals(31, daysInMonth(1, 2026, 12))
    }

    @Test fun localComponentsOffsetCrossesMidnight() {
        // 2026-06-11T02:00:00Z at -08:00 → 2026-06-10 18:00 local: the offset moves the
        // calendar DATE back a day (date fields are the exact, consumed outputs).
        val interval = dateIntervalFromEpochMillis(Instant.parse("2026-06-11T02:00:00Z").toEpochMilli())
        val c = localComponentsFromTimeInterval(interval, -28800.0)
        assertEquals(2026, c.year)
        assertEquals(6, c.month)
        assertEquals(10, c.day)
    }
}
