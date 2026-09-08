package com.plasticsmoke.beryl.astro

import com.plasticsmoke.beryl.expr.createDefaultEnvironment
import com.plasticsmoke.beryl.expr.evaluateExpression
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Interaction stage 3: the Olympia chronograph — three-slot stopwatch state machine ported
 * from iOS ECWatchTime stopwatch methods + ECGLWatch start/stop/rattrapante/reset.
 */
class StopwatchTest {

    private var realMs = 1_000_000_000L

    private fun newState() = StopwatchState(systemNow = { realMs })

    @Test fun startStopMeasuresElapsedRealTime() {
        val sw = newState()
        assertEquals(0.0, sw.stopwatch.reading(), 1e-9, "reset at birth")
        assertTrue(sw.stopwatch.isStopped)

        sw.startStop(0.1)
        realMs += 90_500
        assertEquals(90.5, sw.stopwatch.reading(), 1e-9)
        assertFalse(sw.stopwatch.isStopped)

        // Stopping truncates to the 0.1 s precision (iOS: floor, like a mechanical movement).
        realMs += 33
        sw.startStop(0.1)
        assertEquals(90.5, sw.stopwatch.reading(), 1e-9, "90.533 truncates to 90.5")
        realMs += 60_000
        assertEquals(90.5, sw.stopwatch.reading(), 1e-9, "stopped watch holds")

        // Restart continues from the frozen reading; lap/display ride along when not split.
        sw.startStop(0.1)
        realMs += 9_500
        assertEquals(100.0, sw.stopwatch.reading(), 1e-9)
        assertEquals(100.0, sw.lap.reading(), 1e-9)
        assertEquals(100.0, sw.display.reading(), 1e-9)
    }

    @Test fun rattrapanteSplitsAndRejoins() {
        val sw = newState()
        // iOS stopwatchRattrapanteValid: 0 only when STOPPED and riding — a fresh stopwatch.
        assertFalse(sw.rattrapanteValid, "stopped and riding: the ratt button parks")
        sw.startStop(0.1)
        realMs += 10_000
        assertTrue(sw.rattrapanteValid, "running: the split button is live")

        sw.rattrapante(0.1)   // split: the lap hand freezes at 10 s
        realMs += 5_000
        assertEquals(10.0, sw.lap.reading(), 1e-9, "split hand frozen")
        assertEquals(15.0, sw.stopwatch.reading(), 1e-9, "main hand keeps running")
        assertTrue(sw.rattrapanteValid)

        sw.rattrapante(0.1)   // rejoin: the lap hand snaps back onto the stopwatch
        assertEquals(15.0, sw.lap.reading(), 1e-9)
        realMs += 1_000
        assertEquals(sw.stopwatch.reading(), sw.lap.reading(), 1e-9, "riding again")
    }

    @Test fun resetZerosAllTimersButKeepsRunning() {
        val sw = newState()
        sw.startStop(0.1)
        realMs += 42_000
        sw.rattrapante(0.1)
        realMs += 3_000
        sw.reset()
        assertEquals(0.0, sw.stopwatch.reading(), 1e-9)
        assertEquals(0.0, sw.lap.reading(), 1e-9)
        assertFalse(sw.stopwatch.isStopped, "reset while running keeps running (iOS)")
        realMs += 2_000
        assertEquals(2.0, sw.stopwatch.reading(), 1e-9)
    }

    @Test fun leavesReadTheTimersAndPersistenceRoundTrips() {
        val sw = newState()
        val env = createDefaultEnvironment()
        registerStopwatchFunctions(env, sw)

        evaluateExpression("stopwatchStartStop(0.1)", env)
        realMs += (3 * 3600 + 4 * 60 + 45) * 1000L + 500
        assertEquals(45.5, evaluateExpression("stopwatchSecondValue()", env), 1e-9)
        assertEquals(4.0, evaluateExpression("stopwatchMinuteNumber()", env), 1e-9)
        assertEquals(3.0, evaluateExpression("stopwatchHour24Number()", env), 1e-9)
        evaluateExpression("stopwatchRattrapante(0.1)", env)   // split
        assertEquals(45.5, evaluateExpression("stopwatchLapSecondValue()", env), 1e-9)
        realMs += 2_000
        assertEquals(45.5, evaluateExpression("stopwatchLapSecondValue()", env), 1e-9)
        assertEquals(47.5, evaluateExpression("stopwatchSecondValue()", env), 1e-9)
        assertEquals(1.0, evaluateExpression("stopwatchRattrapanteValid()", env), 1e-9)

        // Persistence: warp/zero restore verbatim (zero anchors to absolute real time).
        val sw2 = newState()
        sw2.stopwatch.restore(sw.stopwatch.warp, sw.stopwatch.zero)
        assertEquals(sw.stopwatch.reading(), sw2.stopwatch.reading(), 1e-9)
        realMs += 1_000
        assertEquals(sw.stopwatch.reading(), sw2.stopwatch.reading(), 1e-9)
    }
}
