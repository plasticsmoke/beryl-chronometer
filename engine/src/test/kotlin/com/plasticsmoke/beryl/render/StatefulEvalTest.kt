package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.expr.createDefaultEnvironment
import com.plasticsmoke.beryl.expr.parse
import com.plasticsmoke.beryl.watch.QHandPart
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iOS fire schedule for assignment-bearing expressions (Tombstone's keyless winding
 * gears, `stat==1 ? tic=tic+2*pi/180 : tic`): a part's next update anchors to the watch time
 * ROUNDED to the nearest beat (latchTimeForBeatsPerSecond rint + ECDynamicUpdate onEvenTime),
 * so evals fire per draw during the HALF-beat where real time leads the rounded beat, and
 * pause during the trailing half — 8 Hz burst-and-dwell clicking on Tombstone, not a spin.
 */
class StatefulEvalTest {

    private class FixedBeat(var ms: Long) : BeatClock {
        override fun beat(intervalSeconds: Double): Double = 0.0
        override fun frameTimeMs(): Long = ms
    }

    @Test fun keylessGearFiresInBeatAnchoredBursts() {
        val env = createDefaultEnvironment()
        env.variables["stat"] = 1.0
        env.variables["tic"] = 0.0
        val part = QHandPart(
            name = "keyless",
            angle = parse("stat==1 ? tic=tic+2*pi/180 : tic"),
            update = parse("1/60"),
        )
        val cache = StaticCache()
        cache.beatsPerSec = 4   // Tombstone

        val beat = FixedBeat(0)
        var changes = 0
        var last = Double.NaN
        // Simulate 1 s of 60 fps draws; beat period 250 ms.
        var quietRun = 0
        var maxQuietRun = 0
        var t = 0L
        while (t < 1000) {
            beat.ms = t
            val v = angleTargetOf(part, env, cache, beat)
            if (v != last) { changes++; quietRun = 0 } else { quietRun++; maxQuietRun = maxOf(maxQuietRun, quietRun) }
            last = v
            t += 16
        }
        // Moves only in the leading half of each beat: roughly half the frames, never all.
        assertTrue(changes in 15..45, "expected burst-gated motion, got $changes of ~62 frames")
        // And real dwells: ~130 ms of stillness each 250 ms cycle (~8 frames at 60 fps).
        assertTrue(maxQuietRun >= 5, "expected multi-frame dwells between bursts, got $maxQuietRun")
        // Virtual 120 Hz fire slots: ~13-14 evals × 2° per ~110 ms window, 4 windows/s —
        // ~26-28° per click, ~110°/s, one revolution ≈ 3 s: the measured iPhone rate.
        val ticDeg = Math.toDegrees(env.variables["tic"]!!)
        assertTrue(ticDeg in 90.0..130.0, "expected ~110°/s wind rate, got $ticDeg°/s")
    }

    @Test fun quietHalfServesTheCachedValueWithoutEvaluating() {
        val env = createDefaultEnvironment()
        env.variables["stat"] = 1.0
        env.variables["tic"] = 0.0
        val part = QHandPart(
            name = "keyless",
            angle = parse("stat==1 ? tic=tic+2*pi/180 : tic"),
            update = parse("1/60"),
        )
        val cache = StaticCache()
        cache.beatsPerSec = 8
        val beat = FixedBeat(70)   // leading half of the 0..125 beat window (snapped=125? no: round(70/125)=1 → snapped 125 > 70 → QUIET)
        val v0 = angleTargetOf(part, env, cache, beat)   // first-ever eval always fires
        beat.ms = 80
        val v1 = angleTargetOf(part, env, cache, beat)
        assertEquals(v0, v1, 0.0, "quiet half: cached value, no re-eval")
        assertEquals(2.0 * PI / 180, env.variables["tic"]!!, 1e-9, "tic advanced only once")
        // Cross into the next fire window (snapped 125, fires when t > 140).
        beat.ms = 141
        angleTargetOf(part, env, cache, beat)
        assertEquals(2 * 2.0 * PI / 180, env.variables["tic"]!!, 1e-9, "fired once in the window")
    }
}
