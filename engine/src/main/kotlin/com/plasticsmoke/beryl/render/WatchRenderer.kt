package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.expr.Environment
import com.plasticsmoke.beryl.watch.ButtonPart
import com.plasticsmoke.beryl.watch.CalendarHeaderPart
import com.plasticsmoke.beryl.watch.CalendarRowCoverPart
import com.plasticsmoke.beryl.watch.ImagePart
import com.plasticsmoke.beryl.watch.QDayNightRingPart
import com.plasticsmoke.beryl.watch.QDialPart
import com.plasticsmoke.beryl.watch.QHandPart
import com.plasticsmoke.beryl.watch.QRectPart
import com.plasticsmoke.beryl.watch.QTextPart
import com.plasticsmoke.beryl.watch.QWedgePart
import com.plasticsmoke.beryl.watch.StaticPart
import com.plasticsmoke.beryl.watch.TerminatorPart
import com.plasticsmoke.beryl.watch.Watch
import com.plasticsmoke.beryl.watch.WatchPart
import com.plasticsmoke.beryl.watch.WheelPart
import com.plasticsmoke.beryl.watch.WindowPart

/** A stateful expression's last evaluation: the eval-grid epoch it fired in and its value. */
internal class StatefulEval(var windowId: Long, var fireIdx: Int, var value: Double)

/**
 * Per-face/mode static-run bitmap cache — the iOS architecture: parts whose rendering doesn't
 * change with time (dials, images, rects, text, static groups, their window cutouts and buttons)
 * are rasterized ONCE into device-space sprites; every subsequent frame blits the sprites and
 * evaluates/draws only the dynamic parts (hands, wheels, terminator, wedges, rings, calendar).
 *
 * One cache belongs to one (watch, env, canvas size) combination; it invalidates itself when the
 * canvas size changes and must be discarded when the env changes meaning (e.g. observer moved).
 */
class StaticCache {
    internal var sizeKey = 0L
    internal val runs = HashMap<Int, LayerSprite?>()   // null = the run drew nothing
    internal val handSprites = HashMap<QHandPart, HandSprite>()

    // Rigid-rotation sprites for the remaining per-frame rasterizers (iOS: every part is a
    // texture; only quad transforms change per frame). Both are CONTENT-KEYED — they rebake
    // when what they draw changes (the terminator's phase moves once per act during a
    // hold-repeat, not per frame) — because act-time invalidation only covers static runs.
    internal val termSprites = HashMap<com.plasticsmoke.beryl.watch.TerminatorPart, TermSprite>()
    internal val wheelSprites = HashMap<com.plasticsmoke.beryl.watch.WheelPart, WheelSprite>()

    // Terra's world-time ring sprite, kept ACROSS invalidate() and keyed by its visible
    // content (top slot + the 24 date-color states): a pusher tap invalidates the static
    // runs twice (depress + release), and re-baking the ring's 24 curved city names each
    // time (~20 ms) ate the 175 ms slide. The key changes exactly when the colors would.
    internal var ringSprite: HandSprite? = null
    internal var ringSpriteKey: String? = null

    // iOS hand-sweep animation state (see AngleAnimator.kt): per (part, slot) chased angles,
    // the real-time ms driving them this frame (-1 = snap, the test default), and whether any
    // sweep is still in progress (the app keeps rendering until it settles).
    internal val angleAnims = HashMap<Pair<com.plasticsmoke.beryl.watch.WatchPart, Int>, AngleAnim>()
    internal var animNowMs = -1L
    internal var animsActive = false
    internal var animMode = AnimMode.LIVE   // how this frame treats the sweep state
    // Stable per-session store keys (duplicate part names disambiguated by encounter order).
    internal val storeKeys = HashMap<Pair<com.plasticsmoke.beryl.watch.WatchPart, Int>, String>()
    internal val storeKeyCounts = HashMap<String, Int>()

    /** Session-lifetime last-drawn angles for this face+mode (survives LRU eviction): a
     *  revisited face resumes from here — hands sweep their drift, unchanged values hold. */
    var animStore: AngleAnimStore? = null

    /** iOS animationOverrideInterval (ChronometerAppDelegate.m:118, ECGLPart.m:499-502):
     *  while a button auto-repeats, every NEWLY-STARTED sweep is forced to finish in this
     *  many ms (¾ of the repeat interval) so the hands keep pace with the repeat cadence
     *  instead of lagging ever further behind. 0 = no override (the normal state). */
    var animOverrideMs: Long = 0

    /** Stateful (assignment-bearing) expression values with the frame they last FIRED in —
     *  re-evaluated per draw only inside the iOS beat-anchored fire windows (see
     *  angleTargetOf); the cached value is served between fires. */
    internal val statefulEvals = HashMap<com.plasticsmoke.beryl.watch.WatchPart, StatefulEval>()

    /** The watch's beatsPerSecond (set each frame by renderFrame): the iOS update scheduler
     *  anchors part fire times to the beat-ROUNDED time, which is what makes Tombstone's
     *  keyless winding gears click in 8 Hz bursts instead of spinning continuously. */
    internal var beatsPerSec = 1

    /** App-only: bake hand sprites UPRIGHT so long hands crop to thin strips and their
     *  per-frame rotated blits stay cheap (see bakeHandSprite). Tests leave this false —
     *  bake-at-current-angle keeps delta 0 and golden renders byte-exact. */
    var bakeUprightHands = false

    /** True while any hand/wheel is mid-sweep — callers should render again soon. */
    val isAnimating: Boolean get() = animsActive

    /** Debug aid: which (part, slot) sweeps are currently in flight. */
    fun animatingParts(): List<String> =
        angleAnims.entries.filter { it.value.animating }
            .map { "${it.key.first.name}#${it.key.second}" }

    fun invalidate() {
        runs.clear()
        handSprites.clear()
        termSprites.clear()
        wheelSprites.clear()
        sizeKey = 0
        // angleAnims intentionally survives: a rebake (button press) must not restart sweeps.
    }
}

/**
 * Per-part display-time sampler — the iOS beat model: each part declares an `update` interval
 * (default 1 s; Geneva's seconds tick at .1) and the runtime samples the part's expressions at
 * that cadence, floored by the watch's beat grid (1/beatsPerSecond). Implementations quantize
 * the time the environment reads to floor(frameTime/interval)·interval: a seconds hand with
 * update='.25' then TICKS four times a second even though its angle expression is continuous —
 * and Tombstone's balance-wheel sine, sampled on the 0.25 s grid, lands on its zeros and holds
 * still, exactly like the iPhone.
 */
interface BeatClock {
    /** Quantize display time to this interval's current epoch; returns the phase (0..1) of the
     *  frame instant within the epoch. */
    fun beat(intervalSeconds: Double): Double

    /** The frame's un-quantized DISPLAY time (ms), for the stateful-expression eval grid
     *  (iOS fires part evals on its own timer regardless of the beat floor); -1 if unknown. */
    fun frameTimeMs(): Long = -1
}

/** Temporary render profiler: per-part-type nanos for one frame (enable, render, snapshot). */
object RenderStats {
    @Volatile var enabled = false
    private val nanos = HashMap<String, Long>()
    private val counts = HashMap<String, Int>()

    internal fun add(type: String, ns: Long) {
        if (!enabled) return
        synchronized(nanos) {
            nanos[type] = (nanos[type] ?: 0L) + ns
            counts[type] = (counts[type] ?: 0) + 1
        }
    }

    fun snapshotAndReset(): String = synchronized(nanos) {
        val s = nanos.entries.sortedByDescending { it.value }
            .joinToString(" ") { "${it.key}=${it.value / 1000}us×${counts[it.key]}" }
        nanos.clear(); counts.clear()
        s
    }
}

private inline fun profiled(type: String, block: () -> Unit) {
    if (!RenderStats.enabled) { block(); return }
    val t0 = System.nanoTime()
    block()
    RenderStats.add(type, System.nanoTime() - t0)
}

/**
 * Renders a full watch frame — static parts and hands in document order, then the bezel —
 * mirroring renderFrame/renderPartsDocumentOrder in chronometer-web's renderer.ts.
 *
 * Hands are evaluated against [env] (which must have the time/calendar functions registered and
 * its `<init>` blocks applied via [applyInits]); calling again with a later time re-poses them.
 *
 * With a [cache], contiguous runs of static parts render once and re-blit as sprites (see
 * [StaticCache]); without one, every part renders every call (the test/preview path). Caching is
 * skipped for bezel-clipped fixture faces (layers don't inherit the face clip).
 */
fun renderFrame(
    ctx: DrawingContext,
    watch: Watch,
    env: Environment,
    scale: Double,
    canvasWidthPx: Int,
    canvasHeightPx: Int,
    images: Map<String, LoadedImage> = emptyMap(),
    cache: StaticCache? = null,
    beat: BeatClock? = null,
    /** Real-time ms driving the iOS hand-sweep animation; -1 (default) disables it (tests). */
    animateAtRealMs: Long = -1L,
    /** How this render treats the sweep state — see [AnimMode]. */
    animMode: AnimMode = AnimMode.LIVE,
) {
    ctx.save()
    ctx.translate(canvasWidthPx / 2.0, canvasHeightPx / 2.0)
    ctx.scale(scale, scale)
    clipToFace(ctx, watch)

    val effCache = cache?.takeIf { watch.bezelColor.isEmpty() }
    if (effCache != null) {
        val key = (canvasWidthPx.toLong() shl 32) or canvasHeightPx.toLong()
        if (effCache.sizeKey != key) {
            effCache.runs.clear()
            effCache.handSprites.clear()   // device-space sprites: stale at a new canvas size
            effCache.termSprites.clear()
            effCache.wheelSprites.clear()
            effCache.sizeKey = key
        }
        effCache.animNowMs = animateAtRealMs
        effCache.animsActive = false
        effCache.animMode = animMode
        effCache.beatsPerSec = watch.beatsPerSecond
    }

    val parts = watch.parts
    val pendingWindows = ArrayList<WindowPart>()
    var i = 0
    var runId = 0
    while (i < parts.size) {
        // A static run may only begin with no windows pending (pending windows would have to cut
        // into the cached sprite, coupling it to earlier frame state).
        val runEnd = if (pendingWindows.isEmpty()) staticRunEnd(parts, i) else i
        if (runEnd > i) {
            if (effCache == null) {
                drawSequence(ctx, parts, i, runEnd, env, images, pendingWindows, null, null, 0)
            } else if (effCache.runs.containsKey(runId)) {
                effCache.runs[runId]?.let { ctx.drawSprite(it) }
            } else {
                ctx.pushLayer()
                drawSequence(ctx, parts, i, runEnd, env, images, pendingWindows, null, null, 0)
                val sprite = ctx.captureLayerSprite()
                effCache.runs[runId] = sprite
                sprite?.let { ctx.drawSprite(it) }
            }
            runId++
            i = runEnd
        } else {
            drawSequence(ctx, parts, i, i + 1, env, images, pendingWindows, effCache, beat, watch.beatsPerSecond)
            i++
        }
    }
    // Leftover windows with no following part — draw their borders only.
    for (win in pendingWindows) drawWindowBorder(ctx, win, env)
    drawBezel(ctx, watch)

    ctx.restore()
}

/** True for part types whose rendering is time-independent (bakeable into a static sprite). */
private fun isStaticPart(part: WatchPart): Boolean = when (part) {
    is StaticPart, is QDialPart, is ImagePart, is QRectPart, is QTextPart -> true
    // Buttons are DYNAMIC, like iOS's independent GL parts: their depress motion
    // (thisButtonPressed/manualSet exprs) renders per frame — baking them forced a full
    // static rebake on every press AND release, whose 30-80 ms frames tore visible holes
    // in concurrent hand sweeps (Terra's pusher slide judder).
    else -> false
}

/**
 * End (exclusive) of the maximal bakeable run starting at [from]: static parts, plus window
 * clusters whose eventual target part is itself static — the cutout result is then
 * time-independent too. Returns [from] when parts[from] isn't cacheable (dynamic part, or
 * windows targeting a dynamic part). A button inside a window cluster ends the run (buttons
 * render per frame); the dynamic dispatch keeps the windows pending across it, as on iOS.
 */
private fun staticRunEnd(parts: List<WatchPart>, from: Int): Int {
    var j = from
    while (j < parts.size) {
        val p = parts[j]
        when {
            isStaticPart(p) -> j++
            p is WindowPart -> {
                var k = j
                while (k < parts.size && parts[k] is WindowPart) k++
                if (k < parts.size && isStaticPart(parts[k])) j = k + 1 else return j
            }
            else -> return j
        }
    }
    return j
}

/** Draw parts[from until until] with the standard document-order dispatch. */
private fun drawSequence(
    ctx: DrawingContext,
    parts: List<WatchPart>,
    from: Int,
    until: Int,
    env: Environment,
    images: Map<String, LoadedImage>,
    pendingWindows: MutableList<WindowPart>,
    cache: StaticCache?,
    beat: BeatClock?,
    beatsPerSecond: Int,
) {
    for (idx in from until until) {
        val part = parts[idx]
        // Sample the environment's display time at this part's effective update cadence.
        val interval = updateIntervalOf(part, env, beatsPerSecond)
        beat?.beat(interval)
        when (part) {
            is QHandPart -> profiled(if (part.special != null) "hand:${part.special}" else "hand") {
                drawQHand(ctx, part, env, images, cache, beat)
            }
            is TerminatorPart -> profiled("terminator") { renderTerminator(ctx, part, env, cache) }
            is WheelPart -> profiled("wheel") { drawWheel(ctx, part, env, cache) }
            is CalendarRowCoverPart -> drawCalendarRowCover(ctx, part, env)
            is CalendarHeaderPart -> drawCalendarHeader(ctx, part, env)
            is QWedgePart -> profiled("wedge") { drawQWedge(ctx, part, env, cache) }
            is QDayNightRingPart -> profiled("daynight") { drawQDayNightRing(ctx, part, env) }
            is WindowPart -> pendingWindows.add(part)
            // iOS ignores windows on buttons ("passed on to the next part" —
            // parserDidButtonStart); draw the button but keep the windows pending.
            // Dynamic buttons SLIDE to their motion offsets (iOS getLinear at
            // kECGLLinearAnimationSpeed) instead of popping.
            is ButtonPart -> profiled("button") { drawButton(ctx, part, env, images, cache) }
            else -> {
                if (pendingWindows.isNotEmpty()) {
                    profiled("windowed") { renderWithWindowCutouts(ctx, part, pendingWindows, env, images) }
                    pendingWindows.clear()
                } else {
                    profiled("static") { drawStaticPart(ctx, part, env, images) }
                }
            }
        }
    }
}

/**
 * A part's effective `update` interval in seconds: the declared attr (iOS default 1;
 * ECWatchDefinitionManager df:1), floored by the watch's beat grid — iOS schedules part updates
 * on the master 1/beatsPerSecond timer, so a Tombstone (4 beats) part declaring update≈8ms
 * still only samples every 0.25 s.
 */
fun updateIntervalOf(part: WatchPart, env: Environment, beatsPerSecond: Int): Double {
    val expr = when (part) {
        is QHandPart -> part.update
        is WheelPart -> part.update
        is QWedgePart -> part.update
        is TerminatorPart -> part.update
        is QDayNightRingPart -> part.update
        else -> null
    }
    val declared = expr?.let { evalAttr(it, env) }?.takeIf { it > 0 } ?: 1.0
    val floor = if (beatsPerSecond > 0) 1.0 / beatsPerSecond else 0.0
    return maxOf(declared, floor)
}

/**
 * Draw [part] into an offscreen layer, stroke the window borders, punch the holes, then composite
 * the layer over whatever was already drawn — so content beneath shows through (e.g. the moon
 * porthole). Ported from renderer.ts renderWithWindowCutouts.
 */
internal fun renderWithWindowCutouts(
    ctx: DrawingContext, part: WatchPart, windows: List<WindowPart>, env: Environment, images: Map<String, LoadedImage>,
    drawPart: () -> Unit = { drawStaticPart(ctx, part, env, images) },
) {
    ctx.pushLayer()
    drawPart()
    // iOS clearHolesForBounds (ECQView.m:130-159): windows are processed IN DOCUMENT ORDER
    // inside the SAME texture — rect: StrokeRect (full width) then ClearRect (which eats the
    // stroke's inner half); porthole: clear the circle, then stroke the ring (full width
    // survives). A later window's clear therefore ERASES earlier borders where they overlap.
    // Istanbul's am/pm pill is BUILT from that: two portholes + a bordered rect, then a final
    // borderless window clears every stroke inside the union, leaving a clean pill outline.
    for (win in windows) {
        if (win.windowType == "porthole") {
            cutWindowHole(ctx, win, env)
            drawWindowBorder(ctx, win, env)
        } else {
            drawWindowBorder(ctx, win, env)
            cutWindowHole(ctx, win, env)
        }
    }
    ctx.popLayerToParent()
    for (win in windows) drawWindowInnerShadow(ctx, win, env)
}

/**
 * Recessed inner shadow inside a window opening. iOS bakes an offset 2-D Gaussian of the window
 * frame; we reproduce it by capturing a black frame just outside the hole, blurring it, and
 * compositing it clipped to the hole interior with a slight down-right offset (light upper-left).
 */
private fun drawWindowInnerShadow(ctx: DrawingContext, win: WindowPart, env: Environment) {
    val opacity = evalAttr(win.shadowOpacity, env)
    if (opacity <= 0) return
    val x = evalAttr(win.x, env); val y = evalAttr(win.y, env)
    val w = evalAttr(win.w, env); val h = evalAttr(win.h, env)
    if (w <= 0 || h <= 0) return
    val sigmaUnits = if (win.shadowSigma != null) evalAttr(win.shadowSigma, env) else 1.0
    val scale = ctx.deviceScale()
    val sigmaDev = sigmaUnits * scale
    val margin = sigmaUnits * 4 + 2
    val off = sigmaDev * 0.6           // directional bias (down-right)

    ctx.save()
    if (win.windowType == "porthole") {
        val cx = x; val cy = -y; val r = minOf(w, h) / 2
        ctx.clipCircle(cx, cy, r)
        ctx.pushLayer()
        ctx.fillCircle(cx, cy, r + margin, INNER_SHADOW_BLACK)
        ctx.cutCircle(cx, cy, r)
        ctx.popLayerAsShadow(off, off, sigmaDev, opacity)
    } else {
        val rx = x; val ry = -(y + h)  // top-left corner, Y-down (matches cutWindowHole)
        ctx.clipRect(rx, ry, w, h)
        ctx.pushLayer()
        ctx.fillRect(rx - margin, ry - margin, w + 2 * margin, h + 2 * margin, INNER_SHADOW_BLACK)
        ctx.cutRect(rx, ry, w, h)
        ctx.popLayerAsShadow(off, off, sigmaDev, opacity)
    }
    ctx.restore()
}

private const val INNER_SHADOW_BLACK = 0xFF000000.toInt()

private fun cutWindowHole(ctx: DrawingContext, win: WindowPart, env: Environment) {
    val x = evalAttr(win.x, env); val y = evalAttr(win.y, env)
    val w = evalAttr(win.w, env); val h = evalAttr(win.h, env)
    if (w <= 0 || h <= 0) return
    if (win.windowType == "porthole") {
        ctx.cutCircle(x, -y, minOf(w, h) / 2)         // x,y is the arc center
    } else {
        ctx.cutRect(x, -(y + h), w, h)                // x,y is the corner (Y-up → Y-down)
    }
}

internal fun drawWindowBorder(ctx: DrawingContext, win: WindowPart, env: Environment) {
    val x = evalAttr(win.x, env); val y = evalAttr(win.y, env)
    val w = evalAttr(win.w, env); val h = evalAttr(win.h, env)
    // iOS defaults (ECWatchDefinitionManager parserDidWindowStart): border=2, strokeColor=black —
    // a window that omits both still gets a black frame on the iPhone (e.g. Thebes' am/pm squares,
    // which the staged XML previously patched with an explicit strokeColor).
    val border = if (win.border != null) evalAttr(win.border, env) else 2.0
    if (w <= 0 || h <= 0 || border <= 0) return
    val stroke = if (win.strokeColor != null) evalColorArgb(win.strokeColor, env) else INNER_SHADOW_BLACK
    if (isTransparentArgb(stroke)) return
    if (win.windowType == "porthole") {
        // iOS strokes the porthole ring AFTER clearing the hole (ECQView clearHolesForBounds,
        // ECHolePort) → the full border width survives, centered on the circle edge.
        ctx.strokeCircle(x, -y, minOf(w, h) / 2, stroke, border)
    } else {
        // iOS CGContextStrokeRect: an honest full-width stroke centered on the rect edge. The
        // window's own ClearRect runs right after (renderWithWindowCutouts) and eats the inner
        // half — and any LATER window's clear can eat more. Let the cuts do that work.
        val rx = x; val ry = -(y + h)
        ctx.beginPath()
        ctx.moveTo(rx, ry); ctx.lineTo(rx + w, ry)
        ctx.lineTo(rx + w, ry + h); ctx.lineTo(rx, ry + h); ctx.closePath()
        ctx.strokePath(stroke, border)
    }
}
