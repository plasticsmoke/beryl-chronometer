package com.plasticsmoke.beryl.astro

import com.plasticsmoke.beryl.expr.createDefaultEnvironment
import com.plasticsmoke.beryl.expr.evaluateExpression
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Interaction stage 2: the ECWatchTime skew/warp port and the real time-action leaves.
 * Real time is simulated by a mutable clock so warp behavior is deterministic.
 */
class WatchTimeTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private var realMs = Instant.parse("2026-07-04T17:10:30Z").toEpochMilli()

    private fun newTime() = WatchTimeState(systemNow = { realMs }, zone = zone)

    private fun local(time: WatchTimeState): ZonedDateTime =
        Instant.ofEpochMilli(time.displayedNow()).atZone(zone)

    @Test fun stemStopsAndRestartsTheWatch() {
        val t = newTime()
        assertFalse(t.manualSet)
        assertTrue(t.isCorrect)
        assertEquals(realMs, t.displayedNow())

        t.stop()   // stem out
        assertTrue(t.manualSet)
        val frozen = t.displayedNow()
        realMs += 90_000
        assertEquals(frozen, t.displayedNow(), "stopped watch must not advance")
        assertFalse(t.isCorrect, "a stopped watch is not correct")

        t.start()  // stem in — resumes from the frozen time, now 90 s slow
        assertFalse(t.manualSet)
        assertEquals(frozen, t.displayedNow())
        realMs += 10_000
        assertEquals(frozen + 10_000, t.displayedNow(), "running again at warp 1")

        t.reset()
        assertTrue(t.isCorrect)
        assertEquals(realMs, t.displayedNow())
    }

    @Test fun advancesAreCalendarAware() {
        val t = newTime()
        t.stop()
        val before = local(t)

        t.advanceDays(1)
        assertEquals(before.dayOfMonth + 1, local(t).dayOfMonth)
        assertEquals(before.hour, local(t).hour, "same local clock time next day")

        // Jul 31 + 1 month clamps to Aug 31 -> fine; test the Jan 31 clamp instead.
        val jan31 = ZonedDateTime.of(2026, 1, 31, 10, 8, 0, 0, zone)
        t.setDisplayed(jan31.toInstant().toEpochMilli())
        t.advanceMonths(1)
        assertEquals(2, local(t).monthValue)
        assertEquals(28, local(t).dayOfMonth, "Jan 31 + 1 month clamps to Feb 28")

        // Feb 29 + 1 year clamps to Feb 28 (2028 is a leap year).
        val feb29 = ZonedDateTime.of(2028, 2, 29, 10, 8, 0, 0, zone)
        t.setDisplayed(feb29.toInstant().toEpochMilli())
        t.advanceYears(1)
        assertEquals(28, local(t).dayOfMonth)
        assertEquals(2029, local(t).year)
    }

    @Test fun quarterHourRounding() {
        val t = newTime()
        t.stop()
        t.setDisplayed(ZonedDateTime.of(2026, 7, 4, 10, 8, 30, 0, zone).toInstant().toEpochMilli())
        t.advanceToQuarterHour()
        assertEquals(15, local(t).minute)
        assertEquals(0, local(t).second)
        t.advanceToQuarterHour()
        assertEquals(30, local(t).minute)
        t.retreatToQuarterHour()
        assertEquals(15, local(t).minute, "retreat from an exact boundary lands on the previous one")
    }

    @Test fun reverseRunsTimeBackward() {
        val t = newTime()
        t.setRunningBackward(true)
        assertTrue(t.runningBackward)
        val d0 = t.displayedNow()
        realMs += 10_000
        assertEquals(d0 - 10_000, t.displayedNow(), "warp -1 runs backward")

        // Direction survives a stop/start cycle (iOS warpBeforeFreeze).
        t.stop()
        assertTrue(t.runningBackward)
        t.start()
        realMs += 5_000
        assertTrue(t.runningBackward)
        t.setRunningBackward(false)
        assertFalse(t.runningBackward)
    }

    @Test fun timeLeavesDriveTheStemStateMachine() {
        val t = newTime()
        val env = createDefaultEnvironment()
        registerWatchFunctions(env, -7 * 3600.0, 0.66, -2.14) { t.displayedNow() }
        registerTimeFunctions(env, t)

        // Kyoto's stem action, verbatim.
        val stemAction = "manualSet() ? (tick(), stemIn()) : (tock(), stemOut())"
        evaluateExpression(stemAction, env)
        assertTrue(t.manualSet, "first stem tap pulls the stem out (stops)")
        assertEquals(1.0, evaluateExpression("manualSet()", env))

        evaluateExpression("advanceSeconds(86400)", env)
        val dayAhead = t.displayedNow()
        assertEquals(0.0, evaluateExpression("timeIsCorrect()", env))

        evaluateExpression(stemAction, env)
        assertFalse(t.manualSet, "second tap pushes the stem in (starts)")
        assertEquals(dayAhead, t.displayedNow(), "still a day ahead while running")

        evaluateExpression("reset()", env)
        assertEquals(1.0, evaluateExpression("timeIsCorrect()", env))
        assertEquals(realMs, t.displayedNow())

        // Kyoto F/R.
        evaluateExpression("inReverse() ? goForward() : goBackward()", env)
        assertTrue(t.runningBackward)
        evaluateExpression("inReverse() ? goForward() : goBackward()", env)
        assertFalse(t.runningBackward)
    }

    @Test fun astroJumpsLandOnEvents() {
        val t = newTime()
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) { t.displayedNow() }
        registerTimeFunctions(env, t)

        t.stop()
        evaluateExpression("advanceToSunriseForDay()", env)
        val sunrise = local(t)
        assertTrue(sunrise.hour in 4..7, "SF July sunrise ~05:50 PDT, got $sunrise")

        // Already showing today's sunrise -> jumps to the NEXT day's.
        val day0 = sunrise.dayOfYear
        evaluateExpression("advanceToSunriseForDay()", env)
        assertEquals(day0 + 1, local(t).dayOfYear, "second tap advances to tomorrow's sunrise")

        evaluateExpression("advanceToClosestFullMoon()", env)
        val fm1 = t.displayedNow()
        evaluateExpression("advanceToClosestFullMoon()", env)
        val fm2 = t.displayedNow()
        val gapDays = (fm2 - fm1) / 86400000.0
        assertTrue(gapDays in 28.0..31.0, "second tap = next full moon, one synodic month on (got $gapDays d)")
    }
}
