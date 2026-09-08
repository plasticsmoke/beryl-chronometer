package com.plasticsmoke.beryl.astro

import com.plasticsmoke.beryl.expr.Environment
import kotlin.math.PI
import kotlin.math.floor

/**
 * Interaction stage 3: the stopwatch subsystem (Olympia's chronograph with rattrapante),
 * ported from iOS ECWatchTime's stopwatch methods + ECGLWatch's three timer slots +
 * the stopwatch ops in ECVirtualMachineOps.m.
 *
 * A [StopwatchTimer] is a "smooth-time" ECWatchTime: reading = warp·realSeconds + zero,
 * zero at reset, unaffected by the main time base's time travel. Three timers cooperate:
 *
 *  - stopwatch: what the start/stop button measures;
 *  - lap: the rattrapante (split-seconds) hand — normally IDENTICAL to stopwatch (riding it);
 *    the rattrapante button freezes it at the split, pressing again rejoins;
 *  - display: what dial-shared hands show (faces with a separate lap-display flow; Olympia's
 *    hands read stopwatch/lap directly but lapReset uses it).
 */
class StopwatchTimer(private val systemNow: () -> Long) {
    /** reading = warp·realSec + zero; when stopped (warp 0) zero IS the frozen reading. */
    var warp = 0.0
        private set
    var zero = 0.0
        private set

    private fun realSec() = systemNow() / 1000.0

    val isStopped: Boolean get() = warp == 0.0

    /** Elapsed seconds on this timer right now. */
    fun reading(): Double = if (warp == 0.0) zero else warp * realSec() + zero

    /** Restore persisted state verbatim (zero is anchored to absolute real time). */
    fun restore(warp: Double, zero: Double) {
        this.warp = warp
        this.zero = zero
    }

    /** iOS toggleStopWithRounding: stopping TRUNCATES to the rounding step so a restart
     *  continues exactly where the frozen hand purports to be (mechanical behavior). */
    fun toggleStop(rounding: Double) {
        if (warp == 0.0) {
            zero -= realSec()
            warp = 1.0
        } else {
            zero = reading()
            warp = 0.0
            if (rounding > 0) zero = floor(zero / rounding) * rounding
        }
    }

    /** Back to zero; keeps running if running (iOS stopwatchReset). */
    fun reset() {
        zero = if (warp == 0.0) 0.0 else -warp * realSec()
    }

    /** Freeze at [other]'s current reading (the rattrapante split — iOS copyLapTime). */
    fun copyLapFrom(other: StopwatchTimer) {
        zero = other.reading()
        warp = 0.0
    }

    fun makeIdenticalTo(other: StopwatchTimer) {
        zero = other.zero
        warp = other.warp
    }

    fun isIdenticalTo(other: StopwatchTimer): Boolean =
        zero == other.zero && warp == other.warp
}

/** The three-slot stopwatch state one face owns (shared by its mode envs). */
class StopwatchState(systemNow: () -> Long = { System.currentTimeMillis() }) {
    val stopwatch = StopwatchTimer(systemNow)
    val lap = StopwatchTimer(systemNow)
    val display = StopwatchTimer(systemNow)

    /** Called after every mutation — the app persists the state. */
    var onChange: (() -> Unit)? = null

    private fun changed() {
        onChange?.invoke()
    }

    /** iOS stopwatchStartStopWithRounding: lap/display follow unless independently split. */
    fun startStop(rounding: Double) {
        val displayingLap = !stopwatch.isIdenticalTo(display)
        val rattrapanteEngaged = !stopwatch.isIdenticalTo(lap)
        stopwatch.toggleStop(rounding)
        if (!displayingLap) display.makeIdenticalTo(stopwatch)
        if (!rattrapanteEngaged) lap.makeIdenticalTo(stopwatch)
        changed()
    }

    fun reset() {
        stopwatch.reset()
        display.makeIdenticalTo(stopwatch)
        lap.makeIdenticalTo(stopwatch)
        changed()
    }

    /** The split button: freeze the lap hand at the split, or rejoin it (iOS rattrapante). */
    fun rattrapante(rounding: Double) {
        if (stopwatch.isIdenticalTo(lap)) {
            if (!lap.isStopped) lap.toggleStop(rounding)
        } else {
            lap.makeIdenticalTo(stopwatch)
        }
        changed()
    }

    /** iOS doLapReset (display-timer faces): lap-freeze the display, rejoin, or reset. */
    fun lapReset() {
        if (stopwatch.isStopped) {
            if (stopwatch.isIdenticalTo(display)) {
                stopwatch.reset()
                display.reset()
            } else {
                display.makeIdenticalTo(stopwatch)
            }
        } else {
            if (display.isStopped) display.makeIdenticalTo(stopwatch)
            else display.copyLapFrom(stopwatch)
        }
        changed()
    }

    val rattrapanteValid: Boolean get() = !(lap.isStopped && stopwatch.isIdenticalTo(lap))
}

private fun posFmod(a: Double, m: Double): Double {
    val r = a % m
    return if (r < 0) r + m else r
}

/**
 * Register the real stopwatch leaves over the render-test stubs — like
 * [registerTimeFunctions], call AFTER the face stubs so the live impls win.
 */
fun registerStopwatchFunctions(env: Environment, sw: StopwatchState) {
    val f = env.functions

    f["stopwatchStartStop"] = { a -> sw.startStop(if (a.isNotEmpty()) a[0] else 0.0); 0.0 }
    f["stopwatchReset"] = { sw.reset(); 0.0 }
    f["stopwatchRattrapante"] = { a -> sw.rattrapante(if (a.isNotEmpty()) a[0] else 0.0); 0.0 }
    f["stopwatchLapReset"] = { sw.lapReset(); 0.0 }
    f["stopwatchRattrapanteValid"] = { if (sw.rattrapanteValid) 1.0 else 0.0 }

    // The reading leaves: Number = integer unit (floor), Value = fractional, Angle = scaled.
    // One set per timer slot: '' = stopwatch, 'Lap' = lap, 'Display' = display (iOS naming).
    for ((prefix, timer) in listOf(
        "stopwatch" to sw.stopwatch,
        "stopwatchLap" to sw.lap,
        "stopwatchDisplay" to sw.display,
    )) {
        fun t() = timer.reading()
        f["${prefix}SecondNumber"] = { (floor(t()) % 60.0) }
        f["${prefix}SecondValue"] = { posFmod(t(), 60.0) }
        f["${prefix}SecondNumberAngle"] = { (2 * PI / 60) * (floor(t()) % 60.0) }
        f["${prefix}SecondValueAngle"] = { (2 * PI / 60) * posFmod(t(), 60.0) }
        f["${prefix}MinuteNumber"] = { floor(t() / 60) % 60.0 }
        f["${prefix}MinuteValue"] = { posFmod(t() / 60, 60.0) }
        f["${prefix}MinuteNumberAngle"] = { (2 * PI / 60) * (floor(t() / 60) % 60.0) }
        f["${prefix}MinuteValueAngle"] = { (2 * PI / 60) * posFmod(t() / 60, 60.0) }
        f["${prefix}Hour24Number"] = { floor(t() / 3600) % 24.0 }
        f["${prefix}Hour24Value"] = { posFmod(t() / 3600, 24.0) }
        f["${prefix}Hour24ValueAngle"] = { (2 * PI / 24) * posFmod(t() / 3600, 24.0) }
        f["${prefix}Hour12Number"] = { floor(t() / 3600) % 12.0 }
        f["${prefix}Hour12Value"] = { posFmod(t() / 3600, 12.0) }
        f["${prefix}Hour12ValueAngle"] = { (2 * PI / 12) * posFmod(t() / 3600, 12.0) }
        f["${prefix}DayNumber"] = { floor(t() / 86400) }
        f["${prefix}DayValue"] = { t() / 86400 }
    }
}
