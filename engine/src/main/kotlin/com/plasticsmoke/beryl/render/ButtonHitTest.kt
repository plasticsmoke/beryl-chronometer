package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.astro.kindDragsAlarm
import com.plasticsmoke.beryl.astro.kindDragsMainTime
import com.plasticsmoke.beryl.expr.Environment
import com.plasticsmoke.beryl.expr.evaluate
import com.plasticsmoke.beryl.watch.ButtonPart
import com.plasticsmoke.beryl.watch.QHandPart
import com.plasticsmoke.beryl.watch.StaticPart
import com.plasticsmoke.beryl.watch.Watch
import com.plasticsmoke.beryl.watch.WatchPart
import com.plasticsmoke.beryl.watch.WheelPart
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Interactive-part hit-testing, ported from iOS ECGLPartBase/ECGLPart enclosesPoint +
 * ECGLPartFinder findClosestActivePartInWatch.
 *
 * Coordinates are XML face units, Y-UP, origin at the watch center (the same space the part
 * x/y attributes live in) — callers convert device pixels via the render transform.
 *
 * Candidates are buttons (tap actions) and, while the stem is out, hands whose `kind` maps a
 * revolution to a time span (drag-to-set). Bounds models, matching iOS:
 *  - image buttons (src=): the image quad centered at (x + motion·xMotion, y + motion·yMotion),
 *    the whole quad mirrored in x on the back (flipXOnBack);
 *  - src-less buttons (w/h attrs): the fixed corner rect [x, x+w] × [y, y+h] — iOS's base-class
 *    enclosesPoint applies neither the flip nor the motion offsets to these;
 *  - hands: the UNROTATED bounding box about the anchor, tested against the touch point rotated
 *    back by the hand's current angle (iOS transformPointWithinView);
 *  - wheels (Terra's 24-hour dial): the full disc square about the center.
 *
 * Each dimension expands to at least [MIN_INPUT_SIZE] (ECMinimumInputViewSize). When several
 * parts enclose the point, the highest grabPrio wins; among equals, the one deepest from its
 * border; parts bigger than 100×100 always lose depth ties (iOS "Chandra hack").
 */

/** ECMinimumInputViewSize — minimum hit-rect size in face units, each dimension. */
const val MIN_INPUT_SIZE = 40.0

private class HitRect(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    fun expanded(): HitRect {
        var nx0 = x0; var nx1 = x1; var ny0 = y0; var ny1 = y1
        if (x1 - x0 < MIN_INPUT_SIZE) {
            val cx = (x0 + x1) / 2
            nx0 = cx - MIN_INPUT_SIZE / 2
            nx1 = cx + MIN_INPUT_SIZE / 2
        }
        if (y1 - y0 < MIN_INPUT_SIZE) {
            val cy = (y0 + y1) / 2
            ny0 = cy - MIN_INPUT_SIZE / 2
            ny1 = cy + MIN_INPUT_SIZE / 2
        }
        return HitRect(nx0, ny0, nx1, ny1)
    }

    fun contains(px: Double, py: Double) = px in x0..x1 && py in y0..y1

    fun borderDistance(px: Double, py: Double) =
        minOf(px - x0, x1 - px, py - y0, y1 - py)
}

/**
 * Whether the button responds to touches right now — iOS ECGLPartBase activeInModeNum.
 * The mode filter already happened at parse time (a back-only button exists only in the
 * BACK-parsed watch), so only the enabled gate remains.
 */
fun buttonIsActive(part: ButtonPart, env: Environment): Boolean {
    if (part.action == null) return false
    fun leaf(name: String) = env.functions[name]?.invoke(DoubleArray(0)) ?: 0.0
    return when (part.enabled) {
        "always" -> true
        "wrongTimeOnly" -> leaf("manualSet") != 0.0 || leaf("timeIsCorrect") == 0.0
        // iOS default = ECButtonEnabledStemOutOnly ('alarmStemOutOnly' additionally accepts the
        // alarm stem; no staged face uses that string, but the gate is the same shape)
        else -> leaf("manualSet") != 0.0 || (part.enabled == "alarmStemOutOnly" && leaf("alarmManualSet") != 0.0)
    }
}

/** A draggable hand is grabbable only while its stem is out (iOS activeInModeNum for hands):
 *  main-time hands need the main stem, alarm/interval hands the alarm stem. Terra's world-
 *  time ring follows the main stem too (its parts archive iOS's hand default enabledControl
 *  = StemOutOnly, ECPartController.m:79). */
private fun handIsGrabbable(kind: String?, env: Environment): Boolean {
    fun leaf(name: String) = env.functions[name]?.invoke(DoubleArray(0)) ?: 0.0
    return ((kindDragsMainTime(kind) || kind == com.plasticsmoke.beryl.astro.WORLDTIME_RING_KIND) &&
        leaf("manualSet") != 0.0) ||
        (kindDragsAlarm(kind) && leaf("alarmManualSet") != 0.0)
}

private fun buttonRect(part: ButtonPart, env: Environment, images: Map<String, LoadedImage>): HitRect? {
    env.evaluatingButton = part.name
    try {
        val loaded = images[part.src]
        if (loaded != null) {
            val motion = evalAttr(part.motion, env)
            var cx = evalAttr(part.x, env) + motion * evalAttr(part.xMotion, env)
            val cy = evalAttr(part.y, env) + motion * evalAttr(part.yMotion, env)
            if (part.flippedOnBack) cx = -cx
            val s = if (part.scale != null) evalAttr(part.scale, env) else 1.0
            val w = loaded.image.width * loaded.scale * s
            val h = loaded.image.height * loaded.scale * s
            return HitRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        }
        val w = evalAttr(part.w, env)
        val h = evalAttr(part.h, env)
        if (w <= 0 || h <= 0) return null
        val x = evalAttr(part.x, env)
        val y = evalAttr(part.y, env)
        return HitRect(x, y, x + w, y + h)
    } finally {
        env.evaluatingButton = null
    }
}

/** The point where a hand pivots (and a drag session measures its angles): x/y plus motion. */
fun dragAnchorOf(part: QHandPart, env: Environment): DoubleArray = doubleArrayOf(
    evalAttr(part.x, env) + evalAttr(part.xMotion, env),
    evalAttr(part.y, env) + evalAttr(part.yMotion, env),
)

/**
 * Hand hit test: local frame + bounds (iOS enclosesPointGuts). Returns the border depth when
 * ([px], [py]) falls inside the expanded unrotated bounds, else null.
 *
 * The unrotated box approximates iOS's rendered-texture bounds from the hand geometry attrs:
 * width across, −tail…length(+ornament) along. Orbiting image hands (offsetRadius, e.g. the
 * Geneva moon disc) are a box at the orbit position instead — their `angle` spins the disc
 * about its own anchor, not the pivot, so un-rotation doesn't apply.
 */
private fun handHitDepth(part: QHandPart, env: Environment, images: Map<String, LoadedImage>, px: Double, py: Double): Double? {
    // The gear/spoke/special hands are never draggable (no kind), and unbounded types are skipped.
    if (part.special != null || part.handType == "gear" || part.handType == "spoke") return null
    val anchor = dragAnchorOf(part, env)
    val dx = px - anchor[0]
    val dy = py - anchor[1]

    val rect: HitRect
    val lx: Double
    val ly: Double
    val offsetRadius = evalAttr(part.offsetRadius, env)
    if (part.src != null) {
        val loaded = images[part.src] ?: return null
        val w = loaded.image.width * loaded.scale
        val h = loaded.image.height * loaded.scale
        if (offsetRadius > 0) {
            // Orbit case: box centered at the orbit position, no un-rotation.
            val offsetAngle = evalAttr(part.offsetAngle, env)
            val ox = offsetRadius * sin(offsetAngle)
            val oy = offsetRadius * cos(offsetAngle)
            rect = HitRect(ox - w / 2, oy - h / 2, ox + w / 2, oy + h / 2)
            lx = dx
            ly = dy
        } else {
            // Anchored (or centered) image hand: pivot at x/y, image offset by its anchor.
            val xa = if (part.xAnchor != null) evalAttr(part.xAnchor, env) else w / 2
            val ya = if (part.yAnchor != null) evalAttr(part.yAnchor, env) else h / 2
            rect = HitRect(-xa, -ya, w - xa, h - ya)
            val a = evalAttr(part.angle, env)
            lx = dx * cos(a) - dy * sin(a)
            ly = dy * cos(a) + dx * sin(a)
        }
    } else {
        val length = evalAttr(part.length, env)
        if (length <= 0) return null
        val width = evalAttr(part.width, env)
        val tail = if (part.tail != null) evalAttr(part.tail, env) else ceil(length / 10)
        val oRadius = evalAttr(part.oRadius, env)
        val halfW = max(width, 2 * oRadius) / 2
        rect = HitRect(-halfW, -max(tail, oRadius), halfW, length + evalAttr(part.oLength, env))
        // Rotate the anchor-relative point BACK by the hand's clock angle (clockwise from 12).
        val a = evalAttr(part.angle, env)
        lx = dx * cos(a) - dy * sin(a)
        ly = dy * cos(a) + dx * sin(a)
    }

    val expanded = rect.expanded()
    if (!expanded.contains(lx, ly)) return null
    return if (rect.x1 - rect.x0 > 100 && rect.y1 - rect.y0 > 100) 0.01
    else expanded.borderDistance(lx, ly)
}

/** Wheel hit test (Terra's draggable 24-hour dial): the disc's bounding square. */
private fun wheelHitDepth(part: WheelPart, env: Environment, px: Double, py: Double): Double? {
    val r = evalAttr(part.radius, env)
    if (r <= 0) return null
    val cx = evalAttr(part.x, env)
    val cy = evalAttr(part.y, env)
    if (part.kind == com.plasticsmoke.beryl.astro.WORLDTIME_RING_KIND) {
        // Terra's ring mover: on iOS the TWheel's hit surface is its 24 invisible text
        // spokes AT THE RIM, each quad expanded to the 40-unit minimum — together an
        // annulus ~MIN_INPUT_SIZE wide inside the rim radius, NOT a full disc (a disc
        // would shadow the draggable 24-hour dial and the date-advance buttons within).
        val dist = kotlin.math.hypot(px - cx, py - cy)
        if (dist < r - MIN_INPUT_SIZE || dist > r) return null
        return minOf(dist - (r - MIN_INPUT_SIZE), r - dist)
    }
    val rect = HitRect(cx - r, cy - r, cx + r, cy + r)
    val expanded = rect.expanded()
    if (!expanded.contains(px, py)) return null
    return if (2 * r > 100) 0.01 else expanded.borderDistance(px, py)
}

private fun collectInteractive(parts: List<WatchPart>, out: MutableList<WatchPart>) {
    for (part in parts) {
        when (part) {
            is ButtonPart -> out.add(part)
            is QHandPart -> if (part.kind != null) out.add(part)
            is WheelPart -> if (part.kind != null) out.add(part)
            is StaticPart -> collectInteractive(part.children, out)
            else -> {}
        }
    }
}

/**
 * Find the interactive part under ([xUnits], [yUnits]) — an active [ButtonPart] to press, or a
 * grabbable [QHandPart]/[WheelPart] to drag (stem out). Returns null when nothing encloses the
 * point. iOS ECGLPartFinder: the highest grabPrio among enclosing parts wins; equals tie-break
 * by border depth.
 */
fun findPartAt(
    watch: Watch,
    env: Environment,
    images: Map<String, LoadedImage>,
    xUnits: Double,
    yUnits: Double,
): WatchPart? {
    val candidates = ArrayList<WatchPart>()
    collectInteractive(watch.parts, candidates)
    var best: WatchPart? = null
    var bestPrio = Int.MIN_VALUE
    var bestDepth = -1.0
    for (part in candidates) {
        val prio: Int
        val depth: Double?
        when (part) {
            is ButtonPart -> {
                prio = part.grabPrio
                depth = if (buttonIsActive(part, env)) {
                    val rect = buttonRect(part, env, images)
                    val expanded = rect?.expanded()
                    if (expanded != null && expanded.contains(xUnits, yUnits)) {
                        // iOS Chandra hack: oversized parts always lose depth ties.
                        if (rect.x1 - rect.x0 > 100 && rect.y1 - rect.y0 > 100) 0.01
                        else expanded.borderDistance(xUnits, yUnits)
                    } else null
                } else null
            }
            is QHandPart -> {
                prio = part.grabPrio
                depth = if (handIsGrabbable(part.kind, env)) {
                    handHitDepth(part, env, images, xUnits, yUnits)
                } else null
            }
            is WheelPart -> {
                prio = 0
                depth = if (handIsGrabbable(part.kind, env)) {
                    wheelHitDepth(part, env, xUnits, yUnits)
                } else null
            }
            else -> continue
        }
        if (depth == null) continue
        if (prio > bestPrio || (prio == bestPrio && depth > bestDepth)) {
            bestPrio = prio
            bestDepth = depth
            best = part
        }
    }
    return best
}

/**
 * iOS enclosesPointExpanded (ECGLPart.m:1215): does ([xUnits], [yUnits]) fall inside [part]'s
 * hit rect expanded to the 40-unit minimum? Used for the `expanded='1'` loose-tracking release
 * test while a stem is out — the release acts if it lands anywhere in these bounds, without
 * requiring the closest-part finder to re-resolve the same button.
 */
fun buttonEnclosesExpanded(
    part: ButtonPart,
    env: Environment,
    images: Map<String, LoadedImage>,
    xUnits: Double,
    yUnits: Double,
): Boolean = buttonRect(part, env, images)?.expanded()?.contains(xUnits, yUnits) == true

/** Buttons-only view of [findPartAt] — a hand covering the point wins and yields null. */
fun findButtonAt(
    watch: Watch,
    env: Environment,
    images: Map<String, LoadedImage>,
    xUnits: Double,
    yUnits: Double,
): ButtonPart? = findPartAt(watch, env, images, xUnits, yUnits) as? ButtonPart

/**
 * Run a button's `action` expression in the watch env (iOS ECGLPartBase act). Assignments land
 * in env.variables; side-effect leaves dispatch through the registered action functions. The
 * evaluating-button context is set so actions referencing thisButtonPressed() behave as on iOS.
 * Returns false when the button has no action.
 */
fun executeButtonAction(part: ButtonPart, env: Environment): Boolean {
    val action = part.action ?: return false
    env.evaluatingButton = part.name
    try {
        evaluate(action, env)
    } finally {
        env.evaluatingButton = null
    }
    return true
}
