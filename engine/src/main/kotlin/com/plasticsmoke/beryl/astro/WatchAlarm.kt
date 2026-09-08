package com.plasticsmoke.beryl.astro

import com.plasticsmoke.beryl.expr.Environment
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor

/**
 * Interaction stage 3: the alarm/interval-timer subsystem (Istanbul's target alarm, Thebes'
 * countdown timer), ported from iOS ECAlarmTime + ECGLWatch's alarm methods + the alarm ops
 * in ECVirtualMachineOps.m.
 *
 * Two modes (iOS ECAlarmTimeMode):
 *  - TARGET: the alarm fires at a time of day — [specifiedOffset] is LOGICAL seconds from
 *    local midnight (6 AM is 6·3600 even across DST); the derived [targetMs] is the next
 *    occurrence (today or tomorrow) in main displayed time.
 *  - INTERVAL: a countdown. iOS models it as an alarm ECWatchTime against the main time:
 *    countdown RUNNING = alarm time FROZEN at a fixed target (the derived interval counts
 *    down); countdown PAUSED = alarm time moving WITH the main time (the interval holds).
 *    Here that is [intervalRunning] + [intervalTargetMs] / [remainingSec].
 *
 * The interval hands re-derive from [effectiveOffset] every frame; the alarm hands show the
 * absolute fire time [currentAlarmTimeMs]. With nothing ever set, the default alarm time is
 * the next local midnight (iOS defaultAlarmTimeForTime) — hands at 12:00:00.
 */
class AlarmState(
    private val mainTime: WatchTimeState,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val systemNow: () -> Long = { System.currentTimeMillis() },
) {
    enum class Mode { TARGET, INTERVAL }

    /**
     * iOS creates its ECAlarmTime lazily (ensureAlarmTimerPresent): before ANY alarm
     * interaction the object is nil — the alarmType* face inits no-op against it, the alarm
     * hands read the default next-midnight time, and the interval reads a frozen 0. The
     * first mutating op "creates" it as a target alarm at midnight, exactly like iOS.
     */
    var everSet = false
        private set

    var mode = Mode.TARGET
        private set

    /** TARGET: logical seconds from local midnight; INTERVAL: the specified countdown. */
    var specifiedOffset = 0.0
        private set

    /** TARGET mode: absolute displayed-time ms of the next fire. */
    var targetMs = 0L
        private set

    /** INTERVAL mode state (see class doc). */
    var intervalRunning = false
        private set
    var intervalTargetMs = 0L
        private set
    var remainingSec = 0.0
        private set

    /** The alarm stem (iOS ECGLWatch.alarmManualSet) — gates the set-mode buttons/hands. */
    var manualSet = false
        private set

    var enabled = false
        private set

    /** Ringing state (iOS ECAudio): ring count advances while ringing; a touch stops it. */
    private var ringingSinceRealMs = -1L
    private var lastFiredTargetMs = -1L

    /** Called after every mutation — the app persists the state and redraws. */
    var onChange: (() -> Unit)? = null

    private fun changed() {
        onChange?.invoke()
    }

    init {
        recalcTarget()
    }

    // ---- derived readings ----

    private fun nowMs() = mainTime.displayedNow()

    /** Absolute displayed-time ms when the alarm will fire (iOS currentAlarmTime). */
    val currentAlarmTimeMs: Long
        get() = when {
            !everSet -> defaultTargetMs()
            mode == Mode.TARGET -> targetMs
            intervalRunning -> intervalTargetMs
            else -> nowMs() + (remainingSec * 1000).toLong()
        }

    /** iOS defaultAlarmTimeForTime: the next local midnight (hands read 12:00:00). */
    private fun defaultTargetMs(): Long =
        Instant.ofEpochMilli(nowMs()).atZone(zone).plusDays(1)
            .withHour(0).withMinute(0).withSecond(0).withNano(0)
            .toInstant().toEpochMilli()

    /** Remaining countdown seconds, wrapped to 24 h (iOS effectiveOffset). */
    val effectiveOffset: Double
        get() = if (!everSet) 0.0 else posFmod24h((currentAlarmTimeMs - nowMs()) / 1000.0)

    /** iOS setToFire: target alarms always run; interval alarms only while counting down. */
    val setToFire: Boolean get() = mode == Mode.TARGET || intervalRunning

    val isZero: Boolean
        get() = effectiveOffset.let { abs(it) < 0.01 || abs(it - 86400.0) < 0.01 }

    val ringing: Boolean get() = ringingSinceRealMs >= 0
    val ringCount: Double
        get() = if (ringing) floor((systemNow() - ringingSinceRealMs) / RING_INTERVAL_MS) else 0.0

    // ---- the state machine ----

    /** iOS ensureAlarmTimerPresent: first interaction creates a target alarm at midnight. */
    private fun ensurePresent() {
        if (!everSet) {
            everSet = true
            mode = Mode.TARGET
            specifiedOffset = 0.0
            recalcTarget()
        }
    }

    /** Restore persisted state (no onChange — used at face load). */
    fun restore(
        mode: Mode,
        specifiedOffset: Double,
        enabled: Boolean,
        intervalRunning: Boolean,
        intervalTargetMs: Long,
        remainingSec: Double,
    ) {
        this.everSet = true
        this.mode = mode
        this.specifiedOffset = specifiedOffset
        this.enabled = enabled
        this.intervalRunning = intervalRunning
        this.intervalTargetMs = intervalTargetMs
        this.remainingSec = remainingSec
        if (mode == Mode.TARGET) recalcTarget()
        // A countdown that expired while the app was away just sits expired (no missed-alarm UI).
        if (intervalRunning) lastFiredTargetMs = -1L
    }

    /** Next occurrence of [specifiedOffset]-from-midnight at/after the main displayed now. */
    private fun recalcTarget() {
        val whole = floor(specifiedOffset).toInt()
        val frac = specifiedOffset - whole
        var zdt = Instant.ofEpochMilli(nowMs()).atZone(zone)
            .withHour((whole / 3600) % 24).withMinute((whole / 60) % 60).withSecond(whole % 60)
            .withNano((frac * 1e9).toInt())
        if (zdt.toInstant().toEpochMilli() < nowMs()) zdt = zdt.plusDays(1)
        targetMs = zdt.toInstant().toEpochMilli()
    }

    /** Reload the countdown to [specifiedOffset], PAUSED (iOS recalculateIntervalTime). */
    private fun recalcInterval() {
        remainingSec = specifiedOffset
        intervalRunning = false
        lastFiredTargetMs = -1L
    }

    fun specifyTarget(logicalSecondsFromMidnight: Double) {
        ensurePresent()
        mode = Mode.TARGET
        specifiedOffset =
            if (logicalSecondsFromMidnight < 0 || logicalSecondsFromMidnight >= 86400.0) 0.0
            else logicalSecondsFromMidnight
        recalcTarget()
        changed()
    }

    /** iOS ECGLWatch setIntervalOffset: near-zero wraps to a full day. */
    fun specifyInterval(intervalSeconds: Double) {
        ensurePresent()
        mode = Mode.INTERVAL
        specifiedOffset = if (intervalSeconds < 0.01) 86400.0 else intervalSeconds
        recalcInterval()
        changed()
    }

    /** Mode-switch action leaves (alarmTypeTarget()/alarmTypeInterval() face inits). iOS
     *  setAlarmToTarget/setAlarmToInterval CREATE the alarm when absent — Istanbul's init
     *  yields a midnight target alarm, Thebes' a zero countdown. */
    fun setModeToTarget() {
        ensurePresent()
        if (mode != Mode.TARGET) {
            // Keep the same fire time-of-day (iOS: specified = alarm hour24Value·3600).
            val alarm = Instant.ofEpochMilli(currentAlarmTimeMs).atZone(zone)
            mode = Mode.TARGET
            specifiedOffset = alarm.hour * 3600.0 + alarm.minute * 60.0 + alarm.second.toDouble()
            recalcTarget()
            changed()
        }
    }

    fun setModeToInterval() {
        if (!everSet) {
            // iOS setAlarmToInterval on a nil alarm: create + specifyIntervalAlarmAt:0
            // (raw zero — the 24 h near-zero wrap belongs to setIntervalOffset only).
            everSet = true
            mode = Mode.INTERVAL
            specifiedOffset = 0.0
            recalcInterval()
            changed()
        } else if (mode != Mode.INTERVAL) {
            val offset = posFmod24h((currentAlarmTimeMs - nowMs()) / 1000.0)
            mode = Mode.INTERVAL
            specifiedOffset = offset
            recalcInterval()
            changed()
        }
    }

    /** Start the countdown: freeze the fire time (iOS startTimer = alarmWatchTime stop). */
    fun startTimer() {
        ensurePresent()
        if (!intervalRunning) {
            intervalTargetMs = nowMs() + (effectiveOffset * 1000).toLong()
            intervalRunning = true
            lastFiredTargetMs = -1L
            changed()
        }
    }

    /** Pause the countdown: hold the remaining interval (iOS stopTimer). */
    fun stopTimer() {
        if (intervalRunning) {
            remainingSec = effectiveOffset
            intervalRunning = false
            changed()
        }
    }

    fun toggleTimer() {
        if (intervalRunning) stopTimer() else startTimer()
    }

    /** Thebes RESET: reload the countdown to the specified interval, or clear to 24 h. */
    fun alarmReset() {
        ensurePresent()
        if (abs(effectiveOffset - specifiedOffset) > 0.1) {
            recalcInterval()
            changed()
        } else {
            specifyInterval(0.0)
        }
    }

    fun enable() {
        enabled = true
        changed()
    }

    fun disable() {
        enabled = false
        stopRinging()
        changed()
    }

    /** iOS alarmStemIn: just push the stem back in. */
    fun stemIn() {
        manualSet = false
        changed()
    }

    /** iOS alarmStemOut: enable a target alarm outright; pause a running countdown. */
    fun stemOut() {
        ensurePresent()
        manualSet = true
        if (mode == Mode.TARGET) enabled = true
        else if (intervalRunning) {
            remainingSec = effectiveOffset
            intervalRunning = false
        }
        changed()
    }

    // Advance buttons switch mode first (iOS), then step floor-snapped, wrapping at 24 h.

    fun advanceAlarmHour() = advanceTarget(3600.0)
    fun advanceAlarmMinute() = advanceTarget(60.0)
    fun toggleAlarmAMPM() = advanceTarget(12 * 3600.0)

    private fun advanceTarget(step: Double) {
        ensurePresent()
        setModeToTarget()
        specifiedOffset = floor(specifiedOffset / 60) * 60 + step
        if (specifiedOffset >= 86400.0) specifiedOffset -= 86400.0
        recalcTarget()
        changed()
    }

    fun advanceIntervalHour() = advanceInterval(3600.0)
    fun advanceIntervalMinute() = advanceInterval(60.0)
    fun advanceIntervalSecond() = advanceInterval(1.0)

    private fun advanceInterval(step: Double) {
        ensurePresent()
        setModeToInterval()
        specifiedOffset = floor(specifiedOffset) + step
        if (specifiedOffset >= 86400.0) specifiedOffset -= 86400.0
        recalcInterval()
        changed()
    }

    // ---- firing ----

    /**
     * Per-frame check (the app calls this from the render loop): when the main displayed
     * time reaches the alarm time of an enabled, armed alarm, start ringing. A fired target
     * alarm re-arms for the next day (iOS's repeating local notification).
     */
    fun checkFire() {
        // Auto-silence after the ring budget (iOS's ECAudio stops on its own eventually).
        if (ringing && systemNow() - ringingSinceRealMs > RING_TOTAL_MS) stopRinging()
        if (!everSet || !enabled || !setToFire) return
        val target = currentAlarmTimeMs
        if (target == lastFiredTargetMs) return
        if (nowMs() >= target) {
            lastFiredTargetMs = target
            ringingSinceRealMs = systemNow()
            if (mode == Mode.TARGET) recalcTarget()
            changed()
        }
    }

    /** A touch anywhere stops the bell (iOS stopAlarmRinging). True if it was ringing. */
    fun stopRinging(): Boolean {
        val was = ringing
        ringingSinceRealMs = -1L
        return was
    }

    private companion object {
        const val RING_INTERVAL_MS = 1000.0
        const val RING_TOTAL_MS = 20_000L
    }
}

private fun posFmod24h(sec: Double): Double {
    val r = sec % 86400.0
    return if (r < 0) r + 86400.0 else r
}

/**
 * Register the real alarm/interval leaves over the render-test stubs — like
 * [registerTimeFunctions], call AFTER the face stubs so the live impls win.
 */
fun registerAlarmFunctions(env: Environment, alarm: AlarmState, zone: ZoneId) {
    val f = env.functions

    // Action leaves.
    f["alarmStemIn"] = { alarm.stemIn(); 0.0 }
    f["alarmStemOut"] = { alarm.stemOut(); 0.0 }
    f["enableAlarm"] = { alarm.enable(); 0.0 }
    f["disableAlarm"] = { alarm.disable(); 0.0 }
    f["advanceAlarmHour"] = { alarm.advanceAlarmHour(); 0.0 }
    f["advanceAlarmMinute"] = { alarm.advanceAlarmMinute(); 0.0 }
    f["toggleAlarmAMPM"] = { alarm.toggleAlarmAMPM(); 0.0 }
    f["advanceIntervalHour"] = { alarm.advanceIntervalHour(); 0.0 }
    f["advanceIntervalMinute"] = { alarm.advanceIntervalMinute(); 0.0 }
    f["advanceIntervalSecond"] = { alarm.advanceIntervalSecond(); 0.0 }
    f["startIntervalTimer"] = { alarm.startTimer(); 0.0 }
    f["stopIntervalTimer"] = { alarm.stopTimer(); 0.0 }
    f["toggleIntervalTimer"] = { alarm.toggleTimer(); 0.0 }
    f["alarmReset"] = { alarm.alarmReset(); 0.0 }
    f["alarmTypeTarget"] = { alarm.setModeToTarget(); 0.0 }
    f["alarmTypeInterval"] = { alarm.setModeToInterval(); 0.0 }

    // State reads.
    f["alarmManualSet"] = { if (alarm.manualSet) 1.0 else 0.0 }
    f["alarmEnabled"] = { if (alarm.enabled) 1.0 else 0.0 }
    f["alarmRinging"] = { if (alarm.ringing) 1.0 else 0.0 }
    f["rings"] = { alarm.ringCount }
    f["alarmIsZero"] = { if (alarm.isZero) 1.0 else 0.0 }

    // The alarm hands: components of the absolute fire time in the face's zone.
    fun alarmZdt() = Instant.ofEpochMilli(alarm.currentAlarmTimeMs).atZone(zone)
    fun secondsIntoDay(): Double =
        alarmZdt().let { it.hour * 3600.0 + it.minute * 60.0 + it.second + it.nano / 1e9 }
    f["alarmSecondNumber"] = { alarmZdt().second.toDouble() }
    f["alarmSecondValue"] = { alarmZdt().let { it.second + it.nano / 1e9 } }
    f["alarmSecondNumberAngle"] = { (2 * PI / 60) * alarmZdt().second }
    f["alarmSecondValueAngle"] = { (2 * PI / 60) * alarmZdt().let { it.second + it.nano / 1e9 } }
    f["alarmMinuteNumber"] = { alarmZdt().minute.toDouble() }
    f["alarmMinuteValue"] = { (secondsIntoDay() / 60) % 60 }
    f["alarmMinuteNumberAngle"] = { (2 * PI / 60) * alarmZdt().minute }
    f["alarmMinuteValueAngle"] = { (2 * PI / 60) * ((secondsIntoDay() / 60) % 60) }
    f["alarmHour12Number"] = { (alarmZdt().hour % 12).toDouble() }
    f["alarmHour12Value"] = { (secondsIntoDay() / 3600) % 12 }
    f["alarmHour12NumberAngle"] = { (2 * PI / 12) * (alarmZdt().hour % 12) }
    f["alarmHour12ValueAngle"] = { (2 * PI / 12) * ((secondsIntoDay() / 3600) % 12) }
    f["alarmHour24Number"] = { alarmZdt().hour.toDouble() }
    f["alarmHour24Value"] = { secondsIntoDay() / 3600 }
    f["alarmHour24NumberAngle"] = { (2 * PI / 24) * alarmZdt().hour }
    f["alarmHour24ValueAngle"] = { (2 * PI / 24) * (secondsIntoDay() / 3600) }

    // The interval (countdown) hands: stopwatch-style readings of the remaining time.
    fun io() = alarm.effectiveOffset
    f["intervalSecondNumber"] = { floor(io()) % 60.0 }
    f["intervalSecondValue"] = { io() % 60.0 }
    f["intervalSecondNumberAngle"] = { (2 * PI / 60) * (floor(io()) % 60.0) }
    f["intervalSecondValueAngle"] = { (2 * PI / 60) * (io() % 60.0) }
    f["intervalMinuteNumber"] = { floor(io() / 60) % 60.0 }
    f["intervalMinuteValue"] = { (io() / 60) % 60.0 }
    f["intervalMinuteNumberAngle"] = { (2 * PI / 60) * (floor(io() / 60) % 60.0) }
    f["intervalMinuteValueAngle"] = { (2 * PI / 60) * ((io() / 60) % 60.0) }
    f["intervalHour12Number"] = { floor(io() / 3600) % 12.0 }
    f["intervalHour12Value"] = { (io() / 3600) % 12.0 }
    f["intervalHour12ValueAngle"] = { (2 * PI / 12) * ((io() / 3600) % 12.0) }
    f["intervalHour24Number"] = { floor(io() / 3600) % 24.0 }
    f["intervalHour24Value"] = { (io() / 3600) % 24.0 }
    f["intervalHour24ValueAngle"] = { (2 * PI / 24) * ((io() / 3600) % 24.0) }
}
