package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.watch.QHandPart
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The iOS hand-sweep port (ECGLPart updateAndReturnValue/getAnimatedValue, angle case,
 * ECAnimationDirClosest): displayed angles chase their targets at animSpeed rad/s from an
 * initial 0 (hands wind from 12:00 on first display). Mid-sweep sampling steps in realistic
 * 16 ms render frames — the live loop re-renders every frame while animating, and the
 * slow-frame hop clamp deliberately stretches single coarse jumps (see its own test).
 */
class AngleAnimatorTest {

    private val part = QHandPart(name = "test hand")

    /** Advance in 16 ms render frames up to [toMs], returning the last displayed angle. */
    private fun stepTo(cache: StaticCache, toMs: Long, target: Double, speed: Double): Double {
        var v = 0.0
        var t = cache.animNowMs
        while (t < toMs) {
            t = minOf(t + 16, toMs)
            cache.animNowMs = t
            v = animatedAngle(cache, part, 0, target, speed)
        }
        return v
    }

    @Test fun createsSnappedThenSweepsDrift() {
        val cache = StaticCache()

        // First-ever evaluation CREATES the state snapped to the target — iOS's load-snap
        // (loadFromArchive → updateAllParts animating:false). Watches never wind up.
        cache.animNowMs = 1_000
        assertEquals(1.0, animatedAngle(cache, part, 0, 1.0, 1.0), 1e-9)
        assertFalse(cache.isAnimating)

        // A later jump (drift while hidden, time travel) sweeps: start frame shows the OLD
        // angle, then it chases at the given rad/s and lands on schedule.
        cache.animNowMs = 61_000
        assertEquals(1.0, animatedAngle(cache, part, 0, 1.0 + PI / 2, 1.0), 1e-9)
        assertTrue(cache.isAnimating)
        assertEquals(1.0 + PI / 4, stepTo(cache, 61_000 + 785, 1.0 + PI / 2, 1.0), 0.05)
        assertEquals(1.0 + PI / 2, stepTo(cache, 61_000 + 1_700, 1.0 + PI / 2, 1.0), 1e-9)
        // A settled value no longer reports animating (each frame resets the flag first).
        cache.animsActive = false
        assertEquals(1.0 + PI / 2, animatedAngle(cache, part, 0, 1.0 + PI / 2, 1.0), 1e-9)
        assertFalse(cache.isAnimating)
    }

    @Test fun slowFrameClampsTheHopAndExtendsTheDeadline() {
        // Deliberate divergence from iOS: an 80 ms frame (a static rebake) must NOT swallow
        // half a sweep in one visible hop — the advance caps at ~2 vsyncs' worth and the
        // sweep finishes correspondingly later, at constant velocity throughout.
        val cache = StaticCache()
        cache.animNowMs = 0
        animatedAngle(cache, part, 0, 1.0, 400.0)              // create snapped at 1.0
        cache.animNowMs = 10_000
        animatedAngle(cache, part, 0, 1.0 + PI / 2, 2.0)       // start: a 785 ms sweep
        cache.animNowMs = 10_080                               // an 80 ms hiccup frame
        val v = animatedAngle(cache, part, 0, 1.0 + PI / 2, 2.0)
        val nominalPerMs = (PI / 2) / 785.0
        assertTrue(
            v - 1.0 <= nominalPerMs * 40,
            "80 ms frame advanced ${v - 1.0} rad — should be capped near 34 ms worth",
        )
        // Still lands exactly on target, just a touch later than the nominal deadline.
        assertEquals(1.0 + PI / 2, stepTo(cache, 11_100, 1.0 + PI / 2, 2.0), 1e-9)
    }

    @Test fun closestDirectionCrossesTheWrap() {
        val cache = StaticCache()
        cache.animNowMs = 0
        // Create snapped at 0.1 rad.
        animatedAngle(cache, part, 0, 0.1, 100.0)
        // Target just below 2π: the shortest path is BACKWARD 0.2 rad, not forward 6.08.
        cache.animNowMs = 10
        animatedAngle(cache, part, 0, 2 * PI - 0.1, 1.0)   // start frame
        val v = stepTo(cache, 110, 2 * PI - 0.1, 1.0)
        // Mid-sweep (100 of 200 ms): halfway back = crossing 12 o'clock (≡ 0 mod 2π).
        val m = ((v % (2 * PI)) + 2 * PI) % (2 * PI)
        assertTrue(m < 0.11 || m > 2 * PI - 0.11, "moving backward through 12 o'clock, got $v")
        val landed = stepTo(cache, 250, 2 * PI - 0.1, 1.0)
        assertEquals(2 * PI - 0.1, landed, 1e-9)
    }

    @Test fun microJitterSnapsInsteadOfSweeping() {
        // Chandra's moon target computed 0.009 rad BEHIND its stored angle (libration wobble).
        // iOS DirClosest: a 0.009 rad closest sweep at 2 rad/s is 4.5 ms < one 120 Hz frame →
        // SNAP. The moon must never visibly move on a face switch.
        val cache = StaticCache()
        cache.animNowMs = 0
        animatedAngle(cache, part, 0, 6.242, 400.0)   // create snapped
        cache.animNowMs = 90_000
        assertEquals(6.233, animatedAngle(cache, part, 0, 6.233, 2.0), 1e-9, "snapped, no sweep")
        assertFalse(cache.isAnimating)
    }

    @Test fun seedRenderWarmsWithoutSweeping() {
        val cache = StaticCache()
        // A warm-up render (mode flip — iOS updateAllParts animating:false): snaps TO target.
        cache.animNowMs = 1_000
        cache.animMode = AnimMode.SEED
        assertEquals(2.0, animatedAngle(cache, part, 0, 2.0, 2.0), 1e-9)
        assertFalse(cache.isAnimating)
        // A later live render animates only the drift accumulated since the seed.
        cache.animMode = AnimMode.LIVE
        cache.animNowMs = 61_000
        val v = animatedAngle(cache, part, 0, 2.1, 2.0)   // 0.1 rad of drift → 50 ms sweep
        assertEquals(2.0, v, 1e-9, "start frame shows the seeded angle")
        assertTrue(cache.isAnimating)
        assertEquals(2.1, stepTo(cache, 61_100, 2.1, 2.0), 1e-9, "landed after 50 ms")
    }

    @Test fun holdSnapshotsAndStoreSurvivesRebuild() {
        val store = AngleAnimStore()
        val cache = StaticCache().also { it.animStore = store }
        // Live render settles at 1.0 (writes through to the session store).
        cache.animNowMs = 0
        animatedAngle(cache, part, 0, 1.0, 400.0)   // fast → snaps
        // HOLD (peek pre-render): shows the last-drawn pose, does NOT chase the new target.
        cache.animMode = AnimMode.HOLD
        cache.animNowMs = 60_000
        assertEquals(1.0, animatedAngle(cache, part, 0, 1.5, 2.0), 1e-9, "peek holds the stale pose")
        assertFalse(cache.isAnimating)
        // A REBUILT face (fresh cache, same store — LRU eviction) resumes from the store and
        // sweeps only the drift, not from 12:00.
        val cache2 = StaticCache().also { it.animStore = store }
        cache2.animNowMs = 120_000
        assertEquals(1.0, animatedAngle(cache2, part, 0, 1.5, 2.0), 1e-9, "resumes at last-drawn angle")
        assertTrue(cache2.isAnimating)
        var v2 = 0.0
        var t2 = 120_000L
        while (t2 < 120_260) {
            t2 += 13
            cache2.animNowMs = t2
            v2 = animatedAngle(cache2, part, 0, 1.5, 2.0)
        }
        assertEquals(1.5, v2, 1e-9, "0.5 rad at 2 rad/s = 250 ms")
    }

    @Test fun steadyTicksAndDisabledModesSnap() {
        val cache = StaticCache()
        cache.animNowMs = 0
        animatedAngle(cache, part, 0, 1.0, 200.0)   // 5 ms sweep < one 120 Hz frame → snap
        assertFalse(cache.isAnimating)
        // A tick smaller than one 120 Hz frame at this speed snaps (steady-state seconds hand).
        cache.animNowMs = 200
        cache.animsActive = false   // renderFrame resets this each frame
        assertEquals(1.005, animatedAngle(cache, part, 0, 1.005, 1.0), 1e-9)
        assertFalse(cache.isAnimating)
        // Speed 0 (and iOS's negative no-animate hack) snaps.
        cache.animNowMs = 400
        assertEquals(2.0, animatedAngle(cache, part, 0, 2.0, 0.0), 1e-9)
        // Animation disabled (tests): raw target straight through, even un-normalized.
        cache.animNowMs = -1
        assertEquals(7.0, animatedAngle(cache, part, 0, 7.0, 1.0), 1e-9)
    }
}
