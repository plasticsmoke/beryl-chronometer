package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.expr.Environment
import com.plasticsmoke.beryl.watch.QHandPart
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws clock hands, ported from the QHand paths of chronometer-web `src/watch/renderer.ts`.
 *
 * Scope: the hand types Milano uses — `rect` (thin radial bars: retrograde calendar + power
 * hands) and `tri` (diamond hour/minute/second hands) — plus the `oCenter` center dot. The
 * `quad`/`sun`/`breguet`/`wire`/`spoke` types, ornaments, tail circles, and z-shadows are
 * deferred to later slices.
 */

private const val OPAQUE_BLACK = 0xFF000000.toInt()

/** Master switch for hand drop-shadows (the test/preview path; :app can gate this for performance). */
private const val SHADOWS_ENABLED = true

/** Diagnostics for sprite baking (println → logcat System.out). */
const val BAKE_DEBUG = false

/**
 * Draw a hand, preceded by its drop-shadow when it has a non-zero z. iOS bakes the shadow as a
 * separate blurred bitmap dropped at a FIXED screen direction (it does not rotate with the hand);
 * we reproduce that by capturing the hand's silhouette into a layer and compositing it blurred +
 * offset underneath. Opacity/sigma follow makeOneShadow.pl.
 */
/** A hand baked to sprites: content + optional shadow, plus the pose it was rasterized at. */
internal class HandSprite(
    val content: LayerSprite?,
    val shadow: LayerSprite?,
    val pivotXDev: Double,
    val pivotYDev: Double,
    val angle0: Double,
    val xm0: Double,
    val ym0: Double,
    val shadowDxDev: Double,
    val shadowDyDev: Double,
)

/**
 * True when the hand's rendering is a rigid rotation of a fixed image about (x,y) — the baked
 * sprite is then exact for any angle/motion. Terra's world-time ring IS bakeable: it rotates
 * rigidly, and its date-colored city names only change with the env (topRingSlot acts, date
 * rollovers seen at the next invalidate) — the same updateAtEnvChangeOnly cadence iOS refreshes
 * them at; live-drawing its 24 curved names cost ~13 ms/frame and halved the sweep frame rate.
 * Excluded: offset image hands (a live offsetAngle ORBITS the image about the pivot without
 * spinning it, which a rigid rotation can't reproduce).
 */
private fun spriteBakeable(part: QHandPart): Boolean =
    !(part.src != null && part.offsetRadius != null)

internal fun drawQHand(
    ctx: DrawingContext,
    part: QHandPart,
    env: Environment,
    images: Map<String, LoadedImage> = emptyMap(),
    cache: StaticCache? = null,
    beat: BeatClock? = null,
) {
    if (cache != null && spriteBakeable(part)) {
        // The world-time ring survives static invalidations via its content key (see
        // StaticCache.ringSprite); everything else re-bakes with the run cache.
        val sprite = if (part.special == "specialWorldtime") {
            val key = worldtimeRingKey(env)
            cache.ringSprite?.takeIf { cache.ringSpriteKey == key }
                ?: bakeHandSprite(ctx, part, env, images, cache).also {
                    cache.ringSprite = it
                    cache.ringSpriteKey = key
                }
        } else {
            cache.handSprites.getOrPut(part) { bakeHandSprite(ctx, part, env, images, cache) }
        }
        // iOS fmods EVERY angle evaluation to 0..2π before use (ECGLPart updateAndReturnValue
        // — see fmod2pi), then the displayed angle chases the target at animSpeed (the
        // hand-sweep): a freshly shown watch winds its hands to the time, time travel sweeps,
        // and Tombstone's escapement CLICKS — each 0.25 s target step sweeps at 2 rad/s
        // (~80 ms) then dwells until the next step. There is NO tween: the XML `animate`
        // attr sits in iOS's accepted-key whitelist but is never read.
        // Parts that update at 60 Hz or faster (Tombstone's keyless gears at 1/60, the
        // balance at updateRate/30) SNAP: on iOS their per-fire deltas are one update
        // period's worth, whose 2 rad/s sweeps finish sub-frame — visually a snap. Our
        // virtual-fire catch-up hands such parts CHUNKY multi-fire target steps per draw;
        // animating those smears each winding burst ~50 ms past its dwell, blurring the
        // click. Slower parts sweep normally.
        val updateS = part.update?.let { evalAttr(it, env) } ?: 1.0
        val speed =
            if (updateS > 0 && updateS <= FAST_UPDATE_SNAP_S) 0.0
            else animSpeedOf(part.animSpeed, env)
        val angle = animatedAngle(
            cache, part, 0, angleTargetOf(part, env, cache, beat), speed,
        )
        val scale = ctx.deviceScale()
        // Motion offsets CHASE their target at kECGLLinearAnimationSpeed × animSpeed (iOS
        // getLinear) — Babylon's day-indicator wires slide to the new cell instead of popping.
        val linSpeed = linearSpeedOf(part.animSpeed, env)
        val xm = animatedLinear(cache, part, 1, evalAttr(part.xMotion, env), linSpeed)
        val ym = animatedLinear(cache, part, 2, evalAttr(part.yMotion, env), linSpeed)
        val dx = (xm - sprite.xm0) * scale
        val dy = -(ym - sprite.ym0) * scale   // XML Y-up → device Y-down
        val delta = angle - sprite.angle0
        sprite.shadow?.let {
            ctx.drawSpriteRotated(
                it, sprite.pivotXDev + dx, sprite.pivotYDev + dy, delta,
                sprite.shadowDxDev, sprite.shadowDyDev,
            )
        }
        sprite.content?.let {
            ctx.drawSpriteRotated(it, sprite.pivotXDev + dx, sprite.pivotYDev + dy, delta)
        }
        return
    }
    drawQHandLive(ctx, part, env, images, cache)
}

/**
 * The hand's angle target this frame, fmod'd to 0..2π like every iOS angle eval.
 *
 * Assignment-bearing expressions (Tombstone's keyless `stat==1 ? tic=tic+2*pi/180 : tic`) are
 * eval-COUNT-sensitive, so they follow iOS's actual fire schedule: a part's next-update time
 * is `onEvenTime(snapped, ceil(update/0.005)·0.005)` where `snapped` is the watch time
 * ROUNDED TO THE NEAREST BEAT (latchTimeForBeatsPerSecond uses rint, ECWatchTime.m:222;
 * ECDynamicUpdate.m:141/155). Real time runs AHEAD of the rounded beat for the first half of
 * each beat period — the part then fires on every draw pass — and BEHIND for the second
 * half — no fires. On Tombstone (8 beats/s) the keyless gears therefore wind in ~50 ms
 * BURSTS with ~75 ms dwells: the 8 Hz CLICK-and-slide of the iPhone's winding train.
 *
 * Within a fire window, iOS fires once per draw — 120 Hz on the reference iPhone. Measured
 * from its screen recording (docs/, Tombstone back, beatsPerSecond=4 → 250 ms cycles):
 * ~4 clicks/s, ~30° per click (~13-14 fires × 2° per ~110 ms window), ~130 ms slide +
 * ~130 ms dwell, one gear revolution ≈ 3 s. We fire on a fixed virtual 120 Hz
 * [STATEFUL_FIRE_STEP_MS] sub-grid instead (catching up per draw), which reproduces that
 * rate INDEPENDENT of our draw cadence. Pure expressions are idempotent under the
 * beat-quantized clock and evaluate every frame.
 */
internal fun angleTargetOf(part: QHandPart, env: Environment, cache: StaticCache, beat: BeatClock?): Double {
    val expr = part.angle ?: return 0.0
    val frameMs = beat?.frameTimeMs() ?: -1L
    if (frameMs < 0 || !part.angleHasAssignment) return fmod2pi(evalAttr(expr, env))
    val raw = part.update?.let { evalAttr(it, env) }?.takeIf { it > 0 } ?: 1.0
    val gridMs = (kotlin.math.ceil(raw / 0.005) * 5.0).coerceAtLeast(5.0)
    val beatMs = 1000.0 / cache.beatsPerSec.coerceAtLeast(1)
    val snapped = Math.round(frameMs / beatMs) * beatMs
    var nextUpdate = kotlin.math.floor(snapped / gridMs) * gridMs
    if (nextUpdate <= snapped) nextUpdate += gridMs
    // Virtual fire slots at STATEFUL_FIRE_STEP_MS from the window's first fire; -1 = none yet.
    val fireIdx =
        if (frameMs <= nextUpdate) -1
        else ((frameMs - nextUpdate) / STATEFUL_FIRE_STEP_MS).toInt()
    val windowId = Math.round(snapped)
    var c = cache.statefulEvals[part]
    if (c == null) {
        // First-ever evaluation: one fire, aligned to the current slot.
        c = StatefulEval(windowId, fireIdx, fmod2pi(evalAttr(expr, env)))
        cache.statefulEvals[part] = c
        return c.value
    }
    // Fires due this draw: new slots in THIS window (no cross-window catch-up — iOS parts
    // don't fire while the face isn't being drawn).
    val fires =
        if (c.windowId == windowId) fireIdx - c.fireIdx
        else fireIdx + 1
    if (fires <= 0) {
        if (c.windowId != windowId) { c.windowId = windowId; c.fireIdx = fireIdx }
        return c.value
    }
    var v = c.value
    repeat(fires) { v = fmod2pi(evalAttr(expr, env)) }
    c.windowId = windowId
    c.fireIdx = fireIdx
    c.value = v
    return v
}

/** Virtual fire cadence inside a stateful part's fire window: one eval per 120 Hz draw,
 *  like the reference iPhone — Tombstone's keyless winds ~30°/click at 4 Hz, rev ≈ 3 s. */
private const val STATEFUL_FIRE_STEP_MS = 1000.0 / 120

/** Update intervals at/above 60 Hz mean iOS's sweeps for the part are sub-frame: snap. */
private const val FAST_UPDATE_SNAP_S = 1.0 / 60 + 1e-9

/** Rasterize the hand at its CURRENT pose into sprites; per-frame draws rotate by the delta. */
private fun bakeHandSprite(
    ctx: DrawingContext, part: QHandPart, env: Environment, images: Map<String, LoadedImage>,
    cache: StaticCache? = null,
): HandSprite {
    // fmod like every angle eval (iOS EC_fmod): the per-frame delta then stays within ±2π —
    // raw unbounded angles (Tombstone third wheel ~1.4e7 rad) would exceed the float
    // precision of the device rotation matrix (ulp ≈ 64° at 8e8 degrees: frozen, then jumps).
    val rawAngle0 = fmod2pi(evalAttr(part.angle, env))
    val xm0 = evalAttr(part.xMotion, env)
    val ym0 = evalAttr(part.yMotion, env)
    val px = evalAttr(part.x, env) + xm0
    val py = -(evalAttr(part.y, env) + ym0)
    val pivot = ctx.devicePoint(px, py)

    // App path (bakeUprightHands): counter-rotate the bake so the content rasterizes UPRIGHT
    // and the crop box is the hand's thin strip, not the near-square box of a diagonal pose —
    // the per-frame rotated blit rasterizes only the strip's mapped quad (~5-20× fewer
    // pixels for long hands; the seconds hand alone was 2.3 ms/frame). Every moving hand is
    // resampled from its bake pose anyway (iOS likewise GPU-rotates a fixed texture); tests
    // keep bake-at-current-angle so delta stays 0 and goldens stay byte-exact.
    val upright = cache?.bakeUprightHands == true
    val angle0 = if (upright) 0.0 else rawAngle0
    ctx.pushLayer()
    if (upright) {
        ctx.save()
        ctx.translate(px, py)
        ctx.rotate(-rawAngle0)
        ctx.translate(-px, -py)
    }
    drawHandContent(ctx, part, env, images)
    if (upright) ctx.restore()
    val content = ctx.captureLayerSprite()
    if (BAKE_DEBUG) {
        println(
            "bake ${part.name}: " + (content?.let { "(${it.x},${it.y}) ${it.image.width}x${it.image.height}" } ?: "EMPTY"),
        )
    }
    if (content == null) return HandSprite(null, null, pivot[0], pivot[1], angle0, xm0, ym0, 0.0, 0.0)

    val z = evalAttr(part.z, env)
    var shadow: LayerSprite? = null
    var shadowDx = 0.0
    var shadowDy = 0.0
    if (SHADOWS_ENABLED && z != 0.0) {
        val thick = if (part.thick != null) evalAttr(part.thick, env) else 3.0
        var opacity = 0.5
        var sigma = (z + 2) / 2
        if (thick < 3.0) { sigma *= thick / 3.0; opacity += 0.5 * (3.0 - thick) / 3.0 }
        val scale = ctx.deviceScale()
        // iOS offset magnitudes z/4.3 (x) and z/2.15 (y), dropped in a fixed screen direction (does
        // not rotate with the hand). Direction set to the conventional down-right (light upper-left).
        shadowDx = (z / 4.3) * scale
        shadowDy = (z / 2.15) * scale
        shadow = ctx.makeShadowSprite(content, sigma * scale, opacity)
    }
    return HandSprite(content, shadow, pivot[0], pivot[1], angle0, xm0, ym0, shadowDx, shadowDy)
}

/** The uncached path: shadow re-blurred and content re-rasterized every call. [animCache]
 *  carries the hand-sweep state for the per-frame live hands (orbit image hands); the BAKE
 *  path passes null so sprites always rasterize at the raw target pose. */
private fun drawQHandLive(ctx: DrawingContext, part: QHandPart, env: Environment, images: Map<String, LoadedImage>, animCache: StaticCache? = null) {
    val z = evalAttr(part.z, env)
    if (SHADOWS_ENABLED && z != 0.0) {
        val thick = if (part.thick != null) evalAttr(part.thick, env) else 3.0
        var opacity = 0.5
        var sigma = (z + 2) / 2
        if (thick < 3.0) { sigma *= thick / 3.0; opacity += 0.5 * (3.0 - thick) / 3.0 }
        val scale = ctx.deviceScale()
        // iOS offset magnitudes z/4.3 (x) and z/2.15 (y), dropped in a fixed screen direction (does
        // not rotate with the hand). Direction set to the conventional down-right (light upper-left).
        ctx.pushLayer()
        drawHandContent(ctx, part, env, images, animCache)
        ctx.popLayerAsShadow((z / 4.3) * scale, (z / 2.15) * scale, sigma * scale, opacity)
    }
    drawHandContent(ctx, part, env, images, animCache)
}

private fun drawHandContent(ctx: DrawingContext, part: QHandPart, env: Environment, images: Map<String, LoadedImage>, animCache: StaticCache? = null) {
    // Terra back subdials: the city name curved around each subdial (the 'specialSubdial' marker
    // hand, which otherwise is an alpha=0 image placeholder).
    if (part.special == "specialSubdial") {
        drawSpecialSubdial(ctx, part, env)
        return
    }
    // Terra front: the rotating 24-city world-time ring (image + date-colored city names).
    if (part.special == "specialWorldtime") {
        drawSpecialWorldtime(ctx, part, env, images, animCache)
        return
    }
    // Image-based hand (e.g. the moon disc, seasons marker).
    if (part.src != null) {
        drawImageHand(ctx, part, env, images, animCache)
        return
    }
    // 'spoke': a text label parked at an offset that rotates in/out of a window (Terra back AM/PM).
    if (part.handType == "spoke") {
        drawSpokeHand(ctx, part, env)
        return
    }
    // 'gear' (Tombstone skeleton movement): procedural gear (teeth + rim + spokes + hub + pinion).
    // It uses tipRadius/rim*/hub/leaf attrs and has no `length`, so handle it before the length gate.
    if (part.handType == "gear") {
        drawGearHand(ctx, part, env, images)
        return
    }

    // xMotion/yMotion are settable/animated position offsets in XML (Y-up) coords; for a static
    // pose they carry the resolved position (e.g. Babylon's day-indicator wires box the current day).
    val x = evalAttr(part.x, env) + evalAttr(part.xMotion, env)
    val y = -(evalAttr(part.y, env) + evalAttr(part.yMotion, env))  // XML Y-up → Y-down
    val angle = fmod2pi(evalAttr(part.angle, env))
    val length = evalAttr(part.length, env)
    if (length <= 0) return

    val width = evalAttr(part.width, env)
    // iOS defaults tail to ceil(length/10) when absent (a small counterweight stub); only an
    // explicit tail='0' suppresses it. The web port defaulted to 0, dropping the stub on ~240 hands.
    val tail = if (part.tail != null) evalAttr(part.tail, env) else ceil(length / 10)
    val handType = part.handType ?: "tri"

    val strokeColor = if (part.strokeColor != null) evalColorArgb(part.strokeColor, env) else OPAQUE_BLACK
    val fillColor = if (part.fillColor != null) evalColorArgb(part.fillColor, env) else OPAQUE_BLACK
    // iOS ECHandLineWidthFill=0.25 / ECHandLineWidthOutline=1.0 (ECQView.m:1654): an unset lineWidth
    // defaults by whether the hand is filled or outline-only (fillColor='clear'). The web port's flat
    // 0.5 was wrong both ways — filled hands' outlines 2× thick, outline-only hands half-width.
    val defaultLineWidth = if (isTransparentArgb(fillColor)) 1.0 else 0.25
    val lineWidth = evalAttr(part.lineWidth, env).let { if (it == 0.0) defaultLineWidth else it }
    val oTail = evalAttr(part.oTail, env)
    val length2 = evalAttr(part.length2, env)

    ctx.save()
    ctx.translate(x, y)
    ctx.rotate(angle)

    // 'sun'/'sun2' draw a rayed sun disc and skip the ornament/tail/center-dot extras. 'sun2' makes
    // every ray equal length (vs 'sun', whose first ray is longer).
    if (handType == "sun" || handType == "sun2") {
        drawSunHandBody(ctx, part, env, length, length2, evalAttr(part.oCenter, env), strokeColor, fillColor, lineWidth, handType == "sun2")
        ctx.restore()
        return
    }
    // 'quad' draws two Bézier-curved tapered blades with a filled arrow tip (e.g. Chandra hands).
    if (handType == "quad") {
        drawQuadHandBody(ctx, part, env, length, strokeColor, fillColor, lineWidth)
        ctx.restore()
        return
    }
    // 'breguet' draws the classic pomme/moon hand: hub + tapered arm + open crescent + tip.
    if (handType == "breguet") {
        drawBreguetHandBody(ctx, length, width, strokeColor, fillColor, lineWidth)
        ctx.restore()
        return
    }
    // 'rise'/'set' draw a partial-sun limb arc peeking over a horizon (Mauna Kea moonrise/moonset).
    if (handType == "rise" || handType == "set") {
        drawRiseSetHandBody(ctx, length, length2, width, handType == "rise", strokeColor, fillColor)
        ctx.restore()
        return
    }

    val oRadius = evalAttr(part.oRadius, env)
    drawHandShape(ctx, handType, length, width, tail, strokeColor, fillColor, lineWidth, oTail, length2, oRadius)

    // Arrowhead ornament extending beyond the tip (e.g. the Geneva hour/minute arrows).
    val oLength = evalAttr(part.oLength, env)
    if (oLength > 0) {
        val oWidth = evalAttr(part.oWidth, env).let { if (it == 0.0) width else it }
        // iOS oLineWidth default is the same fill/outline conditional (ECQView.m:1664), NOT lineWidth.
        val oLineWidth = evalAttr(part.oLineWidth, env).let { if (it == 0.0) defaultLineWidth else it }
        val oStroke = if (part.oStrokeColor != null) evalColorArgb(part.oStrokeColor, env) else strokeColor
        val oFill = if (part.oFillColor != null) evalColorArgb(part.oFillColor, env) else fillColor
        drawHandOrnament(ctx, length, oLength, oWidth, oTail, oLineWidth, oStroke, oFill)
    }

    // Tail circle (e.g. the day-pointer hub).
    if (oRadius > 0) {
        // iOS tLineWidth defaults to the resolved oLineWidth (ECQView.m:1665) = attr or the conditional.
        val tLW = evalAttr(part.tLineWidth, env).let { if (it != 0.0) it else evalAttr(part.oLineWidth, env).let { o -> if (o != 0.0) o else defaultLineWidth } }
        val tSC = when {
            part.tStrokeColor != null -> evalColorArgb(part.tStrokeColor, env)
            part.oStrokeColor != null -> evalColorArgb(part.oStrokeColor, env)
            else -> strokeColor
        }
        val tFC = when {
            part.tFillColor != null -> evalColorArgb(part.tFillColor, env)
            part.oFillColor != null -> evalColorArgb(part.oFillColor, env)
            else -> fillColor
        }
        val oRadiusX = if (part.oRadiusX != null) evalAttr(part.oRadiusX, env) else oRadius
        drawTailCircle(ctx, tail, oRadius, oRadiusX, tLW, tSC, tFC)
    }

    // Center dot (e.g. the second hand's counterweight hub).
    val oCenter = evalAttr(part.oCenter, env)
    if (oCenter > 0) {
        val dotColor = if (part.oStrokeColor != null) evalColorArgb(part.oStrokeColor, env) else strokeColor
        ctx.fillCircle(0.0, 0.0, oCenter, dotColor)
    }

    ctx.restore()
}

/** Kite/arrowhead ornament extending beyond the hand tip. Ported from renderer.ts drawHandOrnament. */
private fun drawHandOrnament(
    ctx: DrawingContext, length: Double, oLength: Double, oWidth: Double,
    oTail: Double, oLineWidth: Double, oStrokeColor: Int, oFillColor: Int,
) {
    val baseY = -(length - oLineWidth * 3)
    val tipY = -(length - oLineWidth * 3 + oLength)
    val innerY = -(length - oTail)

    ctx.beginPath()
    ctx.moveTo(0.0, tipY)
    ctx.lineTo(oWidth / 2, baseY)
    ctx.lineTo(0.0, innerY)
    ctx.lineTo(-oWidth / 2, baseY)
    ctx.closePath()
    if (!isTransparentArgb(oFillColor)) ctx.fillPath(oFillColor)
    if (!isTransparentArgb(oStrokeColor)) ctx.strokePath(oStrokeColor, oLineWidth)

    // Sharp extra tip.
    if (oLineWidth > 0) {
        ctx.beginPath()
        ctx.moveTo(oLineWidth / 2, -(length + oLength - oLineWidth * 3))
        ctx.lineTo(0.0, -(length + oLength))
        ctx.lineTo(-oLineWidth / 2, -(length + oLength - oLineWidth * 3))
        ctx.closePath()
        if (!isTransparentArgb(oStrokeColor)) ctx.fillPath(oStrokeColor)
    }
}

/** Two Bézier-curved blades + a filled arrow tip (the 'quad' hand). Ported from drawQuadHandBody. */
private fun drawQuadHandBody(
    ctx: DrawingContext, part: QHandPart, env: Environment,
    length: Double, strokeColor: Int, fillColor: Int, lineWidth: Double,
) {
    val oLength = evalAttr(part.oLength, env)
    val oWidth = evalAttr(part.oWidth, env)
    val oCenter = evalAttr(part.oCenter, env)
    val oStroke = if (part.oStrokeColor != null) evalColorArgb(part.oStrokeColor, env) else strokeColor

    // The two tapered blades (each a quadratic curve from the hub out to the tip width).
    ctx.beginPath()
    ctx.moveTo(-oWidth / 12, -oCenter)
    ctx.quadraticCurveTo(-oWidth * 0.45, -oLength / 2, -oWidth / 4, -oLength)
    ctx.moveTo(oWidth / 4, -oLength)
    ctx.quadraticCurveTo(oWidth * 0.45, -oLength / 2, oWidth / 12, -oCenter)
    if (!isTransparentArgb(fillColor)) ctx.fillPath(fillColor)
    if (!isTransparentArgb(strokeColor)) ctx.strokePath(strokeColor, lineWidth)

    // The filled arrowhead at the tip (in the ornament stroke color).
    ctx.beginPath()
    ctx.moveTo(oWidth / 4 + lineWidth / 2, -oLength)
    ctx.lineTo(0.0, -length)
    ctx.lineTo(-oWidth / 4 - lineWidth / 2, -oLength)
    ctx.lineTo(-oWidth / 4 + lineWidth / 2, -oLength)
    ctx.lineTo(0.0, -oLength * 1.2)
    ctx.lineTo(oWidth / 4 - lineWidth / 2, -oLength)
    ctx.closePath()
    if (!isTransparentArgb(oStroke)) ctx.fillPath(oStroke)

    if (oCenter > 0) ctx.fillCircle(0.0, 0.0, oCenter, oStroke)
}

/**
 * Partial-sun "limb" arc peeking over a horizon — the moonrise/moonset indicator hands (Mauna Kea
 * back). Ported from ECQView.m ECQHandRise/ECQHandSet: length2..length is the radial extent of the
 * visible disc and width/2 is how far it peeks; the arc curves to the right ('rise') or left ('set').
 */
private fun drawRiseSetHandBody(
    ctx: DrawingContext, length: Double, length2: Double, width: Double, rise: Boolean, strokeColor: Int, fillColor: Int,
) {
    if (width <= 0) return
    val halfHeight = (length - length2) / 2
    val arcRadius = ((width / 2) * (width / 2) + halfHeight * halfHeight) / width
    val theta = asin((halfHeight / arcRadius).coerceIn(-1.0, 1.0))
    val cyIos = length2 + halfHeight
    val cx = if (rise) width / 2 - arcRadius else arcRadius - width / 2
    val a0 = if (rise) -theta else PI - theta
    val a1 = if (rise) theta else PI + theta
    val steps = 24
    ctx.beginPath()
    for (k in 0..steps) {
        val a = a0 + (a1 - a0) * k / steps
        val px = cx + arcRadius * cos(a)
        val py = -(cyIos + arcRadius * sin(a))   // iOS y-up → our y-down
        if (k == 0) ctx.moveTo(px, py) else ctx.lineTo(px, py)
    }
    if (!isTransparentArgb(fillColor)) ctx.fillPath(fillColor)
    if (!isTransparentArgb(strokeColor)) ctx.strokePath(strokeColor, 0.25)
}

/**
 * 'spoke' hand: a text label pivoted at [offsetAngle] and parked [offsetRadius] out, kept upright
 * (unless orientation='radial'). The AM/PM indicators swing the active label into the window and the
 * other out of frame. Ported from chronometer-web drawQHand spoke path.
 */
private fun drawSpokeHand(ctx: DrawingContext, part: QHandPart, env: Environment) {
    val text = part.text ?: return
    val x = evalAttr(part.x, env); val y = -evalAttr(part.y, env)
    val angle = fmod2pi(evalAttr(part.angle, env))   // iOS fmods every angle eval
    val offsetRadius = evalAttr(part.offsetRadius, env)
    val offsetAngle = fmod2pi(evalAttr(part.offsetAngle, env))
    val fontSize = evalAttr(part.fontSize, env).let { if (it <= 0.0) 8.0 else it }
    val fontName = part.fontName ?: "Arial"
    // iOS fills spoke text with fcolor (fillColor), not strokeColor — so AM and PM are both black
    // (the web's use of strokeColor rendered PM white). strokeColor is the unused text outline here.
    val color = if (part.fillColor != null) evalColorArgb(part.fillColor, env) else OPAQUE_BLACK
    if (isTransparentArgb(color)) return
    ctx.save()
    ctx.translate(x, y)
    ctx.rotate(angle + offsetAngle)
    ctx.translate(0.0, -offsetRadius)
    // iOS draws the spoke label with drawText:inRect: inside the hand's rotated frame (ECQView case
    // ECQHandSpoke) — there is NO counter-rotation, so the glyph is always radial (rotates with the
    // spoke around the dial center, matching the front QDial orientation='radial' zodiac ring). The
    // web port kept spoke text upright; iOS is canonical here. Terra's AM/PM stays upright because its
    // active indicator sits at offsetAngle=0 (radial == upright); the inactive one swings to ±pi/2 out
    // of the window. Kyoto's back zodiac/numeral spokes now angle around the dial like the front.
    // iOS centers the text in a rect (-w/2,-h/2,w,h) on the spoke origin; the baseline lands at
    // textVisualCenterY, with no extra vertical offset (the web port added +1).
    ctx.setFont(fontName, fontSize)
    ctx.fillText(text, 0.0, ctx.textVisualCenterY(), color)
    ctx.restore()
}

/**
 * Terra front world-time ring — the rotating 24-city ring. Draws the ring background image then each
 * city's name curved around it, colored by whether that city's calendar date is the same as / ahead
 * of / behind the top city's (black / teal / dark-red). Ported from chronometer-web's
 * drawTerraRingWithKnockouts: the web punches text holes through the ring image to reveal date-color
 * wedges beneath; drawing the names directly in the computed color is the equivalent result.
 */
/** Everything that changes the ring's baked pixels: the top slot plus each city's
 *  ahead/behind date-color state (the same signals iOS refreshes the names on). */
private fun worldtimeRingKey(env: Environment): String {
    val top = (env.variables["topRingSlot"] ?: 5.0)
    val more = env.functions["moreDay"]
    val less = env.functions["lessDay"]
    val sb = StringBuilder().append(top.toInt()).append(':')
    for (i in 0 until 24) {
        val slot = (5 + i).toDouble()
        sb.append(
            when {
                more?.invoke(doubleArrayOf(slot, top)) != 0.0 -> '+'
                less?.invoke(doubleArrayOf(slot, top)) != 0.0 -> '-'
                else -> '.'
            }
        )
    }
    return sb.toString()
}

private fun drawSpecialWorldtime(
    ctx: DrawingContext,
    part: QHandPart,
    env: Environment,
    images: Map<String, LoadedImage>,
    animCache: StaticCache? = null,
) {
    // The ring CHASES its target like every live hand (iOS updateAndReturnValue): a pusher
    // step slides the cities one 15° notch at aSpeed (0.75 → 1.5 rad/s ≈ 175 ms), instead
    // of snapping. Evaluating the angle directly bypassed the sweep entirely.
    val angle = animatedAngle(
        animCache, part, 0, fmod2pi(evalAttr(part.angle, env)), animSpeedOf(part.animSpeed, env),
    )
    val top = env.variables["topRingSlot"]?.toInt() ?: 5
    val moreFn = env.functions["moreDay"]
    val lessFn = env.functions["lessDay"]
    val moreColor = 0xFF005050.toInt(); val lessColor = 0xFF600000.toInt(); val black = 0xFF000000.toInt()

    ctx.save()
    ctx.rotate(angle)

    val loaded = part.src?.let { images[it] }
    if (loaded != null) {
        val w = loaded.image.width * loaded.scale; val h = loaded.image.height * loaded.scale
        ctx.drawImage(loaded.image, -w / 2, -h / 2, w, h, 1.0)
    }

    // City names in the two staggered tracks (even slots inner, odd slots outer). The LIVE iOS
    // constants (ECGLPart.m:1804-1817): Arial 10, cityRad1=129 (even), cityRad2=142.5 (odd) —
    // ground truth for this exact ring image (same 4× asset). (An earlier port used values
    // tuned against the web knockout — too far in and too small.)
    ctx.setFont("Arial", 10.0)
    // iOS anchors each glyph's BASELINE at radius − fontBBoxHeight, ascending OUTWARD
    // (ECGLPart.m:1636-1638: point.y = radius − fontHeight from CGFontGetFontBBox) — the
    // whole name sits INSIDE the radius. Centering the glyph ON the radius pushed every
    // city ~9 units out into the case ring. iOS also letter-spaces ring glyphs by 0.5 px.
    val fontH = ctx.fontBBoxHeight()
    val spacing = 0.5
    for (i in 0 until 24) {
        val slot = 5 + i
        val city = com.plasticsmoke.beryl.astro.terraCityForSlot(slot) ?: continue
        val color = when {
            moreFn != null && moreFn(doubleArrayOf(slot.toDouble(), top.toDouble())) != 0.0 -> moreColor
            lessFn != null && lessFn(doubleArrayOf(slot.toDouble(), top.toDouble())) != 0.0 -> lessColor
            else -> black
        }
        val radius = if (i % 2 == 0) 129.0 else 142.5
        val name = city.name
        val widths = name.map { ctx.measureTextWidth(it.toString()) }
        val total = (widths.sum() + spacing * (name.length - 1)) / radius
        var charAngle = i * PI / 12 - total / 2
        for (c in name.indices) {
            val cw = widths[c] / radius
            ctx.save()
            ctx.rotate(charAngle + cw / 2)
            ctx.translate(0.0, -radius)
            ctx.fillText(name[c].toString(), 0.0, fontH, color)
            ctx.restore()
            charAngle += cw + spacing / radius
        }
    }

    // DST channel arcs: each city's groove shows the range its DST dot can occupy — a solid arc
    // over the standard..DST offset span (DST cities), or a dashed 1-sector arc (non-DST). iOS
    // constants (ECGLPart.m:1815-1816, 1719-1725): channelRad1=111.5 (even), channelRad2=126
    // (odd), line width 1.0 (CG default at zoom 1); the dashed variant is a {2,3} px CG dash
    // pattern drawn at 50% alpha over the full sector.
    val lowFn = env.functions["dstLowHoursN"]; val highFn = env.functions["dstHighHoursN"]
    if (lowFn != null && highFn != null) {
        val dashColor = 0x80000000.toInt()
        for (i in 0 until 24) {
            val slot = 5 + i
            val low = lowFn(doubleArrayOf(slot.toDouble())); val high = highFn(doubleArrayOf(slot.toDouble()))
            if (low.isNaN()) continue
            val channelR = if (i % 2 == 0) 111.5 else 126.0
            if (high != low) {
                ctx.strokeArc(0.0, 0.0, channelR, (4.5 + low) * PI / 12, (4.5 + high) * PI / 12, black, 1.0)
            } else {
                // Dashed 1-sector arc: 2 px on / 3 px off along the arc, half-alpha.
                val a0 = (i - 6.5) * PI / 12; val a1 = (i - 5.5) * PI / 12
                val onA = 2.0 / channelR; val offA = 3.0 / channelR
                var s = a0
                while (s < a1) {
                    val e = minOf(s + onA, a1)
                    ctx.strokeArc(0.0, 0.0, channelR, s, e, dashColor, 1.0)
                    s = e + offA
                }
            }
        }
    }
    ctx.restore()
}

/**
 * Terra subdial city label — the city name curved around the bottom of a back subdial. Ported from
 * ECGLPart.m ECPartSpecialSubdial (the city-name portion). specialParam 0 = the large local dial;
 * 1–3 = the small dials. The slot city comes from the part's envSlot.
 */
private fun drawSpecialSubdial(ctx: DrawingContext, part: QHandPart, env: Environment) {
    val slot = if (part.envSlot != null) evalAttr(part.envSlot, env).toInt() else return
    val city = com.plasticsmoke.beryl.astro.terraCityForSlot(slot) ?: return
    val param = if (part.specialParam != null) evalAttr(part.specialParam, env).toInt() else -1
    val large = param == 0
    val cx = evalAttr(part.x, env); val cy = -evalAttr(part.y, env)
    // LIVE iOS constants (ECGLPart.m:1832-1855): Verdana 12 for BOTH subdial sizes, textRadius
    // 84 (large) / 58 (small). The source comments beside them show the arithmetic 80.5/54.5 —
    // an earlier port transcribed the COMMENT values instead of the code.
    val fontSize = 12.0
    val textRadius = if (large) 84.0 else 58.0
    val color = 0xFF000000.toInt()

    ctx.setFont("Verdana", fontSize)
    val chars = city.name.map { it.toString() }
    val widths = chars.map { ctx.measureTextWidth(it) }
    // iOS drawCircularText's upside-down branch (bottom labels reading upright) anchors the
    // BASELINE at radius − fontBBoxHeight/2 with the ascent toward the CENTER
    // (ECGLPart.m:1626-1631: point.y = −radius + fontHeight/2), letter-spaced 1.0 px.
    val fontH = ctx.fontBBoxHeight()
    val spacing = 1.0
    val total = (widths.sum() + spacing * (chars.size - 1)) / textRadius
    val thCenter = PI / 2   // centered at the bottom (iOS centerAngle = π), reading upright
    ctx.save()
    ctx.translate(cx, cy)
    var cur = thCenter + total / 2
    for (i in chars.indices) {
        val dw = widths[i] / textRadius
        cur -= dw / 2
        if (chars[i] != " ") {
            ctx.save()
            ctx.translate(textRadius * cos(cur), textRadius * sin(cur))
            ctx.rotate(cur + PI / 2 + PI)
            ctx.fillText(chars[i], 0.0, -fontH / 2, color)
            ctx.restore()
        }
        cur -= dw / 2 + spacing / textRadius
    }

    // 24-hour reference numbers around the subdial: even hours as numbers, odd as dots. Skip the
    // hours overlapping the city name (near the bottom) and the per-specialParam overlap sets where
    // adjacent subdials collide. Ported from ECPartSpecialSubdial.
    val numberRadius = if (large) 72.0 else 46.0
    val numFS = if (large) 9.0 else 8.0
    val totalTextAngle = total + 10.0 / textRadius
    ctx.setFont("Arial", numFS)
    val numTvcy = ctx.textVisualCenterY()
    for (i in 1..23) {
        val skip = when (param) {
            0 -> i == 15 || i == 16
            2 -> i in 9..11
            3 -> i in 8..10 || i in 13..15
            else -> false
        }
        if (skip) continue
        val angDistBottom = PI * (if (i < 12) i else 24 - i) / 12
        if (angDistBottom <= totalTextAngle / 2) continue
        val pointAngle = PI * (18 - i) / 12
        val px = numberRadius * cos(pointAngle); val py = -numberRadius * sin(pointAngle)
        if (i % 2 == 1) ctx.fillCircle(px, py, 0.6, 0x80000000.toInt())
        else ctx.fillText(i.toString(), px, py + numTvcy, color)
    }
    ctx.restore()
}

/** Rayed sun disc (e.g. the back's sun-position marker). Ported from renderer.ts drawSunHandBody. */
private fun drawSunHandBody(
    ctx: DrawingContext, part: QHandPart, env: Environment,
    length: Double, length2: Double, oCenter: Double,
    strokeColor: Int, fillColor: Int, lineWidth: Double, sun2: Boolean = false,
) {
    val nRays = evalAttr(part.nRays, env).toInt().let { if (it <= 0) 8 else it }
    val raysRad = (length - length2) / 3
    // 'sun' makes the first ray longer ((length-length2)/2); 'sun2' makes all rays equal (raysRad).
    var rayRad = if (sun2) raysRad else (length - length2) / 2
    val cen = length2 + raysRad  // iOS uses raysRad (the web's shadow-cache path wrongly used rayRad)
    val sunCenter = if (oCenter > 0) oCenter else raysRad / 2

    ctx.beginPath()
    for (i in 0 until nRays) {
        val theta = PI / 2 + 2 * PI * i / nRays
        val farX = rayRad * cos(theta); val farY = cen + rayRad * sin(theta)
        val cwX = sunCenter * cos(theta + PI / nRays); val cwY = cen + sunCenter * sin(theta + PI / nRays)
        val ccwX = sunCenter * cos(theta - PI / nRays); val ccwY = cen + sunCenter * sin(theta - PI / nRays)
        ctx.moveTo(farX, -farY)
        ctx.lineTo(cwX, -cwY)
        ctx.lineTo(ccwX, -ccwY)
        ctx.lineTo(farX, -farY)
        rayRad = raysRad
    }
    if (!isTransparentArgb(fillColor)) ctx.fillPath(fillColor)
    if (!isTransparentArgb(strokeColor)) ctx.strokePath(strokeColor, lineWidth)

    // Central disc (drawn in the fill color for both fill and stroke, per the reference).
    if (!isTransparentArgb(fillColor)) {
        ctx.fillCircle(0.0, -cen, sunCenter, fillColor)
        ctx.strokeCircle(0.0, -cen, sunCenter, fillColor, lineWidth)
    }
}

/**
 * Filled/stroked ellipse at the hand tail (e.g. the date-day indicator ring). Ported from
 * renderer.ts drawTailCircle, but honoring [oRadiusX] for the oval shape (the web simplified it to a
 * circle; the iPhone draws an ellipse, oRadiusX wide × oRadius tall).
 */
private fun drawTailCircle(ctx: DrawingContext, tail: Double, oRadius: Double, oRadiusX: Double, lineWidth: Double, strokeColor: Int, fillColor: Int) {
    val cy = tail + oRadius
    if (!isTransparentArgb(fillColor)) ctx.fillEllipse(0.0, cy, oRadiusX, oRadius, fillColor)
    if (!isTransparentArgb(strokeColor)) ctx.strokeEllipse(0.0, cy, oRadiusX, oRadius, strokeColor, lineWidth)
}

/**
 * Image hand: centered, anchored (pivot at xAnchor/yAnchor), or orbiting (offsetRadius/offsetAngle).
 * Ported from renderer.ts drawImageHand. The orbit case (e.g. the Moon's off-center anchor) places
 * the image at polar (offsetRadius, offsetAngle) then rotates by `angle` about its anchor.
 */
private fun drawImageHand(ctx: DrawingContext, part: QHandPart, env: Environment, images: Map<String, LoadedImage>, animCache: StaticCache? = null) {
    val loaded = images[part.src] ?: return
    val alpha = if (part.alpha != null) evalAttr(part.alpha, env) else 1.0
    if (alpha <= 0) return
    val x = evalAttr(part.x, env)
    val y = -evalAttr(part.y, env)
    var angle = fmod2pi(evalAttr(part.angle, env))
    val offsetRadius = evalAttr(part.offsetRadius, env)
    var offsetAngle = fmod2pi(evalAttr(part.offsetAngle, env))
    val drawW = loaded.image.width * loaded.scale
    val drawH = loaded.image.height * loaded.scale

    ctx.save()
    ctx.translate(x, y)

    if (offsetRadius > 0) {
        // Orbiting image hands render live every frame (never sprite-baked), so the iOS
        // hand-sweep applies here: Miami's planet icons swing around the selector ring.
        val speed = animSpeedOf(part.animSpeed, env)
        offsetAngle = animatedAngle(animCache, part, 1, offsetAngle, speed)
        angle = animatedAngle(animCache, part, 0, angle, speed)
        val xAnchor = if (part.xAnchor != null) evalAttr(part.xAnchor, env) else drawW / 2
        val yAnchor = if (part.yAnchor != null) -evalAttr(part.yAnchor, env) else -drawH / 2
        ctx.rotate(offsetAngle)
        ctx.translate(0.0, -offsetRadius)
        ctx.rotate(angle)
        ctx.drawImage(loaded.image, -xAnchor, -yAnchor - drawH, drawW, drawH, alpha)
    } else if (part.xAnchor != null || part.yAnchor != null) {
        if (angle != 0.0) ctx.rotate(angle)
        val xAnchor = if (part.xAnchor != null) evalAttr(part.xAnchor, env) else drawW / 2
        val yAnchor = if (part.yAnchor != null) -evalAttr(part.yAnchor, env) else -drawH / 2
        ctx.drawImage(loaded.image, -xAnchor, -yAnchor - drawH, drawW, drawH, alpha)
    } else {
        if (angle != 0.0) ctx.rotate(angle)
        ctx.drawImage(loaded.image, -drawW / 2, -drawH / 2, drawW, drawH, alpha)
    }
    ctx.restore()
}

/**
 * Procedural gear (Tombstone skeleton movement), ported from ECQView.m ECQHandGear. A toothed rim
 * annulus with N spokes to a hub, plus a central pinion of N leaves. The gear body uses fillColor;
 * the pinion (leaves + center) uses oFillColor. iOS's stroke-color state machine: teeth/spokes set
 * stroke=fillColor, so once teeth are drawn the rim/hub also stroke in fillColor (a toothless gear
 * keeps strokeColor for its rim). An optional `overlay` image (e.g. the mainspring blender) is
 * stamped over the gear and rotates with it.
 */
private fun drawGearHand(ctx: DrawingContext, part: QHandPart, env: Environment, images: Map<String, LoadedImage>) {
    val tip = evalAttr(part.tipRadius, env)
    if (tip <= 0) return
    // iOS parser defaults (ECWatchDefinitionManager parserDidQHandStart, gear branch): absent radii
    // default to fractions of tipRadius — e.g. the balance wheel omits hubRadius/leafRadius, and the
    // iPhone still draws its 0.2·tip hub with the 0.075·tip dark-red pinion center inside it.
    val rimOuter = if (part.rimOuterRadius != null) evalAttr(part.rimOuterRadius, env) else tip * 0.9
    val rimInner = if (part.rimInnerRadius != null) evalAttr(part.rimInnerRadius, env) else tip * 0.8
    val hub = if (part.hubRadius != null) evalAttr(part.hubRadius, env) else tip * 0.2
    val leaf = if (part.leafRadius != null) evalAttr(part.leafRadius, env) else tip * 0.15
    val nTeeth = (if (part.nTeeth != null) evalAttr(part.nTeeth, env) else 100.0).toInt()
    val nLeaves = (if (part.nLeaves != null) evalAttr(part.nLeaves, env) else 10.0).toInt()
    val nSpokes = (if (part.nSpokes != null) evalAttr(part.nSpokes, env) else 3.0).toInt()
    val fill = if (part.fillColor != null) evalColorArgb(part.fillColor, env) else OPAQUE_BLACK
    val oFill = if (part.oFillColor != null) evalColorArgb(part.oFillColor, env) else OPAQUE_BLACK
    // iOS ECHandLineWidthFill = 0.25 (filled hands) / ECHandLineWidthOutline = 1.0 (outline-only).
    val lineWidth = evalAttr(part.lineWidth, env).let {
        if (it == 0.0) (if (isTransparentArgb(fill)) 1.0 else 0.25) else it
    }
    // The overlay (blender) is luminosity-blended over the gear INSIDE the gear's own texture on iOS
    // (drawn during part archiving), so it recolors only gear pixels — draw the gear into a layer,
    // blend the overlay there, then composite the layer over the scene.
    val overlayImg = part.overlay?.let { images[it] }

    ctx.save()
    ctx.translate(evalAttr(part.x, env), -evalAttr(part.y, env))
    ctx.rotate(fmod2pi(evalAttr(part.angle, env)))
    if (overlayImg != null) ctx.pushLayer()

    var curStroke = if (part.strokeColor != null) evalColorArgb(part.strokeColor, env) else OPAQUE_BLACK

    // teeth: a trapezoidal tooth at the top of the rim, repeated around (one accumulated path)
    if (nTeeth > 0 && rimOuter > 0) {
        curStroke = fill
        val pitch = 2 * PI * rimOuter / nTeeth
        val ab = (tip - rimOuter) / 2
        val ao = pitch / 4
        val local = doubleArrayOf(
            -ao * 2, rimOuter,        -ao, rimOuter,
            -ao, rimOuter + ab,       -ao / 2, rimOuter + ab * 2,
            ao / 2, rimOuter + ab * 2, ao, rimOuter + ab,
            ao, rimOuter,            ao * 2, rimOuter,
        )
        ctx.beginPath()
        for (i in 0 until nTeeth) {
            val th = 2 * PI * i / nTeeth
            val c = cos(th); val s = sin(th)
            for (k in 0 until 8) {
                val px = local[k * 2]; val py = local[k * 2 + 1]
                val rx = px * c - py * s; val ry = px * s + py * c
                if (k == 0) ctx.moveTo(rx, ry) else ctx.lineTo(rx, ry)
            }
        }
        ctx.fillPath(fill); ctx.strokePath(curStroke, lineWidth)
    }

    // rim: filled annulus between rimInner and rimOuter, both edges stroked
    if (rimInner > 0 && rimOuter > 0) {
        ctx.fillRing(0.0, 0.0, rimOuter + lineWidth, rimInner, fill)
        ctx.strokeCircle(0.0, 0.0, rimOuter + lineWidth, curStroke, lineWidth)
        ctx.strokeCircle(0.0, 0.0, rimInner, curStroke, lineWidth)
    }

    // iOS (ECQView gear case) never RESETS the stroke width between elements — the residual
    // width from the last CGContextSetLineWidth call fattens everything after it. Track it.
    var curWidth = lineWidth

    // spokes: thick strokes from hub to rim
    if (nSpokes > 0) {
        curWidth = (tip - rimInner) * 0.8
        ctx.beginPath()
        for (i in 0 until nSpokes) {
            val th = 2 * PI * i / nSpokes
            ctx.moveTo(hub * cos(th), hub * sin(th))
            ctx.lineTo(rimInner * cos(th), rimInner * sin(th))
        }
        ctx.strokePath(fill, curWidth)
        curStroke = fill
    }

    // hub — stroked at the RESIDUAL width (the spoke width when spokes exist), per iOS.
    if (hub > 0) {
        ctx.fillCircle(0.0, 0.0, hub, fill)
        ctx.strokeCircle(0.0, 0.0, hub, curStroke, curWidth)
    }

    // leaves (pinion teeth): a single dot for nLeaves==1, else thick spokes out to leafRadius
    if (nLeaves == 1) {
        curWidth = 2.0
        // iOS puts the lone leaf dot at local (0, +leafRadius) in its Y-UP hand frame
        // (ECQView.m:1846); our hand frame is Y-DOWN (tips at −y), so the local y negates —
        // copied verbatim it landed 180° opposite (visible on Tombstone's balance wheel,
        // whose parked pose puts the dot on a fixed side of the red center).
        ctx.beginPath(); ctx.moveTo(0.0, -leaf); ctx.lineTo(0.0, -(leaf - 2.0)); ctx.strokePath(oFill, curWidth)
    } else if (nLeaves > 1) {
        curWidth = PI * leaf / 2 / nLeaves
        ctx.beginPath()
        for (i in 0 until nLeaves) {
            val th = 2 * PI * i / nLeaves
            ctx.moveTo(leaf * cos(th), leaf * sin(th))
            ctx.lineTo(leaf / 2 * cos(th), leaf / 2 * sin(th))
        }
        ctx.strokePath(oFill, curWidth)
    }

    // pinion center + tiny black arbor mark. The center circle strokes at the residual width —
    // for a leafless gear (Tombstone balance / thirdWheel-b) that's the SPOKE width, which is
    // what makes the iPhone's solid red dots visibly fatter than leafRadius/2.
    if (leaf > 0) {
        ctx.fillCircle(0.0, 0.0, leaf / 2, oFill)
        ctx.strokeCircle(0.0, 0.0, leaf / 2, oFill, curWidth)
    }
    ctx.beginPath(); ctx.moveTo(-1.0, 0.0); ctx.lineTo(1.0, 0.0); ctx.strokePath(OPAQUE_BLACK, 2.0)

    // Overlay image (e.g. the mainspring blender): iOS stretches it over the gear's full view square
    // (2·tip+1) and draws with kCGBlendModeLuminosity — the gear keeps its gold hue/saturation and
    // takes the overlay's brushed-gray luminance (ECQView.m:2134). Rotates with the gear.
    if (overlayImg != null) {
        val side = tip * 2 + 1
        ctx.drawImageLuminosity(overlayImg.image, -side / 2, -side / 2, side, side)
        ctx.popLayerToParent()
    }
    ctx.restore()
}

private fun drawHandShape(
    ctx: DrawingContext,
    handType: String,
    length: Double,
    width: Double,
    tail: Double,
    strokeColor: Int,
    fillColor: Int,
    lineWidth: Double,
    oTail: Double,
    length2: Double,
    oRadius: Double,
) {
    ctx.beginPath()
    val hw = width / 2

    when (handType) {
        "wire" -> {
            // Single straight line from length2 to length (iOS -width/2 offset preserved).
            ctx.moveTo(-hw, -length2)
            ctx.lineTo(-hw, -(length - (if (oTail < 0) oTail else 0.0)))
            if (!isTransparentArgb(strokeColor)) ctx.strokePath(strokeColor, lineWidth)
            return
        }
        "rect" -> {
            // Rectangle from length2→length, or tail→length when no length2.
            if (length2 > 0) {
                rectPath(ctx, -hw, -length2, width, -(length - length2))
            } else {
                rectPath(ctx, -hw, tail, width, -(length + tail - oTail))
            }
        }
        else -> {
            // 'tri' (default), iOS ECQHandTri (ECQView.m:1935-1950) — ALWAYS the 4-point
            // diamond: widest at length2 (the pivot when length2=0), tip at length (extended
            // only by a NEGATIVE oTail), and the counterweight tapering back to a POINT at
            // length2−tail (a ±0.5 flat notch instead when an oRadius tail circle is set).
            // The web port's flat-based 3-point triangle for length2=0 was an invention.
            ctx.moveTo(-hw, -length2)
            ctx.lineTo(0.0, -(length - (if (oTail < 0) oTail else 0.0)))
            ctx.lineTo(hw, -length2)
            if (oRadius == 0.0) {
                ctx.lineTo(0.0, tail - length2)
            } else {
                ctx.lineTo(0.5, tail - length2)
                ctx.lineTo(-0.5, tail - length2)
            }
            ctx.closePath()
        }
    }

    if (!isTransparentArgb(fillColor)) ctx.fillPath(fillColor)
    if (!isTransparentArgb(strokeColor)) ctx.strokePath(strokeColor, lineWidth)
}

/**
 * Breguet "pomme"/moon hand body — hub circle, tapered inner arm, an open crescent ring near the
 * tip, and a small triangular point beyond it. Ported from chronometer-web `drawBreguetHandBody`.
 * The crescent is two offset circles filled with opposite winding (non-zero rule → a ring/crescent).
 */
private fun drawBreguetHandBody(
    ctx: DrawingContext, length: Double, width: Double,
    strokeColor: Int, fillColor: Int, lineWidth: Double,
) {
    val widthScaler    = width / (length * 0.16)
    val lengthScaler   = (length - 81) / 10
    val armWidth       = length * 0.04  * widthScaler
    val centerRadius   = length * 0.08  * widthScaler
    val breOuterCenter = length * 0.71  + lengthScaler
    val breInnerCenter = length * 0.725 + lengthScaler * 0.8
    val breOuterRadius = length * 0.075 * widthScaler
    val breInnerRadius = length * 0.05  * widthScaler
    val breBase        = breOuterCenter - breOuterRadius
    val tipBase        = breOuterCenter + breOuterRadius
    val tipWidth       = length * 0.045 * widthScaler

    val fill = !isTransparentArgb(fillColor)
    val stroke = !isTransparentArgb(strokeColor)

    // Hub circle
    if (fill) ctx.fillCircle(0.0, 0.0, centerRadius, fillColor)
    if (stroke) ctx.strokeCircle(0.0, 0.0, centerRadius, strokeColor, lineWidth)

    // Inner arm trapezoid (hub → crescent base)
    ctx.beginPath()
    ctx.moveTo(-armWidth / 2, -centerRadius)
    ctx.lineTo(-armWidth / 10, -breBase)
    ctx.lineTo(armWidth / 10, -breBase)
    ctx.lineTo(armWidth / 2, -centerRadius)
    ctx.closePath()
    if (fill) ctx.fillPath(fillColor)
    if (stroke) ctx.strokePath(strokeColor, lineWidth)

    // Breguet pomme — outer circle minus the (offset) inner circle. Opposite winding makes the hole.
    ctx.beginPath()
    addCircleSubpath(ctx, 0.0, -breOuterCenter, breOuterRadius, clockwise = false)
    addCircleSubpath(ctx, 0.0, -breInnerCenter, breInnerRadius, clockwise = true)
    if (fill) ctx.fillPath(fillColor)
    if (stroke) {
        ctx.strokeCircle(0.0, -breOuterCenter, breOuterRadius, strokeColor, lineWidth)
        ctx.strokeCircle(0.0, -breInnerCenter, breInnerRadius, strokeColor, lineWidth)
    }

    // Triangular tip
    ctx.beginPath()
    ctx.moveTo(-tipWidth / 2, -tipBase)
    ctx.lineTo(0.0, -length)
    ctx.lineTo(tipWidth / 2, -tipBase)
    ctx.closePath()
    if (fill) ctx.fillPath(fillColor)
    if (stroke) ctx.strokePath(strokeColor, lineWidth)
}

/** Append a closed circle (polygon-approximated) to the current path, in the given winding. */
private fun addCircleSubpath(ctx: DrawingContext, cx: Double, cy: Double, r: Double, clockwise: Boolean, segments: Int = 48) {
    val dir = if (clockwise) -1.0 else 1.0
    for (i in 0..segments) {
        val t = dir * 2 * Math.PI * i / segments
        val x = cx + r * cos(t); val y = cy + r * sin(t)
        if (i == 0) ctx.moveTo(x, y) else ctx.lineTo(x, y)
    }
    ctx.closePath()
}

/** Build a rectangle as a closed path (h may be negative). */
private fun rectPath(ctx: DrawingContext, x: Double, y: Double, w: Double, h: Double) {
    ctx.moveTo(x, y)
    ctx.lineTo(x + w, y)
    ctx.lineTo(x + w, y + h)
    ctx.lineTo(x, y + h)
    ctx.closePath()
}
