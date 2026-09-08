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
 * Interaction stage 3: the alarm/interval subsystem — Istanbul's target alarm and Thebes'
 * countdown timer, ported from iOS ECAlarmTime + ECGLWatch alarm methods.
 */
class WatchAlarmTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    // 2026-07-04 10:10:30 PDT
    private var realMs = ZonedDateTime.of(2026, 7, 4, 10, 10, 30, 0, zone).toInstant().toEpochMilli()

    private fun newAlarm(): Pair<WatchTimeState, AlarmState> {
        val time = WatchTimeState(systemNow = { realMs }, zone = zone)
        return time to AlarmState(time, zone, systemNow = { realMs })
    }

    private fun env(alarm: AlarmState): com.plasticsmoke.beryl.expr.Environment {
        val e = createDefaultEnvironment()
        registerAlarmFunctions(e, alarm, zone)
        return e
    }

    private fun alarmLocal(alarm: AlarmState): ZonedDateTime =
        Instant.ofEpochMilli(alarm.currentAlarmTimeMs).atZone(zone)

    @Test fun untouchedAlarmDefaultsMatchTheFaceInits() {
        // Before ANY interaction (iOS: nil ECAlarmTime): hands read next midnight.
        val (_, virgin) = newAlarm()
        val e0 = env(virgin)
        assertFalse(virgin.everSet)
        assertEquals(0.0, evaluateExpression("alarmHour24Number()", e0))
        assertEquals(1.0, evaluateExpression("alarmIsZero()", e0))

        // Istanbul's init alarmTypeTarget() CREATES a midnight target alarm (iOS
        // setAlarmToTarget → ensureAlarmTimerPresent), disabled.
        val (_, ist) = newAlarm()
        val e1 = env(ist)
        evaluateExpression("alarmTypeTarget()", e1)
        assertTrue(ist.everSet)
        assertEquals(0.0, evaluateExpression("alarmHour24Number()", e1))
        assertEquals(0.0, evaluateExpression("alarmEnabled()", e1))

        // Thebes' init alarmTypeInterval() creates a ZERO countdown, paused.
        val (_, thb) = newAlarm()
        val e2 = env(thb)
        evaluateExpression("alarmTypeInterval()", e2)
        assertEquals(AlarmState.Mode.INTERVAL, thb.mode)
        assertEquals(0.0, evaluateExpression("intervalMinuteValueAngle()", e2))
        assertEquals(1.0, evaluateExpression("alarmIsZero()", e2))
    }

    @Test fun istanbulTargetAlarmFlow() {
        val (_, alarm) = newAlarm()
        val e = env(alarm)

        // Pulling the alarm stem creates + enables a midnight target alarm (iOS alarmStemOut).
        evaluateExpression("alarmManualSet() ? alarmStemIn() : alarmStemOut()", e)
        assertTrue(alarm.manualSet)
        assertTrue(alarm.enabled)
        assertEquals(0, alarmLocal(alarm).hour)

        // Set 7:30, then flip to PM: 19:30 (iOS advance ops floor-snap to the minute).
        repeat(7) { evaluateExpression("advanceAlarmHour()", e) }
        repeat(30) { evaluateExpression("advanceAlarmMinute()", e) }
        evaluateExpression("toggleAlarmAMPM()", e)
        assertEquals(19, alarmLocal(alarm).hour)
        assertEquals(30, alarmLocal(alarm).minute)
        assertEquals(19.5, evaluateExpression("alarmHour24Value()", e), 1e-9)
        // 10:10 AM now, 7:30 PM today is ahead — the target is TODAY's occurrence.
        assertEquals(4, alarmLocal(alarm).dayOfMonth)

        // Fire: cross 19:30 → ringing; the target re-arms for tomorrow (iOS repeats daily).
        evaluateExpression("alarmStemIn()", e)
        realMs = ZonedDateTime.of(2026, 7, 4, 19, 30, 1, 0, zone).toInstant().toEpochMilli()
        alarm.checkFire()
        assertTrue(alarm.ringing)
        assertEquals(1.0, evaluateExpression("alarmRinging()", e))
        realMs += 3200
        assertEquals(3.0, evaluateExpression("rings()", e), "ring count advances ~1/s")
        assertEquals(5, alarmLocal(alarm).dayOfMonth, "re-armed for tomorrow 19:30")

        // A touch silences it (iOS stopAlarmRinging).
        assertTrue(alarm.stopRinging())
        assertFalse(alarm.ringing)
    }

    @Test fun thebesIntervalCountdownFlow() {
        val (_, alarm) = newAlarm()
        val e = env(alarm)

        // Thebes' face init, then stem out and set a 3-minute countdown.
        evaluateExpression("alarmTypeInterval()", e)
        evaluateExpression("alarmStemOut()", e)
        repeat(3) { evaluateExpression("advanceIntervalMinute()", e) }
        assertEquals(AlarmState.Mode.INTERVAL, alarm.mode)
        assertFalse(alarm.intervalRunning, "adjusting loads a PAUSED countdown")
        assertEquals(3.0, evaluateExpression("intervalMinuteValue()", e), 0.02)
        realMs += 10_000
        assertEquals(3.0, evaluateExpression("intervalMinuteValue()", e), 0.02, "paused countdown holds")

        // Thebes' start button: toggleIntervalTimer(), alarmStemIn().
        evaluateExpression("enableAlarm()", e)
        evaluateExpression("toggleIntervalTimer(), alarmStemIn()", e)
        assertTrue(alarm.intervalRunning)
        realMs += 60_000
        assertEquals(120.0, alarm.effectiveOffset, 0.02, "counting down")

        // Pause holds the remaining time.
        evaluateExpression("toggleIntervalTimer()", e)
        realMs += 30_000
        assertEquals(120.0, alarm.effectiveOffset, 0.02)

        // RESET reloads the specified 3:00 (paused); a second RESET clears to the 24 h zero.
        evaluateExpression("alarmReset()", e)
        assertEquals(180.0, alarm.effectiveOffset, 0.02)
        evaluateExpression("alarmReset()", e)
        assertEquals(1.0, evaluateExpression("alarmIsZero()", e))

        // Run a fresh countdown to zero: it fires.
        repeat(2) { evaluateExpression("advanceIntervalSecond()", e) }
        evaluateExpression("toggleIntervalTimer()", e)
        realMs += 2_500
        alarm.checkFire()
        assertTrue(alarm.ringing, "countdown reached zero while enabled")
    }

    @Test fun alarmHandDragging() {
        val (time, alarm) = newAlarm()
        // Thebes' interval minute hand: +90° = +15 whole minutes on the countdown.
        alarm.specifyInterval(300.0)
        val s = HandDragSession(time, "intervalMinuteKind", 0.0, 0.0, 0.0, 100.0, alarm = alarm)
        s.moveTo(100.0, 0.0)
        assertEquals(300.0 + 900.0, alarm.effectiveOffset, 0.02)

        // Istanbul's target hour hand: +90° on a 12-hour hand = +3 hours, minute-snapped.
        val (time2, alarm2) = newAlarm()
        alarm2.specifyTarget(7 * 3600.0)
        val s2 = HandDragSession(time2, "targetHour12Kind", 0.0, 0.0, 0.0, 100.0, alarm = alarm2)
        s2.moveTo(100.0, 0.0)
        assertEquals(10 * 3600.0, alarm2.specifiedOffset, 1e-9)
    }

    @Test fun persistenceRoundTrip() {
        val (_, alarm) = newAlarm()
        alarm.specifyInterval(600.0)
        alarm.enable()
        alarm.startTimer()
        realMs += 60_000

        val (_, alarm2) = newAlarm()
        alarm2.restore(
            mode = alarm.mode,
            specifiedOffset = alarm.specifiedOffset,
            enabled = alarm.enabled,
            intervalRunning = alarm.intervalRunning,
            intervalTargetMs = alarm.intervalTargetMs,
            remainingSec = alarm.remainingSec,
        )
        assertEquals(alarm.effectiveOffset, alarm2.effectiveOffset, 1e-6)
        assertEquals(540.0, alarm2.effectiveOffset, 0.02, "10 min countdown, 1 min elapsed")
        assertTrue(alarm2.enabled)
    }
}
