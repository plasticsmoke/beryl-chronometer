package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.expr.Environment
import com.plasticsmoke.beryl.watch.TerminatorPart
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Moon-phase terminator — the leaf-based display, ported from chronometer-web `src/watch/terminator.ts`
 * (itself a port of the iOS ECVirtualMachineOps.m terminatorAngle + ECQView.m ECTerminatorLeaf).
 * The shadow is approximated by 4×leavesPerQuadrant dark "leaves" that rotate with the moon's age.
 * Animation is omitted — leaf angles are computed once from the live phase/rotation.
 */

// Quadrants
private const val UPPER_LEFT = 0
private const val LOWER_LEFT = 1
private const val LOWER_RIGHT = 2
private const val UPPER_RIGHT = 3

private fun isLeft(q: Int) = q == UPPER_LEFT || q == LOWER_LEFT
private fun isRight(q: Int) = q == UPPER_RIGHT || q == LOWER_RIGHT
private fun isUpper(q: Int) = q == UPPER_LEFT || q == UPPER_RIGHT

private val EVEN_ORDER = intArrayOf(LOWER_LEFT, UPPER_LEFT, UPPER_RIGHT, LOWER_RIGHT)
private val ODD_ORDER = intArrayOf(UPPER_LEFT, LOWER_LEFT, LOWER_RIGHT, UPPER_RIGHT)
private fun quadrantOrder(leafIndex: Int, q: Int): Int = if (leafIndex % 2 == 0) EVEN_ORDER[q] else ODD_ORDER[q]

private fun fmod(a: Double, b: Double): Double = ((a % b) + b) % b

private fun phaseAngleForInnerEdge(forceLowerRight: Boolean, q: Int, idx: Int, lpq: Int): Double =
    if (!forceLowerRight && isLeft(q)) PI - ((idx + 1.0) / lpq) * (PI / 2)
    else PI + ((idx + 1.0) / lpq) * (PI / 2)

private fun phaseAngleForOuterEdge(forceLowerRight: Boolean, q: Int, idx: Int, lpq: Int): Double =
    if (!forceLowerRight && isLeft(q)) 2 * PI - (idx.toDouble() / lpq) * (PI / 2)
    else 0.0 + (idx.toDouble() / lpq) * (PI / 2)

/** Rotation angle for a single leaf. Direct port of terminatorAngle(). */
private fun terminatorAngle(phaseIn: Double, q: Int, idx: Int, lpq: Int, incr: Int): Double {
    var phase = fmod(phaseIn, PI * 2)
    val halfLeafSpan = 0.5 / lpq * (PI / 2)
    if (phase > PI) {
        phase -= halfLeafSpan
        if (isLeft(q) && phase < PI) phase = PI + 0.01
    } else {
        phase += halfLeafSpan
        if (isRight(q) && phase > PI) phase = PI - 0.01
    }
    if (isLeft(q)) phase = 2 * PI - phase

    val innerPhase = phaseAngleForInnerEdge(true, q, idx, lpq)
    val outerPhase = phaseAngleForOuterEdge(true, q, idx, lpq)
    val outerEndPhase = innerPhase - PI
    val innerStartPhase = outerPhase + PI
    val incremental = incr != 0

    val returnAngle: Double
    when {
        phase < outerPhase -> return 0.0
        phase < outerEndPhase -> {
            if (incremental) {
                val xOuter = cos(outerPhase)
                val outerRef = kotlin.math.atan(xOuter)
                val xInner = cos(outerEndPhase)
                val rOuter = sqrt(xOuter * xOuter + 1.0)
                val innerRef = kotlin.math.asin(xInner / rOuter)
                returnAngle = (phase - outerPhase) / (outerEndPhase - outerPhase) * (innerRef - outerRef)
            } else return 0.0
        }
        phase < innerStartPhase -> returnAngle = PI / 2 * (idx + 1.0) / lpq   // park angle
        phase < innerPhase -> {
            if (incremental) {
                val xInner = cos(innerPhase)
                val innerRef = kotlin.math.atan(xInner)
                val xOuter = cos(innerStartPhase)
                val rInner = sqrt(xInner * xInner + 1.0)
                val outerRef = kotlin.math.asin(xOuter / rInner)
                returnAngle = -(phase - innerPhase) / (innerStartPhase - innerPhase) * (outerRef - innerRef)
            } else return 0.0
        }
        else -> return 0.0
    }
    return if (q == UPPER_RIGHT || q == LOWER_LEFT) -returnAngle else returnAngle
}

private fun terminatorArcPoint(i: Int, n: Int, xsign: Int, ysign: Int, ycenter: Double, radius: Double, phase: Double): Pair<Double, Double> {
    val th = (PI / 2) * (i.toDouble() / n)
    val x = xsign * abs(cos(phase) * cos(th) * radius)
    val y = ycenter + ysign * sin(th) * radius
    return x to y
}

private class LeafState(
    val quadrant: Int, val idx: Int, val lpq: Int, val radius: Double, val incremental: Boolean,
    val fill: Int, val border: Int, val centerX: Double, val centerY: Double,
    val baseOffsetAngle: Double, val offsetRadius: Double, val currentAngle: Double, val currentRotation: Double,
)

private fun expandTerminatorToLeaves(part: TerminatorPart, env: Environment): List<LeafState> {
    val radius = evalAttr(part.radius, env).let { if (it == 0.0) 20.0 else it }
    val lpq = if (part.leavesPerQuadrant != null) round(evalAttr(part.leavesPerQuadrant, env)).toInt() else 6
    val incremental = part.incremental != null && evalAttr(part.incremental, env) != 0.0
    val anchorEdge = evalAttr(part.leafAnchorRadius, env)
    val fill = if (part.leafFillColor != null) evalColorArgb(part.leafFillColor, env) else 0xFF080808.toInt()
    val border = if (part.leafBorderColor != null) evalColorArgb(part.leafBorderColor, env) else 0xFF383838.toInt()
    val cx = evalAttr(part.x, env)
    val cy = evalAttr(part.y, env)
    val offsetRadius = radius + anchorEdge
    val phase = evalAttr(part.phaseAngle, env)
    val rotation = evalAttr(part.rotation, env)

    val leaves = ArrayList<LeafState>(lpq * 4)
    for (i in 0 until lpq) {
        for (q in 0 until 4) {
            val quadrant = quadrantOrder(i, q)
            val baseOffset = if (isUpper(quadrant)) 0.0 else PI
            var angle = terminatorAngle(phase, quadrant, i, lpq, if (incremental) 1 else 0)
            if (!isUpper(quadrant)) angle += PI
            leaves.add(LeafState(quadrant, i, lpq, radius, incremental, fill, border, cx, cy, baseOffset, offsetRadius, angle, rotation))
        }
    }
    return leaves
}

/** Everything a terminator's pixels depend on except [TermSprite.rotation0]: the whole leaf
 *  assembly rotates rigidly about the part center (each leaf's position offset and
 *  orientation advance by the same rotation), so equal keys mean the baked sprite is exact. */
internal data class TermKey(
    val phase: Double, val radius: Double, val lpq: Int, val incremental: Boolean,
    val fill: Int, val border: Int, val cx: Double, val cy: Double, val anchor: Double,
)

/** A terminator baked at one phase/rotation; per-frame draws rotate by the rotation delta.
 *  iOS keeps the leaves as textures and only their quad transforms change per frame — the
 *  live path re-sampled ~24 leaf paths every frame (~7 ms, the top cost on Geneva). During
 *  a hold-repeat the phase moves once per ACT (the key changes, one rebake), not per frame:
 *  phase/rotation are raw evals, never animated (see the file comment). */
internal class TermSprite(
    val content: LayerSprite?, val pivotX: Double, val pivotY: Double,
    val rotation0: Double, val key: TermKey,
    /** Leaf sprites for reassembly: a leaf's SHAPE is phase-independent (phase only spins
     *  it about its rim anchor — iOS ECTerminatorLeaf exactly), so a phase change rebuilds
     *  the disc from ~24 cheap rotated blits instead of re-sampling ~24 filled paths. The
     *  bank survives key changes; it depends only on the key's geometry/color fields. */
    val leafBank: List<BakedLeaf>,
)

internal class BakedLeaf(
    val content: LayerSprite?,
    /** Device-space rim-anchor pivot the leaf spins about, at bake pose. */
    val pivotX: Double, val pivotY: Double,
    /** Anchor position (face units, y-down local) and orientation at bake pose. */
    val anchorX0: Double, val anchorY0: Double, val orient0: Double,
)

/** Render a terminator part: expand to leaves and draw them at the current phase/rotation. */
fun renderTerminator(ctx: DrawingContext, part: TerminatorPart, env: Environment, cache: StaticCache? = null) {
    if (cache != null) {
        val radius = evalAttr(part.radius, env).let { if (it == 0.0) 20.0 else it }
        val key = TermKey(
            phase = evalAttr(part.phaseAngle, env),
            radius = radius,
            lpq = if (part.leavesPerQuadrant != null) round(evalAttr(part.leavesPerQuadrant, env)).toInt() else 6,
            incremental = part.incremental != null && evalAttr(part.incremental, env) != 0.0,
            fill = if (part.leafFillColor != null) evalColorArgb(part.leafFillColor, env) else 0xFF080808.toInt(),
            border = if (part.leafBorderColor != null) evalColorArgb(part.leafBorderColor, env) else 0xFF383838.toInt(),
            cx = evalAttr(part.x, env),
            cy = evalAttr(part.y, env),
            anchor = evalAttr(part.leafAnchorRadius, env),
        )
        val rotation = evalAttr(part.rotation, env)
        var sprite = cache.termSprites[part]
        if (sprite == null || sprite.key != key) {
            // Geometry/colors changed (face mode init, or never baked): bake the leaf bank.
            // A pure PHASE change (each act of a hold-repeat) reuses it — assembly is ~24
            // rotated blits, keeping act frames inside the 60 Hz budget.
            val sameGeometry = sprite != null &&
                sprite.key.copy(phase = key.phase) == key && sprite.leafBank.isNotEmpty()
            val bank = if (sameGeometry) sprite!!.leafBank else bakeLeafBank(ctx, part, env)
            val pivot = ctx.devicePoint(key.cx, -key.cy)
            val scale = ctx.deviceScale()
            val leaves = expandTerminatorToLeaves(part, env)
            ctx.pushLayer()
            for ((i, leaf) in leaves.withIndex()) {
                val baked = bank.getOrNull(i) ?: continue
                val content = baked.content ?: continue
                val off = leaf.baseOffsetAngle + leaf.currentRotation
                val ax = leaf.centerX + leaf.offsetRadius * sin(off)
                val ay = -leaf.centerY - leaf.offsetRadius * cos(off)
                ctx.drawSpriteRotated(
                    content, baked.pivotX, baked.pivotY, (off + leaf.currentAngle) - baked.orient0,
                    (ax - baked.anchorX0) * scale, (ay - baked.anchorY0) * scale,
                )
            }
            sprite = TermSprite(ctx.captureLayerSprite(), pivot[0], pivot[1], rotation, key, bank)
            cache.termSprites[part] = sprite
        }
        sprite.content?.let {
            ctx.drawSpriteRotated(it, sprite.pivotX, sprite.pivotY, rotation - sprite.rotation0)
        }
        return
    }
    drawTerminatorLeaves(ctx, part, env)
}

/** Rasterize each leaf once at its current pose; reassemblies rotate these about their rim
 *  anchors (iOS: leaf textures + per-frame quad transforms; shapes never re-rasterize). */
private fun bakeLeafBank(ctx: DrawingContext, part: TerminatorPart, env: Environment): List<BakedLeaf> {
    val leaves = expandTerminatorToLeaves(part, env)
    return leaves.map { leaf ->
        val off = leaf.baseOffsetAngle + leaf.currentRotation
        val ax = leaf.centerX + leaf.offsetRadius * sin(off)
        val ay = -leaf.centerY - leaf.offsetRadius * cos(off)
        val pivot = ctx.devicePoint(ax, ay)
        ctx.pushLayer()
        ctx.save()
        ctx.translate(leaf.centerX, -leaf.centerY)
        ctx.translate(leaf.offsetRadius * sin(off), -leaf.offsetRadius * cos(off))
        ctx.rotate(off + leaf.currentAngle)
        ctx.scale(1.0, -1.0)   // leaf geometry is in CG (Y-up) convention
        drawLeaf(ctx, leaf)
        ctx.restore()
        BakedLeaf(ctx.captureLayerSprite(), pivot[0], pivot[1], ax, ay, off + leaf.currentAngle)
    }
}

private fun drawTerminatorLeaves(ctx: DrawingContext, part: TerminatorPart, env: Environment) {
    val leaves = expandTerminatorToLeaves(part, env)
    for (leaf in leaves) {
        ctx.save()
        ctx.translate(leaf.centerX, -leaf.centerY)
        val offsetAngle = leaf.baseOffsetAngle + leaf.currentRotation
        ctx.translate(leaf.offsetRadius * sin(offsetAngle), -leaf.offsetRadius * cos(offsetAngle))
        ctx.rotate(offsetAngle + leaf.currentAngle)
        ctx.scale(1.0, -1.0)   // leaf geometry is in CG (Y-up) convention
        drawLeaf(ctx, leaf)
        ctx.restore()
    }
}

private fun drawLeaf(ctx: DrawingContext, leaf: LeafState) {
    val radius = leaf.radius
    val xsign: Int; val ysign: Int; val ycenter: Double; val ccwEndArc: Boolean
    when (leaf.quadrant) {
        UPPER_LEFT -> { xsign = -1; ysign = 1; ycenter = -radius; ccwEndArc = true }
        LOWER_LEFT -> { xsign = -1; ysign = -1; ycenter = radius; ccwEndArc = false }
        UPPER_RIGHT -> { xsign = 1; ysign = 1; ycenter = -radius; ccwEndArc = false }
        else -> { xsign = 1; ysign = -1; ycenter = radius; ccwEndArc = true }   // LOWER_RIGHT
    }
    val paInner = fmod(phaseAngleForInnerEdge(false, leaf.quadrant, leaf.idx, leaf.lpq), 2 * PI)
    val paOuter = fmod(phaseAngleForOuterEdge(false, leaf.quadrant, leaf.idx, leaf.lpq), 2 * PI)
    val n = 30
    val overlap = if (leaf.incremental) 1 else 0

    ctx.beginPath()
    ctx.moveTo(0.0, ycenter + ysign * radius)

    var lastX = 0.0; var lastY = 0.0
    var i = n - 1
    while (i >= -overlap) {
        val (x, y) = terminatorArcPoint(i, n, xsign, ysign, ycenter, radius, paInner)
        ctx.lineTo(x, y); lastX = x; lastY = y
        i--
    }
    val (nextX, nextY) = terminatorArcPoint(-overlap, n, xsign, ysign, ycenter, radius, paOuter)
    val midX = (lastX + nextX) / 2; val midY = (lastY + nextY) / 2
    val dX = lastX - nextX; val dY = lastY - nextY
    val endRadius = sqrt(dX * dX + dY * dY) / 2.0
    val startAngle = atan2(lastY - midY, lastX - midX)
    val endAngle = atan2(nextY - midY, nextX - midX)
    sampleArc(ctx, midX, midY, endRadius, startAngle, endAngle, ccwEndArc)

    var j = -overlap
    while (j <= n) {
        val (x, y) = terminatorArcPoint(j, n, xsign, ysign, ycenter, radius, paOuter)
        ctx.lineTo(x, y)
        j++
    }
    ctx.closePath()
    if (!isTransparentArgb(leaf.fill)) ctx.fillPath(leaf.fill)
    // iOS never sets a line width for the leaf border (ECTerminatorLeaf drawAtZoomFactor) —
    // CG's default 1.0 applies; 0.5 drew the seams half-weight on every terminator face.
    if (!isTransparentArgb(leaf.border)) ctx.strokePath(leaf.border, 1.0)
}

/** Append a sampled arc to the current path, matching Canvas 2D arc() winding. */
private fun sampleArc(ctx: DrawingContext, cx: Double, cy: Double, r: Double, start: Double, end: Double, counterclockwise: Boolean, steps: Int = 16) {
    var a1 = end
    if (!counterclockwise) { while (a1 < start) a1 += 2 * PI } else { while (a1 > start) a1 -= 2 * PI }
    for (k in 0..steps) {
        val a = start + (a1 - start) * k / steps
        ctx.lineTo(cx + r * cos(a), cy + r * sin(a))
    }
}
