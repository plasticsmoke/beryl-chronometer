package com.plasticsmoke.beryl.astro

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Interaction stage 2, hand dragging: the cumulative-angle → time mapping ported from iOS
 * ECGLPart dragFrom/moveTimerZeroForDeltaAngle. The watch is stopped (stem out) in every
 * test — hands are only grabbable in set mode.
 */
class HandDragTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private var realMs = Instant.parse("2026-07-04T17:10:30Z").toEpochMilli()

    private fun stoppedTime(): WatchTimeState {
        val t = WatchTimeState(systemNow = { realMs }, zone = zone)
        t.stop()
        return t
    }

    private fun local(time: WatchTimeState): ZonedDateTime =
        Instant.ofEpochMilli(time.displayedNow()).atZone(zone)

    @Test fun minuteHandQuarterTurnAddsFifteenMinutes() {
        val t = stoppedTime()
        val frozen = t.displayedNow()
        // Grab the minute hand at 12 o'clock (anchor at the center) and turn it to 3 o'clock.
        val s = HandDragSession(t, "minuteKind", 0.0, 0.0, 0.0, 100.0)
        s.moveTo(100.0, 0.0)
        assertEquals(frozen + 15 * 60_000, t.displayedNow(), "+90° on the minute hand = +15 min")
    }

    @Test fun cumulativeAngleSurvivesFullRevolutions() {
        val t = stoppedTime()
        val frozen = t.displayedNow()
        val s = HandDragSession(t, "minuteKind", 0.0, 0.0, 0.0, 100.0)
        // A full clockwise lap (through the atan2 wrap) and a further quarter: 450° total.
        s.moveTo(100.0, 0.0)
        s.moveTo(0.0, -100.0)
        s.moveTo(-100.0, 0.0)
        s.moveTo(0.0, 100.0)
        s.moveTo(100.0, 0.0)
        assertEquals(frozen + 75 * 60_000, t.displayedNow(), "one lap + 90° = +75 min")
        // Winding a quarter back down re-derives from the drag-start time: no drift.
        s.moveTo(0.0, 100.0)
        assertEquals(frozen + 60 * 60_000, t.displayedNow(), "back to the lap = +60 min exactly")
    }

    @Test fun hour12HalfTurnAddsSixHours() {
        val t = stoppedTime()
        val frozen = t.displayedNow()
        val s = HandDragSession(t, "hour12Kind", 0.0, 0.0, 0.0, 80.0)
        s.moveTo(80.0, 0.0)   // must pass through intermediate points to stay wrap-unambiguous
        s.moveTo(0.0, -80.0)
        assertEquals(frozen + 6 * 3600_000, t.displayedNow(), "half a turn of the 12-hour hand")
    }

    @Test fun counterclockwiseRunsTimeBackward() {
        val t = stoppedTime()
        val frozen = t.displayedNow()
        val s = HandDragSession(t, "secondKind", 0.0, 0.0, 0.0, 100.0)
        s.moveTo(-100.0, 0.0)   // 9 o'clock, counterclockwise: −90° = −15 s
        assertEquals(frozen - 15_000, t.displayedNow())
    }

    @Test fun dayHandSnapsToWholeCalendarDays() {
        val t = stoppedTime()
        val before = local(t)
        // The day hand maps a revolution to 31 days; +90° → round(7.75) = 8 whole days,
        // landing on the same local clock time (calendar-aware, like the advanceDay button).
        val s = HandDragSession(t, "dayKind", 0.0, 0.0, 0.0, 100.0)
        s.moveTo(100.0, 0.0)
        val after = local(t)
        assertEquals(before.plusDays(8).dayOfMonth, after.dayOfMonth)
        assertEquals(before.hour, after.hour, "same local clock time 8 days on")
        assertEquals(before.minute, after.minute)
    }

    @Test fun anchorOffsetSubdialsMeasureAboutTheirOwnCenter() {
        val t = stoppedTime()
        val frozen = t.displayedNow()
        // A subdial hand anchored at (60, -40) — Geneva's back time subdial. Angles are
        // measured about the anchor, not the watch center.
        val s = HandDragSession(t, "minuteKind", 60.0, -40.0, 60.0, -10.0)
        s.moveTo(90.0, -40.0)
        assertEquals(frozen + 15 * 60_000, t.displayedNow())
    }

    @Test fun mainTimeKindGate() {
        assertTrue(kindDragsMainTime("minuteKind"))
        assertTrue(kindDragsMainTime("reverseHour24Kind"))
        assertTrue(kindDragsMainTime("moonRAKind"))
        assertFalse(kindDragsMainTime(null), "kindless hands never drag")
        assertFalse(kindDragsMainTime("intervalMinuteKind"), "alarm hands are stage 3")
        assertFalse(kindDragsMainTime("worldtimeRingKind"), "the ring acts, not a time-set")
        assertFalse(kindDragsMainTime("latitudeTensKind"), "location hands aren't ported")
    }

    // ---- Terra worldtime ring (iOS moveWorldtimeRingForDeltaAngle) ----

    private fun ringSession(acts: MutableList<Int>): HandDragSession =
        HandDragSession(
            stoppedTime(), WORLDTIME_RING_KIND, 0.0, 0.0, 0.0, 130.0,
            ringAct = { n -> acts.add(n) },
        )

    @Test fun ringDragActsOncePerFifteenDegreeNotch() {
        val acts = mutableListOf<Int>()
        val s = ringSession(acts)
        // 10° clockwise: not yet a notch (round(10/15) = 1? no — round(0.67) = 1!). Use 5°.
        s.moveTo(130.0 * Math.sin(Math.toRadians(5.0)), 130.0 * Math.cos(Math.toRadians(5.0)))
        assertEquals(emptyList(), acts, "5° is less than half a notch")
        // 20° total: rounds to 1 notch → one act.
        s.moveTo(130.0 * Math.sin(Math.toRadians(20.0)), 130.0 * Math.cos(Math.toRadians(20.0)))
        assertEquals(listOf(1), acts)
        // 50° total: rounds to 3 notches → 2 more acts.
        s.moveTo(130.0 * Math.sin(Math.toRadians(50.0)), 130.0 * Math.cos(Math.toRadians(50.0)))
        assertEquals(listOf(1, 2), acts)
    }

    @Test fun ringDragCounterclockwiseWrapsForward() {
        val acts = mutableListOf<Int>()
        val s = ringSession(acts)
        // One notch COUNTERclockwise: iOS normalizes -1 to +23 forward acts (the action only
        // rotates one way; 23 steps land on the same slot mod 24).
        s.moveTo(130.0 * Math.sin(Math.toRadians(-16.0)), 130.0 * Math.cos(Math.toRadians(-16.0)))
        assertEquals(listOf(23), acts)
    }

    @Test fun terraAdvanceDayStepsTheSlotCityCalendar() {
        // 17:10:30Z on Jul 4 = Jul 5 in Tokyo (02:10 JST) but Jul 4 in LA (10:10 PDT).
        val t = stoppedTime()
        val env = com.plasticsmoke.beryl.expr.createDefaultEnvironment()
        registerTimeFunctions(env, t)
        registerTerraTimeFunctions(env, t)
        val before = Instant.ofEpochMilli(t.displayedNow())
        // Slot 25 = Tokyo: one day of TOKYO's calendar = +24 h (no DST) even though the
        // device zone (LA) is mid-DST-summer too; the point is the step is computed in JST.
        env.functions["advanceDayN"]!!.invoke(doubleArrayOf(25.0))
        val after = Instant.ofEpochMilli(t.displayedNow())
        assertEquals(
            before.atZone(ZoneId.of("Asia/Tokyo")).plusDays(1).toInstant(), after,
            "advanceDayN(25) advances one Tokyo calendar day",
        )
        // And advanceMonthN in a slot zone where the local date differs from the device's:
        // the watch now shows Jul 6 02:10 JST, so +1 Tokyo month = Aug 6 02:10 JST.
        env.functions["advanceMonthN"]!!.invoke(doubleArrayOf(25.0))
        val afterMonth = Instant.ofEpochMilli(t.displayedNow()).atZone(ZoneId.of("Asia/Tokyo"))
        assertEquals(8, afterMonth.monthValue)
        assertEquals(6, afterMonth.dayOfMonth)
    }
}
