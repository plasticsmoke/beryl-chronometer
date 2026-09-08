package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.watch.WatchPart
import kotlin.math.PI
import kotlin.math.abs

/**
 * The iOS hand-sweep animation (ECGLPart updateAndReturnValue/getAnimatedValue, angle case):
 * every animated angle CHASES its expression target at kECGLAngleAnimationSpeed × animSpeed
 * radians/second along the closest direction, instead of snapping.
 *
 * The iOS lifecycle this reproduces: a watch SNAPS all values to their targets when its
 * archive loads (loadFromArchive ends with updateAllParts animating:false — NO wind-up), the
 * values then persist while loaded, and parts only update when DRAWN — so switching to a
 * watch sweeps exactly the drift accumulated since it was last drawn: the hands adjust in
 * well under a second, and values whose targets haven't changed (the date) hold still.
 * Steady-state ticks are shorter than a frame (kECGLFrameRate = 1/120 s) and snap.
 *
 * Here: state is CREATED already snapped to the target (the load-snap) — a peek pre-render
 * (HOLD) creates it without ever mutating existing state, standing in for iOS's load, and
 * live renders sweep the drift from the last draw. [AngleAnimStore] carries last-drawn
 * angles across LRU eviction (iOS watches stay resident). Animation is driven by REAL time
 * ([StaticCache.animNowMs]); with animNowMs < 0 (the default; render tests) everything snaps.
 */
internal class AngleAnim {
    var current = 0.0        // displayed angle; created snapped to the target (load-snap)
    var target = 0.0         // unwrapped goal: current chases it monotonically
    var animating = false
    var lastMs = 0L
    var stopMs = 0L
    var lastRaw = 0.0        // last raw (fmod'd) target, to track a moving goal continuously
}

/**
 * Session-lifetime store of last-drawn angles for ONE face+mode, surviving the face's LRU
 * eviction — iOS keeps all watches resident for the whole app run, so a revisited watch
 * resumes from where it was last drawn (hands sweep only their drift; unchanged values like
 * the date don't move). Keyed by part name + slot since parts are re-parsed on reload.
 */
class AngleAnimStore {
    internal val angles = HashMap<String, Double>()
}

/** How a render treats the sweep state (iOS allowAnimation / visibility semantics). */
enum class AnimMode {
    /** Normal visible render: angles chase their targets, state advances. */
    LIVE,

    /** Warm-up snap (iOS updateAllParts animating:false — mode flips): state jumps TO the
     *  targets, so nothing sweeps afterward. */
    SEED,

    /** Off-screen snapshot (peek pre-render): draw the LAST-DRAWN pose without touching the
     *  state — like iOS's hidden watches, which simply aren't updated. The incoming face then
     *  slides in at its stale pose and the sweep plays live after the reveal. */
    HOLD,
}

private const val TWO_PI = 2 * PI

/** iOS kECGLFrameRate: a sweep shorter than one 120 Hz frame snaps. */
private const val FRAME_MS = 1000.0 / 120

/** Max sweep advance per frame (~2 × 60 Hz vsyncs) — see the slow-frame hop clamp. */
private const val MAX_SWEEP_STEP_MS = 34L

private fun posFmod(a: Double, m: Double): Double {
    val r = a % m
    return if (r < 0) r + m else r
}

/** iOS EC_fmod at every angle evaluation (ECGLPart updateAndReturnValue): angles are reduced
 *  to 0..2π BEFORE any use. Vital for unbounded expressions — Tombstone's third wheel angle
 *  is currentTime()·2π/360 ≈ 1.4e7 rad, far beyond float rotation precision if left raw. */
internal fun fmod2pi(a: Double): Double = posFmod(a, TWO_PI)

/**
 * Advance the animated angle of ([part], [slot]) toward [targetRaw], returning the angle to
 * draw this frame. [speed] is radians/second; <= 0 snaps (iOS animSpeed 0). When the cache is
 * in seed mode (an off-screen warm-up render: peek pre-renders, mode flips — iOS's
 * allowAnimation:false updates), the state snaps TO the target so later renders only animate
 * changes from here on — a warmed watch adjusts nothing but its drifted hands.
 */
internal fun animatedAngle(
    cache: StaticCache?,
    part: WatchPart,
    slot: Int,
    targetRaw: Double,
    speed: Double,
): Double {
    val nowMs = cache?.animNowMs ?: -1L
    if (cache == null || nowMs < 0 || speed <= 0.0) return targetRaw
    val target = posFmod(targetRaw, TWO_PI)
    // Store keys must be stable across face rebuilds (parts are re-parsed): part name + slot,
    // disambiguated by encounter order for duplicate names (Haleakala has two 'am/pm' wheels).
    // Encounter order == render order == document order, so it's reproducible per session.
    val storeKey = cache.storeKeys.getOrPut(part to slot) {
        val base = "${part.name}#$slot"
        val n = (cache.storeKeyCounts[base] ?: 0) + 1
        cache.storeKeyCounts[base] = n
        "$base#$n"
    }
    if (cache.animMode == AnimMode.HOLD) {
        // Peek pre-render: existing state shows its last-drawn pose UNTOUCHED (an iOS hidden
        // watch isn't updated, so drift keeps accumulating). Absent state is CREATED snapped
        // to the target — this is iOS's load-snap (loadFromArchive → updateAllParts
        // animating:false): the reveal then sweeps the drift since this moment.
        val held = cache.angleAnims.getOrPut(part to slot) {
            AngleAnim().also {
                it.current = cache.animStore?.angles?.get(storeKey) ?: target
                cache.animStore?.angles?.put(storeKey, it.current)
            }
        }
        return held.current
    }
    val v = cache.angleAnims.getOrPut(part to slot) {
        // A revisited face resumes from its last-drawn angle (iOS residency); state that has
        // never existed snaps to the target, like iOS's load-snap — watches never wind up.
        AngleAnim().also {
            it.current = cache.animStore?.angles?.get(storeKey) ?: target
            cache.animStore?.angles?.put(storeKey, it.current)
        }
    }
    if (cache.animMode == AnimMode.SEED) {
        v.current = target
        v.animating = false
        cache.animStore?.angles?.put(storeKey, target)
        return target
    }
    if (!v.animating) {
        if (v.current == target) return target
        // Direction of a NEW sweep, decided once (sticky for its whole flight): the closest
        // path (iOS ECAnimationDirClosest — the default for every part; no face overrides
        // it). Corrections are therefore at most half a turn (~0.8 s at the base speed), and
        // near-static values that jitter backward a hair (Chandra's moon librations) make
        // invisible micro-sweeps instead of spinning the long way around.
        val forward = posFmod(target - v.current, TWO_PI)
        val delta = if (forward <= PI) forward else forward - TWO_PI
        val durMs = abs(delta) / speed * 1000.0
        if (durMs < FRAME_MS) {
            v.current = target
            cache.animStore?.angles?.put(storeKey, v.current)
            return target
        }
        v.target = v.current + delta   // unwrapped: current chases it monotonically
        v.lastRaw = target
        v.animating = true
        v.lastMs = nowMs
        v.stopMs = nowMs + durMs.toLong()
        // iOS animationOverrideInterval: a repeating button forces new sweeps to finish
        // within ¾ of the repeat interval (applied AFTER the snap check, like ECGLPart.m:499).
        if (cache.animOverrideMs > 0) v.stopMs = nowMs + cache.animOverrideMs
        cache.animsActive = true
        // iOS returns the untouched currentValue on the starting frame.
        return v.current
    }
    // In flight: track the moving raw target with small closest-path increments so the
    // unwrapped goal stays continuous (no direction flips at the 12 o'clock boundary),
    // and keep the ORIGINAL stop time (iOS lands on schedule even if the target moves).
    var d = target - v.lastRaw
    if (d > PI) d -= TWO_PI else if (d < -PI) d += TWO_PI
    v.target += d
    v.lastRaw = target
    // Slow-frame hop clamp (deliberate divergence from iOS): their linear interpolation
    // assumes uniform ~8-16 ms GL frames; a software-render hiccup (a static rebake, a
    // ring re-bake) would swallow a third of a 175 ms sweep in ONE visible hop — reads as
    // "too fast and clunky". Advance by at most ~2 vsyncs' worth and push the deadline out
    // by the remainder: constant velocity always, duration stretches only when frames do.
    val dt = nowMs - v.lastMs
    if (dt > MAX_SWEEP_STEP_MS) v.stopMs += dt - MAX_SWEEP_STEP_MS
    if (nowMs >= v.stopMs) {
        v.animating = false
        v.current = posFmod(v.target, TWO_PI)
        cache.animStore?.angles?.put(storeKey, v.current)
        return v.current
    }
    val dtEff = minOf(dt, MAX_SWEEP_STEP_MS)
    val frac = dtEff.toDouble() / (v.stopMs - v.lastMs).toDouble()
    v.current += (v.target - v.current) * frac
    v.lastMs = nowMs
    cache.animStore?.angles?.put(storeKey, posFmod(v.current, TWO_PI))
    cache.animsActive = true
    return v.current
}

/**
 * iOS linear-value chase (ECGLPart getLinear → updateAndReturnValue isAngle=false): xOffset/
 * yOffset move toward their target at kECGLLinearAnimationSpeed (80 units/s, ECConstants.h:99)
 * × animSpeed — a stem or day-indicator SLIDES to its new offset (~50-100 ms) instead of
 * popping. Same lifecycle as [animatedAngle] (create-at-target, HOLD/SEED, store), without the
 * fmod/closest wrap.
 */
internal fun animatedLinear(
    cache: StaticCache?,
    part: WatchPart,
    slot: Int,
    target: Double,
    speed: Double,
): Double {
    val nowMs = cache?.animNowMs ?: -1L
    if (cache == null || nowMs < 0 || speed <= 0.0) return target
    val storeKey = cache.storeKeys.getOrPut(part to slot) {
        val base = "${part.name}#$slot"
        val n = (cache.storeKeyCounts[base] ?: 0) + 1
        cache.storeKeyCounts[base] = n
        "$base#$n"
    }
    val v = cache.angleAnims.getOrPut(part to slot) {
        AngleAnim().also {
            it.current = cache.animStore?.angles?.get(storeKey) ?: target
            cache.animStore?.angles?.put(storeKey, it.current)
        }
    }
    if (cache.animMode == AnimMode.HOLD) return v.current
    if (cache.animMode == AnimMode.SEED) {
        v.current = target
        v.animating = false
        cache.animStore?.angles?.put(storeKey, target)
        return target
    }
    if (!v.animating) {
        if (v.current == target) return target
        val durMs = abs(target - v.current) / speed * 1000.0
        if (durMs < FRAME_MS) {
            v.current = target
            cache.animStore?.angles?.put(storeKey, v.current)
            return target
        }
        v.target = target
        v.animating = true
        v.lastMs = nowMs
        v.stopMs = nowMs + durMs.toLong()
        if (cache.animOverrideMs > 0) v.stopMs = nowMs + cache.animOverrideMs
        cache.animsActive = true
        return v.current
    }
    v.target = target   // track a moving goal; keep the ORIGINAL stop time (iOS)
    // Slow-frame hop clamp — see animatedAngle.
    val dt = nowMs - v.lastMs
    if (dt > MAX_SWEEP_STEP_MS) v.stopMs += dt - MAX_SWEEP_STEP_MS
    if (nowMs >= v.stopMs) {
        v.animating = false
        v.current = v.target
        cache.animStore?.angles?.put(storeKey, v.current)
        return v.current
    }
    val dtEff = minOf(dt, MAX_SWEEP_STEP_MS)
    val frac = dtEff.toDouble() / (v.stopMs - v.lastMs).toDouble()
    v.current += (v.target - v.current) * frac
    v.lastMs = nowMs
    cache.animStore?.angles?.put(storeKey, v.current)
    cache.animsActive = true
    return v.current
}

/** Effective linear-chase speed: kECGLLinearAnimationSpeed × animSpeed (attr default 1). */
internal fun linearSpeedOf(attr: com.plasticsmoke.beryl.expr.ASTNode?, env: com.plasticsmoke.beryl.expr.Environment): Double =
    BASE_LINEAR_SPEED * (if (attr != null) abs(evalAttr(attr, env)) else 1.0)

/** iOS kECGLLinearAnimationSpeed (ECConstants.h:99). */
private const val BASE_LINEAR_SPEED = 80.0   // face units per second

/** Button motion slide rate: kECGLLinearAnimationSpeed × the button animSpeed default (1). */
internal const val BUTTON_SLIDE_SPEED = 80.0

/** iOS kECGLAngleAnimationSpeed: the base angle-sweep rate that animSpeed multiplies. */
private const val BASE_ANGLE_SPEED = 2.0   // radians per second

/** Effective sweep speed: kECGLAngleAnimationSpeed × animSpeed (attr default 1; iOS negates
 *  a wheel's animSpeed internally to mean "don't animate linear" — angles still animate). */
internal fun animSpeedOf(attr: com.plasticsmoke.beryl.expr.ASTNode?, env: com.plasticsmoke.beryl.expr.Environment): Double =
    BASE_ANGLE_SPEED * (if (attr != null) abs(evalAttr(attr, env)) else 1.0)
