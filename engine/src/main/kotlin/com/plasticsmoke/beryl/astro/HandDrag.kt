package com.plasticsmoke.beryl.astro

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor

/**
 * Hand dragging — set the watch by turning its hands while the stem is out. Ported from iOS
 * ECGLPart dragStartAtPoint/dragFrom/moveTimerZeroForDeltaAngle.
 *
 * A session accumulates the CUMULATIVE clockwise angle since the drag started (wrap-aware, so
 * going around twice remembers two turns), and re-derives the displayed time from the time at
 * drag start — each hand `kind` maps one revolution to its natural span (minute hand: 1 hour,
 * hour12: 12 hours, day hand: 31 days calendar-aware, …).
 *
 * The alarm/interval kinds (stage 3) move the ALARM offset instead of the main time (iOS
 * moveAlarmTimeForDeltaAngle): interval hands set the countdown, target hands the alarm's
 * time of day, snapped to their natural unit.
 *
 * Terra's [WORLDTIME_RING_KIND] (iOS moveWorldtimeRingForDeltaAngle) executes the part's own
 * ACTION once per 15° notch of cumulative drag instead of touching any time: the shift count
 * is normalized to 0..23 — the action only rotates the ring one way, so a counterclockwise
 * notch arrives as 23 forward acts, the same slot mod 24. The act callback is supplied by the
 * app ([ringAct]), which runs the expression in the face env. Still out of scope: the
 * lat/long kinds (fixed observer).
 */

/** Terra's ring-mover kind: dragging acts per 15° notch (grabbable stem-out, like main-time). */
const val WORLDTIME_RING_KIND = "worldtimeRingKind"

private const val TWO_PI = 2 * PI
private const val SYNODIC_DAYS = 29.5306   // iOS moveTimerZeroForDeltaAngle's constant

/** True when dragging a hand of this [kind] sets the MAIN time (it's grabbable stem-out). */
fun kindDragsMainTime(kind: String?): Boolean = kind != null && kind in MAIN_TIME_KINDS

private val MAIN_TIME_KINDS = setOf(
    "secondKind", "minuteKind", "hour12Kind", "hour24Kind", "reverseHour24Kind",
    "hour24MoonKind", "dayKind", "moonDayKind", "weekDayKind", "monthKind",
    "year1Kind", "year10Kind", "year100Kind", "year1000Kind", "greatYearKind",
    "sunRAKind", "reverseSunRAKind", "moonRAKind", "reverseMoonRAKind",
    "mercuryYearKind", "venusYearKind", "earthYearKind", "marsYearKind",
    "jupiterYearKind", "saturnYearKind", "nodalKind",
)

/** True when dragging a hand of this [kind] sets the ALARM (grabbable alarm-stem-out). */
fun kindDragsAlarm(kind: String?): Boolean = kind != null && kind in ALARM_KINDS

private val ALARM_KINDS = setOf(
    "intervalSecondKind", "intervalMinuteKind", "intervalHour12Kind", "intervalHour24Kind",
    "targetMinuteKind", "targetHour12Kind", "targetHour12KindB", "targetHour24Kind",
)

private fun kindIsInterval(kind: String) = kind.startsWith("interval")

/**
 * One hand-drag gesture. Coordinates are face units, Y-up, origin at the watch center (the
 * space the part x/y attributes live in); [anchorX]/[anchorY] is the hand's rotation anchor.
 * Construct on touch-down (on the point's first position), then feed every move to [moveTo].
 */
class HandDragSession(
    private val time: WatchTimeState,
    private val kind: String,
    private val anchorX: Double,
    private val anchorY: Double,
    startX: Double,
    startY: Double,
    private val alarm: AlarmState? = null,
    /** [WORLDTIME_RING_KIND] only: run the part's action [times] times (one per new notch). */
    private val ringAct: ((times: Int) -> Unit)? = null,
) {
    /** iOS worldtimeRingShifts: notches already simulated this drag. */
    private var ringShifts = 0
    private val startDisplayedMs = time.displayedNow()
    // Alarm drags re-derive from the offset AT GRAB (iOS dragStartAtPoint: currentOffset for
    // interval hands, specifiedOffset for target hands).
    private val startAlarmOffset = when {
        alarm == null -> 0.0
        kindIsInterval(kind) -> alarm.effectiveOffset
        else -> alarm.specifiedOffset
    }
    private var lastPointAngle = pointAngle(startX, startY)
    private var cumulative = 0.0

    /** Clock angle (clockwise-positive) of the touch about the anchor — only deltas matter. */
    private fun pointAngle(px: Double, py: Double): Double =
        -atan2(py - anchorY, px - anchorX)

    /** Track the finger: accumulate the wrapped delta and re-derive the displayed time. */
    fun moveTo(px: Double, py: Double) {
        val a = pointAngle(px, py)
        var delta = a - lastPointAngle
        if (delta < -PI) delta += TWO_PI else if (delta > PI) delta -= TWO_PI
        lastPointAngle = a
        cumulative += delta
        apply(cumulative)
    }

    /** iOS moveTimerZeroForDeltaAngle: cumulative angle → time from the drag-start instant. */
    private fun apply(d: Double) {
        if (kind == WORLDTIME_RING_KIND) {
            applyRing(d)
            return
        }
        if (kindDragsAlarm(kind)) {
            applyAlarm(d)
            return
        }
        val t = time
        val s = startDisplayedMs
        fun seconds(sec: Double) = t.setDisplayedFrom(s, sec)
        fun turns(perTurn: Double) = d * perTurn / TWO_PI
        when (kind) {
            "secondKind" -> seconds(turns(60.0))
            "minuteKind" -> seconds(60.0 * turns(60.0))
            "hour12Kind" -> seconds(3600.0 * turns(12.0))
            "hour24Kind" -> seconds(3600.0 * turns(24.0))
            "reverseHour24Kind" -> seconds(-3600.0 * turns(24.0))
            "hour24MoonKind" -> seconds(86400.0 * d * SYNODIC_DAYS / (SYNODIC_DAYS - 1) / TWO_PI)
            "dayKind" -> t.advanceDaysFrom(s, Math.round(turns(31.0)).toInt())
            "moonDayKind" -> seconds(86400.0 * turns(SYNODIC_DAYS))
            "weekDayKind" -> t.advanceDaysFrom(s, Math.round(turns(7.0)).toInt())
            "monthKind" -> t.advanceMonthsFrom(s, Math.round(turns(12.0)).toInt())
            "year1Kind" -> t.advanceYearsFrom(s, Math.round(turns(10.0)).toInt())
            "year10Kind" -> t.advanceYearsFrom(s, Math.round(10 * turns(10.0)).toInt())
            "year100Kind" -> t.advanceYearsFrom(s, Math.round(100 * turns(10.0)).toInt())
            "year1000Kind" -> t.advanceYearsFrom(s, Math.round(1000 * turns(10.0)).toInt())
            "greatYearKind" -> t.advanceYearsFrom(s, Math.round(turns(25772.0)).toInt())
            "sunRAKind" -> seconds(86400.0 * turns(365.242191))
            "reverseSunRAKind" -> seconds(-86400.0 * turns(365.242191))
            "moonRAKind" -> seconds(86400.0 * turns(27.322))
            "reverseMoonRAKind" -> seconds(-86400.0 * turns(27.322))
            "mercuryYearKind" -> seconds(-86400.0 * turns(87.97))
            "venusYearKind" -> seconds(-86400.0 * turns(224.70))
            "earthYearKind" -> seconds(-86400.0 * turns(365.26))
            "marsYearKind" -> seconds(-86400.0 * turns(686.98))
            "jupiterYearKind" -> seconds(-86400.0 * turns(4332.71))
            "saturnYearKind" -> seconds(-86400.0 * turns(10759.50))
            "nodalKind" -> t.advanceDaysFrom(s, Math.round(-turns(6793.5)).toInt())
        }
    }

    /** iOS moveWorldtimeRingForDeltaAngle (ECGLPart.m:1034-1049): the cumulative clock angle
     *  in 15° notches, normalized 0..23; each NEW notch since the last move fires the action. */
    private fun applyRing(d: Double) {
        var required = Math.round(d * 12 / PI).toInt() % 24
        if (required < 0) required += 24
        var newShifts = (required - ringShifts) % 24
        if (newShifts < 0) newShifts += 24
        ringShifts = required
        if (newShifts > 0) ringAct?.invoke(newShifts)
    }

    /** iOS moveAlarmTimeForDeltaAngle: interval hands set the countdown (seconds continuous,
     *  larger hands in whole units); target hands set the alarm time of day, minute-snapped. */
    private fun applyAlarm(d: Double) {
        val a = alarm ?: return
        fun units(perTurn: Double) = Math.round(d * perTurn / (2 * PI)).toDouble()
        fun wrap(sec: Double): Double {
            val r = sec % 86400.0
            return if (r < 0) r + 86400.0 else r
        }
        when (kind) {
            "intervalSecondKind" -> a.specifyInterval(floor(wrap(startAlarmOffset + d * 60 / (2 * PI))))
            "intervalMinuteKind" -> a.specifyInterval(floor(wrap(startAlarmOffset + 60.0 * units(60.0))))
            "intervalHour12Kind" -> a.specifyInterval(floor(wrap(startAlarmOffset + 3600.0 * units(12.0))))
            "intervalHour24Kind" -> a.specifyInterval(floor(wrap(startAlarmOffset + 3600.0 * units(24.0))))
            "targetMinuteKind" -> a.specifyTarget(floor(wrap(startAlarmOffset + 60.0 * units(60.0)) / 60) * 60)
            "targetHour12Kind" -> a.specifyTarget(floor(wrap(startAlarmOffset + 3600.0 * units(12.0)) / 60) * 60)
            "targetHour12KindB" -> a.specifyTarget(floor(wrap(startAlarmOffset + 300.0 * units(144.0)) / 300) * 300)
            "targetHour24Kind" -> a.specifyTarget(floor(wrap(startAlarmOffset + 3600.0 * units(24.0)) / 60) * 60)
        }
    }
}
