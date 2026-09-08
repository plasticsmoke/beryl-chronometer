package com.plasticsmoke.beryl.astro

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Per-watch displayed-time state — a faithful port of iOS ECWatchTime's skew/warp model
 * (Classes/ECWatchTime.m). In a plot of watch time (y) vs real time (x), the watch shows
 * y = warp·x + b:
 *
 *  - warp: the rate multiplier. 1 = running normally, -1 = running backward (Kyoto's F/R
 *    switch), 0 = STOPPED — pulling the stem out stops the watch (hacking), and
 *    `manualSet()` is literally `warp == 0`.
 *  - [ourTimeAtZeroMs]: what the watch would read at real time zero (b). When stopped, it
 *    holds the absolute frozen time instead (iOS folds the skew out on stop / back in on
 *    start, so stopped watches don't drift when the reference changes).
 *
 * One instance per watch face, shared by its front/night/back envs — iOS has a single VM
 * per watch. All methods are expected to run on one thread (the app's render thread).
 *
 * Times are epoch MILLISECONDS (the engine's TimeSource unit).
 */
class WatchTimeState(
    private val systemNow: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    var warp = 1.0
        private set
    var warpBeforeFreeze = 1.0
        private set
    var ourTimeAtZeroMs = 0.0
        private set

    /** Called after every mutation — the app persists the state and rebakes caches. */
    var onChange: (() -> Unit)? = null

    /** The time the watch face displays right now. */
    fun displayedNow(realMs: Long = systemNow()): Long =
        if (warp != 0.0) (warp * realMs + ourTimeAtZeroMs).toLong()
        else ourTimeAtZeroMs.toLong()

    /** iOS ECGLWatch manualSet — the stem is out iff the watch is stopped. */
    val manualSet: Boolean get() = warp == 0.0

    val isCorrect: Boolean get() = warp == 1.0 && ourTimeAtZeroMs == 0.0

    /** iOS lastMotionWasInReverse — stays true while stopped if it was reversed before. */
    val runningBackward: Boolean get() = warp < 0 || (warp == 0.0 && warpBeforeFreeze < 0)

    /** Restore a persisted state (no onChange — used at face load). */
    fun restore(warp: Double, warpBeforeFreeze: Double, ourTimeAtZeroMs: Double) {
        this.warp = warp
        this.warpBeforeFreeze = if (warpBeforeFreeze == 0.0) 1.0 else warpBeforeFreeze
        this.ourTimeAtZeroMs = ourTimeAtZeroMs
    }

    private fun changed() {
        onChange?.invoke()
    }

    /** stemOut: freeze at the current displayed time (no-op if already stopped). */
    fun stop() {
        if (warp != 0.0) {
            ourTimeAtZeroMs = displayedNow().toDouble()
            warpBeforeFreeze = warp
            warp = 0.0
            changed()
        }
    }

    /** stemIn: resume running (in the pre-freeze direction) from the frozen time. */
    fun start() {
        if (warp == 0.0) {
            if (warpBeforeFreeze == 0.0) warpBeforeFreeze = 1.0
            ourTimeAtZeroMs -= systemNow() * warpBeforeFreeze
            warp = warpBeforeFreeze
            changed()
        }
    }

    /** iOS setRunningBackward (the F/R switch): reverse only when the direction differs. */
    fun setRunningBackward(backward: Boolean) {
        if (backward != runningBackward) {
            warp = -warp
            warpBeforeFreeze = -warpBeforeFreeze
            changed()
        }
    }

    /** reset(): back to the true local time, running forward (iOS resetToLocal). */
    fun reset() {
        ourTimeAtZeroMs = 0.0
        warp = 1.0
        warpBeforeFreeze = 1.0
        changed()
    }

    fun advanceSeconds(seconds: Double) {
        ourTimeAtZeroMs += seconds * 1000.0
        changed()
    }

    /** Jump a STOPPED watch to an absolute displayed time (iOS setMainTimeToFrozenDateInterval). */
    fun setDisplayed(displayedMs: Long) {
        if (warp == 0.0) {
            ourTimeAtZeroMs = displayedMs.toDouble()
            changed()
        } else {
            advanceSeconds((displayedMs - displayedNow()) / 1000.0)
        }
    }

    /**
     * iOS representationOfDeltaOffsetUsingEnv: the watch-vs-true-time delta for the "Off by"
     * status banner, as "±HH:MM:SS" (with "Nd " / "Ny Nd " prefixes when the offset spans
     * days/years). The split uses CALENDAR components in the local zone, like iOS's
     * ESCalendar_localDateComponentsFromDeltaTimeInterval.
     */
    fun deltaOffsetText(realMs: Long = systemNow()): String {
        val delta = if (warp != 0.0) (warp - 1.0) * realMs + ourTimeAtZeroMs
        else ourTimeAtZeroMs - realMs
        val now = displayedNow(realMs)
        val sign: String
        val t1: Long
        val t2: Long
        if (delta > 0) {
            sign = "+"; t1 = now - delta.toLong(); t2 = now
        } else {
            sign = "-"; t1 = now; t2 = now - delta.toLong()
        }
        var a = Instant.ofEpochMilli(t1).atZone(zone)
        val b = Instant.ofEpochMilli(t2).atZone(zone)
        val years = java.time.temporal.ChronoUnit.YEARS.between(a, b)
        a = a.plusYears(years)
        val days = java.time.temporal.ChronoUnit.DAYS.between(a, b)
        a = a.plusDays(days)
        val secs = java.time.temporal.ChronoUnit.SECONDS.between(a, b)
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return when {
            years != 0L -> "%s%dy %dd %02d:%02d:%02d".format(sign, years, days, h, m, s)
            days != 0L -> "%s%dd %02d:%02d:%02d".format(sign, days, h, m, s)
            else -> "%s%02d:%02d:%02d".format(sign, h, m, s)
        }
    }

    // ---- drag anchoring (iOS advanceBySeconds:fromTime: family) ----
    // A hand drag repeatedly re-derives the displayed time from the time AT DRAG START plus a
    // function of the cumulative angle, so wiggling back and forth never accumulates error.

    /** displayed = [startMs] + [seconds] (iOS advanceBySeconds:fromTime:). */
    fun setDisplayedFrom(startMs: Long, seconds: Double) =
        setDisplayed(startMs + Math.round(seconds * 1000.0))

    /** displayed = same local clock time [n] days after [startMs] — DST-aware. */
    fun advanceDaysFrom(startMs: Long, n: Int) = setDisplayed(calendarTarget(startMs) { it.plusDays(n.toLong()) })

    /** displayed = same day-of-month [n] months after [startMs], clamped to the month end. */
    fun advanceMonthsFrom(startMs: Long, n: Int) = setDisplayed(calendarTarget(startMs) { it.plusMonths(n.toLong()) })

    /** displayed = same month/day [n] years after [startMs]; Feb 29 clamps to Feb 28. */
    fun advanceYearsFrom(startMs: Long, n: Int) = setDisplayed(calendarTarget(startMs) { it.plusYears(n.toLong()) })

    private inline fun calendarTarget(startMs: Long, f: (ZonedDateTime) -> ZonedDateTime): Long =
        f(Instant.ofEpochMilli(startMs).atZone(zone)).toInstant().toEpochMilli()

    private inline fun calendarAdvance(inZone: ZoneId, f: (ZonedDateTime) -> ZonedDateTime) {
        val nowMs = displayedNow()
        val target = f(Instant.ofEpochMilli(nowMs).atZone(inZone)).toInstant().toEpochMilli()
        advanceSeconds((target - nowMs) / 1000.0)
    }

    /** Same local clock time n days later — DST-aware (iOS advanceByDays…usingEnv). The
     *  calendar arithmetic runs in [inZone]: Terra's advanceDayN steps the SHARED time by
     *  one day of the slot CITY's calendar (iOS passes environments[slot] to ECWatchTime). */
    fun advanceDays(n: Int, inZone: ZoneId = zone) = calendarAdvance(inZone) { it.plusDays(n.toLong()) }

    /** Same day-of-month next month, clamped to the month end (iOS advanceByMonths). */
    fun advanceMonths(n: Int, inZone: ZoneId = zone) = calendarAdvance(inZone) { it.plusMonths(n.toLong()) }

    /** Same month/day next year; Feb 29 clamps to Feb 28 (iOS advanceByYears). */
    fun advanceYears(n: Int, inZone: ZoneId = zone) = calendarAdvance(inZone) { it.plusYears(n.toLong()) }

    /** Advance to the next :00/:15/:30/:45 boundary (iOS advanceToQuarterHourUsingEnv). */
    fun advanceToQuarterHour() {
        val nowMs = displayedNow()
        val zdt = Instant.ofEpochMilli(nowMs).atZone(zone)
        val secondsSinceHour = zdt.minute * 60 + zdt.second + zdt.nano / 1e9
        val next = when {
            secondsSinceHour < 15 * 60 -> 15 * 60.0
            secondsSinceHour < 30 * 60 -> 30 * 60.0
            secondsSinceHour < 45 * 60 -> 45 * 60.0
            else -> 60 * 60.0
        }
        advanceSeconds(next - secondsSinceHour)
    }

    /** Retreat to the previous quarter boundary, with iOS's 0.01 s anti-stall fudge. */
    fun retreatToQuarterHour() {
        val nowMs = displayedNow()
        val zdt = Instant.ofEpochMilli(nowMs).atZone(zone)
        val fudge = 0.01
        var secondsSinceHour = zdt.minute * 60 + zdt.second + zdt.nano / 1e9 - fudge
        if (secondsSinceHour < 0) secondsSinceHour += 3600
        val prev = when {
            secondsSinceHour < 15 * 60 -> 0.0
            secondsSinceHour < 30 * 60 -> 15 * 60.0
            secondsSinceHour < 45 * 60 -> 30 * 60.0
            else -> 45 * 60.0
        }
        advanceSeconds(prev - fudge - secondsSinceHour)
    }
}
