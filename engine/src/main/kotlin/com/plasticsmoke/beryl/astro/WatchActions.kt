package com.plasticsmoke.beryl.astro

import com.plasticsmoke.beryl.expr.Environment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Side-effect hooks for button `action` leaves that reach outside the expression env.
 * The engine registers no-op defaults; the app re-registers with real implementations
 * (SharedPreferences persistence, and — stage 2 — the per-watch time base).
 */
class ActionHooks(
    /** saveBody(v) — persist the planet-selector body for this face (Miami). */
    var saveBody: (Double) -> Unit = {},
    /** storePersistentValue(slot, v) — iOS generic persisted slot (Terra's topRingSlot). */
    var storePersistentValue: (Int, Double) -> Unit = { _, _ -> },
)

/**
 * Register every side-effect leaf used by button `action` expressions across the built-in
 * faces, so evaluating any action never throws Undefined function.
 *
 * Stage 1 of the interaction layer: only the persistence hooks do real work. The time-base
 * family (reset, the advance leaves, the stem) and the alarm/stopwatch subsystems are inert
 * no-ops here — stage 2/3 re-register them over these defaults.
 */
fun registerActionFunctions(env: Environment, hooks: ActionHooks = ActionHooks()) {
    val f = env.functions
    fun noop(vararg names: String) {
        for (n in names) f[n] = { 0.0 }
    }

    // Stem / set-mode state machine (stage 2: toggles manualSet + freezes the time base).
    noop("stemIn", "stemOut", "tick", "tock", "goForward", "goBackward")

    // Time travel (stage 2: skew the per-watch displayed-time offset).
    noop(
        "reset",
        "advanceSeconds", "advanceHour", "advanceDay", "advanceDays", "advanceDayN",
        "advanceMonth", "advanceMonthN", "advanceYear", "advanceYears",
        "advanceToQuarterHour",
        "advanceToSunriseForDay", "advanceToSunsetForDay",
        "advanceToMoonriseForDay", "advanceToMoonsetForDay",
        "advanceToClosestNewMoon", "advanceToClosestFullMoon",
        "advanceToClosestFirstQuarter", "advanceToClosestThirdQuarter",
        "advanceToNextMoonPhase",
        "advanceToRiseOfPlanetForDay", "advanceToSetOfPlanetForDay",
        "advanceToTransitOfPlanetForDay",
    )

    // Alarm / interval timer (stage 3 — Istanbul/Thebes).
    noop(
        "alarmStemIn", "alarmStemOut", "alarmReset", "enableAlarm", "disableAlarm",
        "toggleAlarmAMPM", "toggleIntervalTimer",
        "advanceAlarmHour", "advanceAlarmMinute",
        "advanceIntervalMinute", "advanceIntervalSecond",
    )

    // Stopwatch / rattrapante (stage 3 — Olympia/Istanbul/Thebes).
    noop("stopwatchStartStop", "stopwatchReset", "stopwatchLapReset", "stopwatchRattrapante")

    // Persistence.
    f["saveBody"] = { a -> hooks.saveBody(a[0]); 0.0 }
    f["storePersistentValue"] = { a -> hooks.storePersistentValue(a[0].toInt(), a[1]); 0.0 }
}

/**
 * Interaction stage 2: the real time-base leaves, overriding the no-ops above when the
 * caller owns a [WatchTimeState] (the on-device app; render tests stay on the static stubs).
 * Ports iOS ECVirtualMachineOps + ECGLWatch:
 *
 *  - the stem stops/starts the watch (manualSet == stopped), reset() returns to true local
 *    time, the F/R switch reverses the run direction;
 *  - the advance family moves the displayed time (negated while running backward), with
 *    calendar-aware day/month/year steps;
 *  - the astronomical advances jump to today's event, or the next one when the watch is
 *    already showing it (iOS's <0.5 s rule; next = the same event a day ahead/behind).
 *
 * The advanceDayN/advanceMonthN registered here use the DEVICE zone; Terra re-registers the
 * real per-slot versions over them ([registerTerraTimeFunctions] — the calendar step runs in
 * the slot city's timezone), matching iOS's env-parameterized advance ops.
 *
 * Call AFTER registerWatchFunctions/registerActionFunctions so the real leaves win.
 */
fun registerTimeFunctions(env: Environment, time: WatchTimeState) {
    val f = env.functions

    f["manualSet"] = { if (time.manualSet) 1.0 else 0.0 }
    f["timeIsCorrect"] = { if (time.isCorrect) 1.0 else 0.0 }
    f["inReverse"] = { if (time.runningBackward) 1.0 else 0.0 }

    f["stemIn"] = { time.start(); 0.0 }
    f["stemOut"] = { time.stop(); 0.0 }
    f["goForward"] = { time.setRunningBackward(false); 0.0 }
    f["goBackward"] = { time.setRunningBackward(true); 0.0 }
    f["reset"] = { time.reset(); 0.0 }

    // Signed by run direction, like every iOS advance op.
    fun dir(n: Double) = if (time.runningBackward) -n else n
    f["advanceSeconds"] = { a -> time.advanceSeconds(dir(a[0])); 0.0 }
    f["advanceHour"] = { time.advanceSeconds(dir(3600.0)); 0.0 }
    f["advanceDay"] = { time.advanceDays(dir(1.0).toInt()); 0.0 }
    f["advanceDays"] = { a -> time.advanceDays(dir(a[0]).roundToInt()); 0.0 }
    f["advanceDayN"] = { time.advanceDays(dir(1.0).toInt()); 0.0 }
    f["advanceMonth"] = { time.advanceMonths(dir(1.0).toInt()); 0.0 }
    f["advanceMonthN"] = { time.advanceMonths(dir(1.0).toInt()); 0.0 }
    f["advanceYear"] = { time.advanceYears(dir(1.0).toInt()); 0.0 }
    f["advanceYears"] = { a -> time.advanceYears(dir(a[0]).roundToInt()); 0.0 }
    f["advanceToQuarterHour"] = {
        if (time.runningBackward) time.retreatToQuarterHour() else time.advanceToQuarterHour()
        0.0
    }

    // ---- astronomical jumps (stem-out buttons: the watch is stopped when these fire) ----
    fun displayedInterval() = dateIntervalFromEpochMillis(time.displayedNow())
    fun jumpTo(target: Double) {
        if (!target.isNaN()) time.setDisplayed(((target + APPLE_EPOCH_OFFSET) * 1000.0).toLong())
    }
    fun lat() = env.observerLatRad ?: 0.0
    fun lon() = env.observerLonRad ?: 0.0
    fun tz() = env.tzOffsetSec ?: 0.0

    // Today's rise/set/transit of the planet; if already showing it, the next day's
    // (previous day's when running backward) — iOS advanceToRiseOfPlanetForDay.
    fun riseSetTransit(planet: Int, kind: Int) {
        val now = displayedInterval()
        fun eventFor(t: Double) = when (kind) {
            0 -> planetRiseSetForDay(planet, true, t, lat(), lon(), tz())
            1 -> planetRiseSetForDay(planet, false, t, lat(), lon(), tz())
            else -> planetTransitForDay(planet, t, lat(), lon(), tz())
        }
        val showing = eventFor(now)
        val target =
            if (showing.isNaN() || abs(now - showing) < 0.5) {
                eventFor(now + if (time.runningBackward) -86400.0 else 86400.0)
            } else showing
        jumpTo(target)
    }
    f["advanceToSunriseForDay"] = { riseSetTransit(PLANET_SUN, 0); 0.0 }
    f["advanceToSunsetForDay"] = { riseSetTransit(PLANET_SUN, 1); 0.0 }
    f["advanceToMoonriseForDay"] = { riseSetTransit(PLANET_MOON, 0); 0.0 }
    f["advanceToMoonsetForDay"] = { riseSetTransit(PLANET_MOON, 1); 0.0 }
    f["advanceToRiseOfPlanetForDay"] = { a -> riseSetTransit(a[0].toInt(), 0); 0.0 }
    f["advanceToSetOfPlanetForDay"] = { a -> riseSetTransit(a[0].toInt(), 1); 0.0 }
    f["advanceToTransitOfPlanetForDay"] = { a -> riseSetTransit(a[0].toInt(), 2); 0.0 }

    // Moon quarter phases: closest event of that phase; if already showing it, the next
    // one a synodic month ahead (iOS advanceToClosestNewMoon's <2 s rule).
    val synodic = 29.530588 * 86400.0
    fun closestPhase(angle: Double) {
        val now = displayedInterval()
        val closest = closestQuarterPhaseTime(angle, now)
        jumpTo(if (abs(now - closest) < 2.0) closestQuarterPhaseTime(angle, closest + synodic) else closest)
    }
    f["advanceToClosestNewMoon"] = { closestPhase(0.0); 0.0 }
    f["advanceToClosestFirstQuarter"] = { closestPhase(PI / 2); 0.0 }
    f["advanceToClosestFullMoon"] = { closestPhase(PI); 0.0 }
    f["advanceToClosestThirdQuarter"] = { closestPhase(3 * PI / 2); 0.0 }
    f["advanceToNextMoonPhase"] = {
        val now = displayedInterval()
        val next = listOf(0.0, PI / 2, PI, 3 * PI / 2)
            .map { a ->
                val c = closestQuarterPhaseTime(a, now)
                if (c > now + 2.0) c else closestQuarterPhaseTime(a, c + synodic)
            }
            .filter { it > now + 2.0 }
            .minOrNull()
        if (next != null) jumpTo(next)
        0.0
    }
}
