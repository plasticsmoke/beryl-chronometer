package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.expr.Environment
import com.plasticsmoke.beryl.watch.QDayNightRingPart
import com.plasticsmoke.beryl.watch.QWedgePart
import com.plasticsmoke.beryl.watch.WheelPart
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * QWheel (rotating text wheel) and QWedge (filled annular sector), ported from
 * chronometer-web `src/watch/renderer.ts` drawWheel/drawQWedge. Scope: the QWheel text + circular
 * background and the QWedge sector — the variants Geneva uses. TWheel half-and-half, calendar
 * wheels, and wheel tick marks are deferred (not used by the current faces).
 */

private const val WW_BLACK = 0xFF000000.toInt()

internal fun drawWheel(ctx: DrawingContext, part: WheelPart, env: Environment, animCache: StaticCache? = null) {
    if (part.calendar != null) { drawCalendarWheel(ctx, part, env); return }   // Babylon grid calendar

    val x = evalAttr(part.x, env)
    val y = -evalAttr(part.y, env)
    val radius = evalAttr(part.radius, env)
    if (radius <= 0) return
    val labels = part.text?.split(',') ?: return
    val n = labels.size
    if (n == 0) return

    // iOS hand-sweep applies to wheels too: Miami's planet-name wheel spins to the selection,
    // Istanbul's AM/PM flips, Thebes' note wheel rolls between states (animSpeed 2-3 in XML).
    // Angles are fmod'd to 0..2π at eval like every iOS angle (ECGLPart updateAndReturnValue).
    val angle = animatedAngle(animCache, part, 0, fmod2pi(evalAttr(part.angle, env)), animSpeedOf(part.animSpeed, env))
    val fontSize = evalAttr(part.fontSize, env).let { if (it == 0.0) 12.0 else it }
    val fontName = part.fontName ?: "Arial"
    val strokeColor = if (part.strokeColor != null) evalColorArgb(part.strokeColor, env) else WW_BLACK
    val bg = evalColorArgb(part.bgColor, env)
    val orientation = (part.orientation ?: "twelve").lowercase()

    // A full-circle wheel is a rigid rotation of fixed content — bake it once and rotate the
    // sprite per frame (iOS: the wheel is a texture; re-laying its glyphs each frame cost
    // ~4 ms on Geneva's AM/PM wheels alone). Excluded: partial-arc wheels (labels counter-
    // rotate to stay upright — not rigid) and halfAndHalf dials (fillTextDifference blends
    // against the pixels UNDER the wheel, which a pre-baked sprite doesn't see).
    val partialArc = part.angle1 != null || part.angle2 != null
    val halfHalf = part.halfAndHalf != null && evalAttr(part.halfAndHalf, env) != 0.0
    if (animCache != null && !partialArc && !halfHalf) {
        val key = WheelKey(x, y, evalAttr(part.radius, env), fontSize, strokeColor, bg,
            if (part.bgColor2 != null) evalColorArgb(part.bgColor2, env) else bg,
            if (part.tradius != null) evalAttr(part.tradius, env) else 0.0)
        var sprite = animCache.wheelSprites[part]
        if (sprite == null || sprite.key != key) {
            val pivot = ctx.devicePoint(x, y)
            ctx.pushLayer()
            drawWheelBody(ctx, part, env, x, y, angle, fontSize, fontName, strokeColor, bg, orientation)
            sprite = WheelSprite(ctx.captureLayerSprite(), pivot[0], pivot[1], angle, key)
            animCache.wheelSprites[part] = sprite
        }
        sprite.content?.let {
            ctx.drawSpriteRotated(it, sprite.pivotX, sprite.pivotY, angle - sprite.angle0)
        }
        return
    }
    drawWheelBody(ctx, part, env, x, y, angle, fontSize, fontName, strokeColor, bg, orientation)
}

/** Content pixels a wheel sprite depends on besides its bake angle (labels/font/variant are
 *  part-constant; the colors and geometry are exprs that could read changed variables). */
internal data class WheelKey(
    val x: Double, val y: Double, val radius: Double, val fontSize: Double,
    val strokeColor: Int, val bg: Int, val bg2: Int, val tradius: Double,
)

/** A wheel baked at [angle0]; per-frame draws rotate the sprite by the angle delta. */
internal class WheelSprite(
    val content: LayerSprite?, val pivotX: Double, val pivotY: Double,
    val angle0: Double, val key: WheelKey,
)

private fun drawWheelBody(
    ctx: DrawingContext, part: WheelPart, env: Environment,
    x: Double, y: Double, angle: Double, fontSize: Double, fontName: String,
    strokeColor: Int, bg: Int, orientation: String,
) {
    val radius = evalAttr(part.radius, env)
    val labels = part.text?.split(',') ?: return
    val n = labels.size

    ctx.save()
    ctx.translate(x, y)
    ctx.setFont(fontName, fontSize)

    // iOS measures each label WITH its spaces (ECWatchDefinitionManager SWheel: sizeWithAttributes
    // on the raw comma-split label; ECQView ECQHandSpoke draws the raw text centered). Trailing/
    // leading spaces are the watch designer's centering — e.g. Istanbul's ',♬ ,,♫ ,,♪ ' relies on
    // the trailing space to seat the note glyph in its aperture. Trimming (the web port's habit,
    // which we inherited) shrank maxW and re-centered the drawn glyph, pushing it off-aperture.
    var maxW = 0.0
    for (lab in labels) maxW = max(maxW, ctx.measureTextWidth(lab))
    // Measured line height (not the fontSize approximation): for 'twelve'/'six' orientations the text
    // radius is tradius − maxH/2, so an undersized maxH pushes the label too far out (too high in a
    // window). iOS/web use the measured glyph height; ctx.textHeight() matches.
    val maxH = ctx.textHeight()

    val angle1 = if (part.angle1 != null) evalAttr(part.angle1, env) else 0.0
    val angle2 = if (part.angle2 != null) evalAttr(part.angle2, env) else 2 * PI
    val step = (angle2 - angle1) / n
    val tradius = if (part.tradius != null) evalAttr(part.tradius, env) else radius

    // QWheels draw their own circular background.
    if (part.wheelVariant == "QWheel" && !isTransparentArgb(bg)) ctx.fillCircle(0.0, 0.0, radius, bg)

    // TWheel background: a split black/white annulus for the 24-hour day/night dial (halfAndHalf),
    // or a plain annulus fill otherwise. iOS builds this from 24 colored number tiles split at
    // 06:00/18:00 (n/4, 3n/4); night half = bgColor (black), day half = bgColor2 (white). The
    // equivalent semicircle split matches that visual. Rotates with the wheel.
    val halfAndHalf = part.halfAndHalf != null && evalAttr(part.halfAndHalf, env) != 0.0
    val bg2 = if (part.bgColor2 != null) evalColorArgb(part.bgColor2, env) else bg
    if (part.wheelVariant == "TWheel" && (halfAndHalf || !isTransparentArgb(bg))) {
        val innerR = radius - fontSize - 2
        ctx.save()
        ctx.rotate(angle)
        if (halfAndHalf) {
            fillAnnulusArc(ctx, radius, innerR, PI, 2 * PI, bg)   // night (top half)
            fillAnnulusArc(ctx, radius, innerR, 0.0, PI, bg2)     // day (bottom half)
        } else if (!isTransparentArgb(bg)) {
            fillAnnulusArc(ctx, radius, innerR, 0.0, 2 * PI, bg)
        }
        ctx.restore()
    }

    val isPartialArc = part.angle1 != null || part.angle2 != null
    ctx.save()
    ctx.rotate(if (isPartialArc) -angle + angle1 else angle + angle1)

    // Dynamic-dial tick rings (e.g. the 24-hour ring's tick='tick288') — drawn in the wheel's rotated
    // frame so the major ticks track the hour numbers. The numbers sit inside at tradius.
    if (part.tick != null) drawWheelTicks(ctx, part.tick!!, radius, strokeColor)

    for (i in 0 until n) {
        val label = labels[i]   // untrimmed: iOS centers the raw label (spaces included) in its cell
        if (label.isNotEmpty()) {
            ctx.save()
            val currentRotation = if (isPartialArc) (-angle + angle1 + i * step) else 0.0
            when (orientation) {
                "three" -> ctx.translate(tradius - maxW / 2, 0.0)
                "six" -> ctx.translate(0.0, tradius - maxH / 2)
                "twelve" -> ctx.translate(0.0, -(tradius - maxH / 2))
                "nine" -> ctx.translate(-(tradius - maxW / 2), 0.0)
            }
            if (isPartialArc) ctx.rotate(-currentRotation)
            // On a halfAndHalf dial each number auto-contrasts with its half via a difference blend,
            // so numbers straddling the day/night split (6, 18) come out half white / half black.
            if (halfAndHalf) {
                ctx.fillTextDifference(label, 0.0, ctx.textVisualCenterY())
            } else {
                ctx.fillText(label, 0.0, ctx.textVisualCenterY(), strokeColor)
            }
            ctx.restore()
        }
        ctx.rotate(if (isPartialArc) step else -step)
    }

    ctx.restore()
    ctx.restore()
}

/**
 * Tick rings for a dynamic dial's `tick=` attribute — concentric tickOut rings (major/minor/micro)
 * at the wheel's [radius], pointing inward. Ported from ECQView.m drawTickMarks (ECDialTick*).
 */
private fun drawWheelTicks(ctx: DrawingContext, tick: String, radius: Double, strokeColor: Int) {
    // Each ring: number of marks, size factor (× radius), line width. From iOS drawTickMarks.
    val rings = when (tick.lowercase()) {
        "tick36" -> listOf(Triple(12, 0.04, 1.0), Triple(36, 0.03, 0.7))
        "tick96" -> listOf(Triple(24, 0.04, 1.0), Triple(96, 0.03, 0.7))
        "tick180" -> listOf(Triple(12, 0.04, 1.0), Triple(36, 0.03, 0.7), Triple(180, 0.02, 0.5))
        "tick288" -> listOf(Triple(24, 0.1, 0.7), Triple(48, 0.07, 0.7), Triple(288, 0.04, 0.7))
        else -> return
    }
    for ((nMarks, sizeF, w) in rings) {
        val ms = radius * sizeF
        val innerR = max(0.0, radius - ms)
        for (i in 0 until nMarks) {
            val th = (i.toDouble() / nMarks) * 2 * PI
            val c = cos(th - PI / 2); val s = sin(th - PI / 2)
            ctx.beginPath(); ctx.moveTo(radius * c, radius * s); ctx.lineTo(innerR * c, innerR * s)
            ctx.strokePath(strokeColor, w)
        }
    }
}

internal fun drawQWedge(ctx: DrawingContext, part: QWedgePart, env: Environment, animCache: StaticCache? = null) {
    val cx = evalAttr(part.x, env)
    val cy = -evalAttr(part.y, env)
    val outerR = evalAttr(part.outerRadius, env)
    val innerR = evalAttr(part.innerRadius, env)
    val span = evalAttr(part.angleSpan, env)
    // Wedge angles CHASE their targets like every iOS animating value (updateAndReturnValue
    // handles each part's angle AND offsetAngle): Terra's date-colored city wedges ride the
    // world-time ring's 15° pusher slides instead of snapping ahead of it.
    val wedgeSpeed = animSpeedOf(part.animSpeed, env)
    val angle = animatedAngle(animCache, part, 0, fmod2pi(evalAttr(part.angle, env)), wedgeSpeed)
    if (outerR <= 0 || innerR <= 0 || span <= 0) return

    val strokeColor = if (part.strokeColor != null) evalColorArgb(part.strokeColor, env) else WW_BLACK
    val fillColor = if (part.fillColor != null) evalColorArgb(part.fillColor, env) else 0
    // iOS wedge borderWidth: the XML attr, defaulting to ECHandLineWidthOutline=1.0 (fillColor clear)
    // / ECHandLineWidthFill=0.25 (ECWatchDefinitionManager parserDidQWedgeStart). Terra's city/24hr
    // indicator wedges set 0.25/0.07 explicitly — previously dropped and stroked at a flat 0.3.
    val borderWidth = if (part.borderWidth != null) evalAttr(part.borderWidth, env)
                      else if (isTransparentArgb(fillColor)) 1.0 else 0.25

    ctx.save()
    ctx.translate(cx, cy)

    var totalAngle = angle
    if (part.offsetRadius != null && part.offsetAngle != null) {
        val offR = evalAttr(part.offsetRadius, env)
        val offA = animatedAngle(
            animCache, part, 1, fmod2pi(evalAttr(part.offsetAngle, env)), wedgeSpeed,
        )
        ctx.translate(offR * sin(offA), -offR * cos(offA))
        totalAngle = offA + angle
    }
    ctx.rotate(totalAngle)

    // Annular sector centered on 12 o'clock (-π/2), built from sampled arcs.
    val startAngle = -PI / 2 - span / 2
    val endAngle = -PI / 2 + span / 2
    ctx.beginPath()
    arcSweep(ctx, outerR, startAngle, endAngle, moveFirst = true)
    arcSweep(ctx, innerR, endAngle, startAngle, moveFirst = false)
    ctx.closePath()
    if (!isTransparentArgb(fillColor)) ctx.fillPath(fillColor)
    if (borderWidth > 0 && !isTransparentArgb(strokeColor)) ctx.strokePath(strokeColor, borderWidth)

    ctx.restore()
}

/**
 * QdayNightRing: an annulus of [numWedges] sectors, each rotated to a leaf-center angle so the
 * filled wedges cluster between the planet's rise and set. Ported from renderer.ts (normal,
 * non-Wadokei path). The leaf angles come from the env's dayNightLeafAngle[LST] function.
 */
internal fun drawQDayNightRing(ctx: DrawingContext, part: QDayNightRingPart, env: Environment) {
    val cx = evalAttr(part.x, env)
    val cy = -evalAttr(part.y, env)
    val outerR = evalAttr(part.outerRadius, env)
    val innerR = evalAttr(part.innerRadius, env)
    if (outerR <= 0 || innerR <= 0) return
    val numWedges = evalAttr(part.numWedges, env).toInt().let { if (it <= 0) 24 else it }
    val planet = evalAttr(part.planetNumber, env)
    val stroke = if (part.strokeColor != null) evalColorArgb(part.strokeColor, env) else WW_BLACK
    val fill = if (part.fillColor != null) evalColorArgb(part.fillColor, env) else 0xFFFFFFFF.toInt()

    val masterOffset = if (part.masterOffset != null) evalAttr(part.masterOffset, env) else 0.0

    // A ring bound to a Terra env slot computes day/night against that slot city's location.
    val slot = if (part.envSlot != null) evalAttr(part.envSlot, env).toInt() else -1
    val slotFn = if (slot > 0) env.functions["dayNightLeafAngleForSlot"] else null
    val fnName = if (part.timeBase == "LST") "dayNightLeafAngleLST" else "dayNightLeafAngle"
    val leafFn = slotFn ?: env.functions[fnName] ?: return

    val wedgeSpan = (2 * PI + 0.2) / numWedges
    val startAngle = -PI / 2 - wedgeSpan / 2
    val endAngle = -PI / 2 + wedgeSpan / 2
    // iOS passes ECHandLineWidthOutline=1.0 (clear fill) / ECHandLineWidthFill=0.25 as the wedge
    // borderWidth (ECWatchDefinitionManager parserDidDayNightRingStart); no XML attr exists.
    val lineWidth = if (isTransparentArgb(fill)) 1.0 else 0.25

    ctx.save()
    ctx.translate(cx, cy)
    for (i in 0 until numWedges) {
        val a = if (slotFn != null) leafFn(doubleArrayOf(planet, i.toDouble(), numWedges.toDouble(), slot.toDouble()))
                else leafFn(doubleArrayOf(planet, i.toDouble(), numWedges.toDouble()))
        if (a.isNaN()) continue
        ctx.save()
        ctx.rotate(masterOffset + a)
        ctx.beginPath()
        arcSweep(ctx, outerR, startAngle, endAngle, moveFirst = true)
        arcSweep(ctx, innerR, endAngle, startAngle, moveFirst = false)
        ctx.closePath()
        if (!isTransparentArgb(fill)) ctx.fillPath(fill)
        if (!isTransparentArgb(stroke)) ctx.strokePath(stroke, lineWidth)
        ctx.restore()
    }
    ctx.restore()
}

/** Append an arc (centered at origin) from [fromA] to [toA] to the current path. */
/** Fill the annular sector between [innerR] and [outerR] from [startA] to [endA] (radians, +x = 0). */
private fun fillAnnulusArc(ctx: DrawingContext, outerR: Double, innerR: Double, startA: Double, endA: Double, argb: Int) {
    if (isTransparentArgb(argb)) return
    ctx.beginPath()
    arcSweep(ctx, outerR, startA, endA, moveFirst = true)
    arcSweep(ctx, innerR, endA, startA, moveFirst = false)
    ctx.closePath()
    ctx.fillPath(argb)
}

private fun arcSweep(ctx: DrawingContext, r: Double, fromA: Double, toA: Double, moveFirst: Boolean, steps: Int = 24) {
    for (k in 0..steps) {
        val a = fromA + (toA - fromA) * k / steps
        val px = r * cos(a); val py = r * sin(a)
        if (k == 0 && moveFirst) ctx.moveTo(px, py) else ctx.lineTo(px, py)
    }
}
