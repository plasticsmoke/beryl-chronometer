package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.expr.Environment
import com.plasticsmoke.beryl.expr.evaluate
import com.plasticsmoke.beryl.watch.AnalemmaPart
import com.plasticsmoke.beryl.watch.ButtonPart
import com.plasticsmoke.beryl.watch.EotDialPart
import com.plasticsmoke.beryl.watch.ImagePart
import com.plasticsmoke.beryl.watch.QDialPart
import com.plasticsmoke.beryl.watch.QRectPart
import com.plasticsmoke.beryl.watch.QTextPart
import com.plasticsmoke.beryl.watch.StaticPart
import com.plasticsmoke.beryl.watch.Watch
import com.plasticsmoke.beryl.watch.WatchPart
import com.plasticsmoke.beryl.watch.WindowPart
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Renders the *static* layer of a watch face (everything except the moving hands) onto a
 * [DrawingContext]. Ported from the static-drawing paths of chronometer-web `src/watch/renderer.ts`.
 *
 * Scope: the part types Milano's static layer uses — QRect, QDial (background + text in the
 * upright/demi/rotated/radial orientations), Image — plus the bezel ring. QDial tick/dot/rose
 * marks, Windows, and the dynamic hands are deferred to later slices.
 */

const val BEZEL_THICKNESS_XML = 10.0

/** Apply all `<init>` blocks into [env] so face variables (radii, colors, …) are defined. */
fun applyInits(watch: Watch, env: Environment) {
    for (e in watch.initExprs) evaluate(e, env)
}

/**
 * Draw the static layer. The caller provides the canvas pixel size and the XML→pixel [scale]
 * (`physPx / (faceWidth + 2*bezel)`); the origin is placed at the canvas center, matching
 * renderFrame's `translate(w/2,h/2); scale(scale,scale)`.
 */
fun renderStaticLayer(
    ctx: DrawingContext,
    watch: Watch,
    env: Environment,
    scale: Double,
    canvasWidthPx: Int,
    canvasHeightPx: Int,
    images: Map<String, LoadedImage> = emptyMap(),
) {
    ctx.save()
    ctx.translate(canvasWidthPx / 2.0, canvasHeightPx / 2.0)
    ctx.scale(scale, scale)
    clipToFace(ctx, watch)

    for (part in watch.parts) drawStaticPart(ctx, part, env, images)
    drawBezel(ctx, watch)

    ctx.restore()
}

/**
 * Round the face: clip to the outer bezel edge so square part backgrounds don't show in corners.
 * Applies ONLY to the procedural-bezel substitution (a `bezelColor` patched into the staged watch
 * tag). iOS never clips — faces with their real case/band chrome staged keep the canonical watch
 * tag (no bezelColor) and render unclipped over the wallpaper, exactly like the iPhone.
 */
internal fun clipToFace(ctx: DrawingContext, watch: Watch) {
    if (watch.bezelColor.isEmpty()) return
    ctx.clipCircle(0.0, 0.0, watch.faceWidth / 2 + BEZEL_THICKNESS_XML)
}

internal fun drawStaticPart(ctx: DrawingContext, part: WatchPart, env: Environment, images: Map<String, LoadedImage>) {
    when (part) {
        is StaticPart -> {
            // iOS quirk (ECQView.m): all pieces of a <static> render into ONE texture whose quad is
            // placed at the UNION of the pieces' screen bounds. Image/QRect/QText pieces re-anchor to
            // their re-translated view bounds (faithful), but ECQDialView.drawInViewBounds translates
            // to the texture CENTER + the dial's own screen midpoint — so on screen a QDial piece
            // lands at xmlPos + unionCenter. Round faces have symmetric unions (offset ≈ 0); Tombstone's
            // case19.png pendant loop skews the union to +29.5, which its XML pre-compensates with
            // dial y='-30' (net ≈ 0, concentric with the hands — verified against the iPhone).
            val quirk = staticQDialQuirkOffset(part.children, env, images)
            fun drawChild(child: WatchPart) {
                if (quirk != null && child is QDialPart) {
                    ctx.save()
                    ctx.translate(quirk.first, -quirk.second)  // XML Y-up → canvas Y-down
                    drawStaticPart(ctx, child, env, images)
                    ctx.restore()
                } else {
                    drawStaticPart(ctx, child, env, images)
                }
            }
            // Windows nested inside a <static> punch holes in the following child (e.g. Mauna Kea's
            // date windows reveal the date wheels drawn beneath) — same accumulate-then-cutout rule
            // renderFrame applies to top-level windows. (Window holes stay at their screen positions;
            // the quirk shift applies only to the dial content.)
            val pending = ArrayList<WindowPart>()
            for (child in part.children) {
                if (child is WindowPart) {
                    pending.add(child)
                } else if (child is ButtonPart) {
                    // iOS ignores windows on buttons ("passed on to the next part").
                    drawChild(child)
                } else if (pending.isNotEmpty()) {
                    renderWithWindowCutouts(ctx, child, pending, env, images) { drawChild(child) }
                    pending.clear()
                } else {
                    drawChild(child)
                }
            }
            for (w in pending) drawWindowBorder(ctx, w, env)
        }
        is QRectPart -> drawQRect(ctx, part, env)
        is QDialPart -> drawQDial(ctx, part, env)
        is ImagePart -> drawImage(ctx, part, env, images)
        is ButtonPart -> drawButton(ctx, part, env, images)
        is QTextPart -> drawQText(ctx, part, env)
        is EotDialPart -> drawEotDial(ctx, part, env)
        is AnalemmaPart -> drawAnalemma(ctx, part, env, images)
        // QHand (dynamic) and Window (clipping) are handled by later slices.
        else -> {}
    }
}

/**
 * XML-space (Y-up) offset the iOS static-texture quirk applies to QDial pieces: the center of the
 * union of all piece screen bounds. Bounds per piece type follow the iOS view inits — Image:
 * center ± size/2 (ECImageView initCentered), QDial: center ± radius (ECQDialView), QRect:
 * [x,x+w]×[y,y+h] (ECImageView initCenteredBlank at x+w/2,y+h/2). QText contributes its center
 * point (its true bounds are the small text rect — never the union extreme in practice). Windows
 * are holes, not pieces. Returns null when the union is centered (no shift — every round face).
 */
private fun staticQDialQuirkOffset(
    children: List<WatchPart>,
    env: Environment,
    images: Map<String, LoadedImage>,
): Pair<Double, Double>? {
    var minX = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
    fun include(x0: Double, x1: Double, y0: Double, y1: Double) {
        if (x0 < minX) minX = x0; if (x1 > maxX) maxX = x1
        if (y0 < minY) minY = y0; if (y1 > maxY) maxY = y1
    }
    for (child in children) {
        when (child) {
            is QDialPart -> {
                val r = evalAttr(child.radius, env)
                if (r <= 0) continue
                val x = evalAttr(child.x, env); val y = evalAttr(child.y, env)
                include(x - r, x + r, y - r, y + r)
            }
            is ImagePart -> {
                val loaded = images[child.src] ?: continue
                val s = loaded.scale * (if (child.scale != null) evalAttr(child.scale, env) else 1.0)
                val w = loaded.image.width * s; val h = loaded.image.height * s
                val x = evalAttr(child.x, env); val y = evalAttr(child.y, env)
                include(x - w / 2, x + w / 2, y - h / 2, y + h / 2)
            }
            is QRectPart -> {
                val x = evalAttr(child.x, env); val y = evalAttr(child.y, env)
                include(x, x + evalAttr(child.w, env), y, y + evalAttr(child.h, env))
            }
            is QTextPart -> {
                val x = evalAttr(child.x, env); val y = evalAttr(child.y, env)
                include(x, x, y, y)
            }
            else -> {}
        }
    }
    if (minX > maxX || minY > maxY) return null
    val cx = (minX + maxX) / 2
    val cy = (minY + maxY) / 2
    // Sub-2-unit offsets are treated as symmetric. Faces whose iOS case/band art we substitute with
    // the procedural bezel don't stage those images, so a small off-center piece can become the union
    // extreme and fake a fraction-of-a-unit shift that iOS (whose union the big centered case art
    // dominates) never applies — e.g. Mauna Kea's back reference dial at y=139.5+4 vs its unstaged
    // 184×498 band strap. Real quirk offsets (Tombstone: +29.5) are an order of magnitude larger.
    return if (kotlin.math.abs(cx) < 2.0 && kotlin.math.abs(cy) < 2.0) null else cx to cy
}

/** A single positioned text label (the flat case; curved demi-text is deferred). */
private fun drawQText(ctx: DrawingContext, part: QTextPart, env: Environment) {
    val text = part.text ?: return
    if (text.isEmpty()) return
    val x = evalAttr(part.x, env)
    val y = -evalAttr(part.y, env)
    val fontSize = evalAttr(part.fontSize, env).let { if (it <= 0.0) 12.0 else it }
    val fontName = part.fontName ?: "Arial"
    val color = if (part.strokeColor != null) evalColorArgb(part.strokeColor, env) else 0xFF000000.toInt()
    if (isTransparentArgb(color)) return
    ctx.setFont(fontName, fontSize)

    val radius = if (part.radius != null) evalAttr(part.radius, env) else 0.0
    if (radius <= 0.0) {
        ctx.fillText(text, x, y + ctx.textVisualCenterY(), color)
        return
    }

    // Circular text along an arc of [radius], centered on [startAngle]. Ported from ECQView.m
    // drawCircularText. Each glyph advances along the arc by its width/radius and is rotated tangent;
    // a 'demi' label whose startAngle is in the bottom half (π/2..3π/2) flips upright to read normally.
    val startAngle = if (part.startAngle != null) evalAttr(part.startAngle, env) else 0.0
    val orientation = part.orientation ?: "upright"
    val bottom = orientation == "demi" && startAngle > PI / 2 && startAngle < 3 * PI / 2
    val chars = text.map { it.toString() }
    val widths = chars.map { ctx.measureTextWidth(it) }
    val total = widths.sumOf { it / radius }   // total angular length (radians)
    val textH = ctx.textHeight()
    val tvcy = ctx.textVisualCenterY()
    val textR = radius - textH / 2
    // Map the XML startAngle (12-o'clock = -π/2 in our th-space) and lay glyphs left→right. In screen
    // space (Y-down th), L→R means DECREASING th along the bottom of the circle but INCREASING th along
    // the top — so the glyph advance direction flips with the half. Bottom (demi) glyphs are also
    // rotated an extra π to read upright; top glyphs sit tangent without the flip.
    val thCenter = startAngle - PI / 2
    val dir = if (bottom) -1.0 else 1.0
    ctx.save()
    ctx.translate(x, y)
    var cur = thCenter - dir * total / 2
    for (i in chars.indices) {
        val dw = widths[i] / radius
        cur += dir * dw / 2
        if (chars[i] != " ") {
            ctx.save()
            ctx.translate(textR * cos(cur), textR * sin(cur))
            ctx.rotate(if (bottom) cur + PI / 2 + PI else cur + PI / 2)
            ctx.fillText(chars[i], 0.0, tvcy, color)
            ctx.restore()
        }
        cur += dir * dw / 2
    }
    ctx.restore()
}

// ============================================================================
// QRect
// ============================================================================

private fun drawQRect(ctx: DrawingContext, part: QRectPart, env: Environment) {
    val xCorner = evalAttr(part.x, env)
    val yCorner = evalAttr(part.y, env)
    val w = evalAttr(part.w, env)
    val h = evalAttr(part.h, env)
    if (w <= 0 || h <= 0) return

    // iOS: (x,y) is the top-left corner; Y is up in XML so negate the center.
    val cx = xCorner + w / 2
    val cy = -(yCorner + h / 2)

    val bg = evalColorArgb(part.bgColor, env)
    val fill = if (isTransparentArgb(bg)) WHITE else bg  // QRect defaults to white

    ctx.save()
    ctx.translate(cx, cy)
    ctx.fillRect(-w / 2, -h / 2, w, h, fill)

    // Pane dividers (e.g. the year/century digit cells 2|0, 2|6). iOS (ECQView) draws each as the
    // edge of a huge circle (radius = height*2.5) centred far to the left (panes>0) or right (panes<0),
    // so the divider bows subtly (bow depth ≈ height/20). Clip to the cell so only that arc shows.
    val panes = evalAttr(part.panes, env).toInt()
    val nPanes = if (panes < 0) -panes else panes
    if (nPanes > 1) {
        val stroke = if (isDarkArgb(fill)) WHITE else BLACK
        val r = 2.5 * h
        ctx.save()
        ctx.clipRect(-w / 2, -h / 2, w, h)
        for (p in 1 until nPanes) {
            val cx = if (panes > 0) -w / 2 - r + w * p / nPanes else w / 2 + r - w * p / nPanes
            ctx.strokeCircle(cx, 0.0, r, stroke, 0.25)
        }
        ctx.restore()
    }
    ctx.restore()
}

/** True when a packed ARGB color's luminance is dark (so a divider/overlay should be white). */
private fun isDarkArgb(argb: Int): Boolean {
    val rr = (argb ushr 16) and 0xFF
    val gg = (argb ushr 8) and 0xFF
    val bb = argb and 0xFF
    return (rr * 299 + gg * 587 + bb * 114) / 1000 < 128
}

// ============================================================================
// QDial — background + text labels (marks deferred)
// ============================================================================

private const val EC_DIAL_RADIUS_FACTOR = 0.92
private const val EC_DIAL_SMALL_RADIUS_CUTOFF = 45.0
private const val EC_DIAL_SMALL_RADIUS_FACTOR = 0.11 / (EC_DIAL_SMALL_RADIUS_CUTOFF - 25)

private fun drawQDial(ctx: DrawingContext, part: QDialPart, env: Environment) {
    val x = evalAttr(part.x, env)
    val y = -evalAttr(part.y, env)  // XML Y-up → Y-down
    val radius = evalAttr(part.radius, env)
    if (radius <= 0) return

    val strokeColor = if (part.strokeColor != null) evalColorArgb(part.strokeColor, env) else BLACK

    ctx.save()
    ctx.translate(x, y)
    if (part.angle != null) ctx.rotate(fmod2pi(evalAttr(part.angle, env)))   // iOS fmods every angle eval

    // Background fill (skipped when transparent, e.g. Milano's bgColor='clear'). With radius2 the
    // fill is an annulus between radius2 (inner) and radius (outer) — iOS QDial semantics; e.g.
    // Alexandria night's 'black' ring (138/130) must NOT bury the Earth/stars beneath it.
    val bg = evalColorArgb(part.bgColor, env)
    if (!isTransparentArgb(bg)) {
        val innerR = if (part.radius2 != null) evalAttr(part.radius2, env) else 0.0
        if (innerR > 0) ctx.fillRing(0.0, 0.0, radius, innerR, bg)
        else ctx.fillCircle(0.0, 0.0, radius, bg)
    }

    // Marks. ('arc'/'line'/'0' are intentional no-ops here — baked into the face image; 'rose' deferred.)
    val marksMask = parseMarks(part.marks)
    val markWidth = if (part.markWidth != null) evalAttr(part.markWidth, env) else 1.0
    val nMarks = evalAttr(part.nMarks, env)
    val mSize = evalAttr(part.mSize, env)
    val angle1 = evalAttr(part.angle1, env)
    val angle2 = if (part.angle2 != null) evalAttr(part.angle2, env) else 2 * PI
    val angle0 = evalAttr(part.angle0, env)   // rotation offset for the marks (iOS drawGuilloche +a0)

    if (marksMask and MARKS_OUTER != 0 && markWidth > 0) {
        // A full-circle border, or a partial arc band when angle1/angle2 restrict it (ChandraII's
        // subdial backgrounds + scale arcs use a thick markWidth over a partial range). Same th-space
        // convention as the tick loop: th=0 at 12 o'clock, increasing clockwise → screen th − π/2.
        if (part.angle1 != null || part.angle2 != null) {
            ctx.strokeArc(0.0, 0.0, radius, angle1 - PI / 2, angle2 - PI / 2, strokeColor, markWidth)
        } else {
            ctx.strokeCircle(0.0, 0.0, radius, strokeColor, markWidth)
        }
    }
    // 'no5s' suppresses every 5th tick (the hour positions), so numbers sit in clean gaps.
    // iOS (ECQView drawGuilloche) is 1-based and skips i%5==1; in this 0-based loop that's i%5==0.
    val no5s = marksMask and MARKS_NO5S != 0
    if (marksMask and MARKS_TICK_OUT != 0 && nMarks > 0) {
        val ms = if (mSize != 0.0) mSize else radius * 0.03
        val innerR = max(0.0, radius - ms)
        for (i in 0 until nMarks.toInt()) {
            if (no5s && i % 5 == 0) continue
            val th = (i.toDouble() / nMarks) * 2 * PI + angle0   // angle0 shifts ticks between labels (Kyoto tick12)
            if ((angle1 != 0.0 || angle2 != 2 * PI) && (th < angle1 || th > angle2)) continue
            val c = cos(th - PI / 2); val s = sin(th - PI / 2)
            ctx.beginPath(); ctx.moveTo(radius * c, radius * s); ctx.lineTo(innerR * c, innerR * s)
            ctx.strokePath(strokeColor, markWidth)
        }
    }
    // Tachymeter ticks (iOS ECDiskMarksMaskTachy, ECQView.m:354-369): one tick per speed value
    // 60..100 step 1, ..250 step 10, ..600 step 50, at the tachy angle 3600/v·(2π/60) clockwise
    // from 12 o'clock, mSize long inward from the radius (Thebes back bezel).
    if (marksMask and MARKS_TACHY != 0) {
        var v = 60
        while (v <= 600) {
            val th = 3600.0 / v * (2 * PI / 60)
            val c = cos(th - PI / 2); val s = sin(th - PI / 2)
            ctx.beginPath(); ctx.moveTo(radius * c, radius * s); ctx.lineTo((radius - mSize) * c, (radius - mSize) * s)
            ctx.strokePath(strokeColor, markWidth)
            v += if (v < 100) 1 else if (v < 250) 10 else 50
        }
    }
    // Compass-rose star: nMarks petals (left+right halves) between radius and radius2, alternating
    // fillColor1/fillColor2 and stroked. Ported from renderer.ts (Miami's back). Raw cos/sin (θ=0 at
    // +x), matching the web — the pattern is symmetric so the dial's own `angle` handles orientation.
    if (marksMask and MARKS_ROSE != 0 && nMarks > 0) {
        val innerR = if (part.radius2 != null) evalAttr(part.radius2, env) else radius * 0.6
        val fill1 = if (part.fillColor1 != null) evalColorArgb(part.fillColor1, env) else 0
        val fill2 = if (part.fillColor2 != null) evalColorArgb(part.fillColor2, env) else 0
        val n = nMarks.toInt()
        val dTheta = PI / n
        for (i in 0 until n) {
            val theta = 2 * i * dTheta
            for ((side, fill) in listOf(-1.0 to fill1, 1.0 to fill2)) {
                ctx.beginPath()
                ctx.moveTo(radius * cos(theta), radius * sin(theta))
                ctx.lineTo(innerR * cos(theta + side * dTheta), innerR * sin(theta + side * dTheta))
                ctx.lineTo(innerR * cos(theta), innerR * sin(theta))
                ctx.closePath()
                // iOS strokes a petal ONLY when its fill is clear (ECQView.m:326-352 picks
                // kCGPathStroke vs kCGPathFill per petal) — filled petals are never outlined.
                if (!isTransparentArgb(fill)) {
                    ctx.fillPath(fill)
                } else if (!isTransparentArgb(strokeColor)) {
                    ctx.strokePath(strokeColor, markWidth)
                }
            }
        }
    }
    if (marksMask and MARKS_DOT != 0 && nMarks > 0) {
        val dotR = (if (mSize != 0.0) mSize else 1.5) / 2
        for (i in 0 until nMarks.toInt()) {
            val th = (i.toDouble() / nMarks) * 2 * PI
            if ((angle1 != 0.0 || angle2 != 2 * PI) && (th < angle1 || th > angle2)) continue
            ctx.fillCircle(radius * cos(th - PI / 2), radius * sin(th - PI / 2), dotR, strokeColor)
        }
    }
    // Parallel lines (crossbars). Ported from ECQView.m drawGuilloche ECDiskMarksMaskLine: n lines
    // perpendicular to angle1; for n=1 a diameter (vertical/horizontal divider of the subdial).
    if (marksMask and MARKS_LINE != 0 && nMarks > 0) {
        val n = nMarks
        val outerR = radius
        for (i in 1..n.toInt()) {
            val b = PI / 2 - angle1
            val x0: Double; val x1: Double; val ya: Double; val yb: Double
            if (b == PI / 2 || b == 3 * PI / 2) {
                x0 = outerR; x1 = -x0; ya = i * 2 * outerR / (n + 1) - outerR; yb = ya
            } else if (b == 0.0 || b == PI) {
                ya = outerR; yb = -ya; x0 = i * 2 * outerR / (n + 1) - outerR; x1 = x0
            } else {
                val c = outerR * tan(b); val dd = outerR / tan(b)
                val xi = (c + dd) * cos(b); val yi = -(c + dd) * sin(b)
                x0 = i * xi / (n + 1); x1 = x0 - xi; ya = i * yi / (n + 1); yb = ya - yi
            }
            ctx.beginPath(); ctx.moveTo(x0, yb); ctx.lineTo(x1, ya); ctx.strokePath(strokeColor, markWidth)
        }
    }
    // Concentric arc(s). Ported from ECQView.m drawGuilloche ECDiskMarksMaskArc.
    if (marksMask and MARKS_ARC != 0 && nMarks > 0) {
        val n = nMarks
        val innerR = if (part.radius2 != null) evalAttr(part.radius2, env) else 0.0
        for (i in 1..n.toInt()) {
            val arcR = innerR + (radius - innerR) * i / (n + 1)
            ctx.strokeArc(0.0, 0.0, arcR, angle1 - PI / 2, angle2 - PI / 2, strokeColor, markWidth)
        }
    }

    val text = part.text
    if (text != null) {
        val labels = text.split(',')
        val n = labels.size
        val fontSize = evalAttr(part.fontSize, env).let { if (it == 0.0) 12.0 else it }
        val fontName = part.fontName ?: "Arial"
        val orientation = part.orientation ?: "upright"

        ctx.setFont(fontName, fontSize)

        // Direct 1:1 translation of chronometer-web renderer.ts text orientations (the authoritative
        // faithful port of iOS). Text radius uses fontSize (not measured height), and the vertical
        // baseline is centered via textVisualCenterY — for every orientation, both demi halves.
        when (orientation) {
            "upright" -> {
                val radiusFactor = if (radius < EC_DIAL_SMALL_RADIUS_CUTOFF)
                    EC_DIAL_RADIUS_FACTOR + EC_DIAL_SMALL_RADIUS_FACTOR * (EC_DIAL_SMALL_RADIUS_CUTOFF - radius)
                else EC_DIAL_RADIUS_FACTOR
                // iOS drawDialUpright (ECQView.m:2405-2412) insets by half the diagonal of the
                // MEASURED text box — sizeWithAttributes LINE HEIGHT (≈1.12·fontSize), not the
                // font size; fontSize left every upright numeral ~½ the difference too far out.
                val lineH = ctx.textHeight()
                for (i in 0 until n) {
                    val label = labels[i].trim()
                    if (label.isEmpty()) continue
                    val th = (i.toDouble() / n) * 2 * PI - PI / 2
                    val tw = ctx.measureTextWidth(label)
                    val textR = radius * radiusFactor - sqrt(tw * tw + lineH * lineH) / 2
                    ctx.save()
                    ctx.translate(textR * cos(th), textR * sin(th))
                    ctx.fillText(label, 0.0, ctx.textVisualCenterY(), strokeColor)
                    ctx.restore()
                }
            }
            "demi" -> {
                // Exact ECQView.m drawDialDemiRadial: the glyph LINE-BOX is top-aligned with its
                // outer edge at the radius (no demi dial sets a `tick` attr, so the 0.92 factor
                // never applies; demiTweak extends the bottom half only). Radial (top) half:
                // baseline lands at radius − ascent, ink toward the rim. Anti-radial (bottom)
                // half: iOS's y-flip + π composition lands the baseline at r − lineHeight + ascent
                // with ink extending INWARD — the halves are deliberately asymmetric (demiTweak
                // exists to correct it per-face). The previous center-anchoring at radius − h/2
                // was a sub-unit approximation, off in opposite directions per half.
                val demiTweak = evalAttr(part.demiTweak, env)
                val textH = ctx.textHeight()
                val asc = ctx.fontAscent()
                for (i in 0 until n) {
                    val label = labels[i].trim()
                    if (label.isEmpty()) continue
                    val th = (i.toDouble() / n) * 2 * PI - PI / 2
                    ctx.save()
                    if (i > n / 4.0 && i < 3 * n / 4.0) {
                        val r = radius + demiTweak
                        ctx.translate(r * cos(th), r * sin(th))
                        ctx.rotate(th + PI / 2 + PI)
                        ctx.fillText(label, 0.0, asc - textH, strokeColor)
                    } else {
                        ctx.translate(radius * cos(th), radius * sin(th))
                        ctx.rotate(th + PI / 2)
                        ctx.fillText(label, 0.0, asc, strokeColor)
                    }
                    ctx.restore()
                }
            }
            "rotated" -> {
                for (i in 0 until n) {
                    val label = labels[i].trim()
                    if (label.isEmpty()) continue
                    val th = (i.toDouble() / n) * 2 * PI - PI / 2
                    // iOS drawDialRadial (ECQView.m:2332-2334): the text box (measured LINE
                    // height, not fontSize) sits with its outer edge at radius·factor.
                    val textR = radius * EC_DIAL_RADIUS_FACTOR - ctx.textHeight() / 2
                    ctx.save()
                    ctx.translate(textR * cos(th), textR * sin(th))
                    ctx.rotate(th)
                    ctx.fillText(label, 0.0, ctx.textVisualCenterY(), strokeColor)
                    ctx.restore()
                }
            }
            "tachy" -> {
                // Tachymeter scale (Olympia): like 'demi', but each number is placed at the angle a
                // sweep-second hand reaches after 3600/V seconds — xTh = (3600/V)·(2π/60). Exact
                // ECQView.m drawDialTachy ("tachy is always demi") — but unlike demi, tachy ALWAYS
                // applies ECDialRadiusFactor: the line-box outer edge sits at 0.92·radius
                // (+ demiTweak on the flipped bottom half), same top-aligned anchoring as demi.
                // (Previously factor-less and center-anchored — numbers ~0.08·radius too far out.)
                val demiTweak = evalAttr(part.demiTweak, env)
                val textH = ctx.textHeight()
                val asc = ctx.fontAscent()
                val rBase = radius * EC_DIAL_RADIUS_FACTOR
                for (i in 0 until n) {
                    val label = labels[i].trim()
                    if (label.isEmpty()) continue
                    val v = label.toDoubleOrNull() ?: continue
                    if (v == 0.0) continue
                    val xTh = (3600.0 / v) * (2 * PI / 60)   // th-space angle (0 = top, clockwise)
                    val th = xTh - PI / 2
                    ctx.save()
                    if (xTh > PI / 2 && xTh < 3 * PI / 2) {
                        val r = rBase + demiTweak
                        ctx.translate(r * cos(th), r * sin(th))
                        ctx.rotate(th + PI / 2 + PI)
                        ctx.fillText(label, 0.0, asc - textH, strokeColor)
                    } else {
                        ctx.translate(rBase * cos(th), rBase * sin(th))
                        ctx.rotate(th + PI / 2)
                        ctx.fillText(label, 0.0, asc, strokeColor)
                    }
                    ctx.restore()
                }
            }
            else -> {
                // Default radial: rotate the whole context per label. Exact ECQView.m
                // drawDialRadial: line-box [0.92·radius − h, 0.92·radius], top-aligned → baseline
                // at 0.92·radius − ascent, ink toward the rim. (Previously approximated the ink
                // center with fontSize/2, sitting numerals ~0.06·fontSize too far out.)
                val baselineY = -(radius * EC_DIAL_RADIUS_FACTOR - ctx.fontAscent())
                ctx.save()
                for (i in 0 until n) {
                    val label = labels[i].trim()
                    if (label.isNotEmpty()) {
                        ctx.fillText(label, 0.0, baselineY, strokeColor)
                    }
                    ctx.rotate(2 * PI / n)
                }
                ctx.restore()
            }
        }
    }

    // Center dot (drawn last, over text — e.g. Geneva subdial hubs).
    if (marksMask and MARKS_CENTER != 0) ctx.fillCircle(0.0, 0.0, markWidth, BLACK)

    ctx.restore()
}

private const val MARKS_NONE = 0
private const val MARKS_OUTER = 1
private const val MARKS_CENTER = 2
private const val MARKS_TICK_OUT = 4
private const val MARKS_DOT = 16
private const val MARKS_ROSE = 32
private const val MARKS_LINE = 64
private const val MARKS_ARC = 128
private const val MARKS_NO5S = 256
private const val MARKS_TACHY = 512

private fun parseMarks(marks: String?): Int {
    if (marks == null) return MARKS_NONE
    var r = MARKS_NONE
    for (p in marks.split('|')) when (p.trim().lowercase()) {
        "outer" -> r = r or MARKS_OUTER
        "center" -> r = r or MARKS_CENTER
        "tickout" -> r = r or MARKS_TICK_OUT
        "dot" -> r = r or MARKS_DOT
        "rose" -> r = r or MARKS_ROSE
        "line" -> r = r or MARKS_LINE
        "arc" -> r = r or MARKS_ARC
        "no5s" -> r = r or MARKS_NO5S
        "tachym" -> r = r or MARKS_TACHY
    }
    return r
}

// ============================================================================
// Button (rendered as static chrome at its rest position)
// ============================================================================

/**
 * iOS ECButtonController wraps the button image in a centered ECImageView at (x,y), displaced by
 * motion·xMotion / motion·yMotion (ECWatchDefinitionManager parserDidButtonStart compiles the
 * offset streams as `(motion)*(xMotion)`). At rest the motion expressions (manualSet() etc.)
 * evaluate 0, so the button sits at its base position — typically under the band/case art drawn
 * after it, with only the protruding edge (crown tip, side-button cap) visible, as on the iPhone.
 */
internal fun drawButton(
    ctx: DrawingContext,
    part: ButtonPart,
    env: Environment,
    images: Map<String, LoadedImage>,
    animCache: StaticCache? = null,
) {
    val loaded = images[part.src] ?: return
    // iOS sets partBeingEvaluated around a button's own expressions so thisButtonPressed()
    // reads 1 only for the held button (press feedback via its motion expr).
    env.evaluatingButton = part.name
    try {
        val opacity = if (part.opacity != null) evalAttr(part.opacity, env) else 1.0
        if (opacity <= 0) return
        val motion = evalAttr(part.motion, env)
        // The motion offset CHASES its target (iOS getLinear at kECGLLinearAnimationSpeed ×
        // animSpeed=1): a stem/pusher SLIDES in and out over its travel instead of popping.
        val mx = animatedLinear(animCache, part, 1, motion * evalAttr(part.xMotion, env), BUTTON_SLIDE_SPEED)
        val my = animatedLinear(animCache, part, 2, motion * evalAttr(part.yMotion, env), BUTTON_SLIDE_SPEED)
        val x = evalAttr(part.x, env) + mx
        val y = -(evalAttr(part.y, env) + my)
        val s = if (part.scale != null) evalAttr(part.scale, env) else 1.0
        val w = loaded.image.width * loaded.scale * s
        val h = loaded.image.height * loaded.scale * s
        ctx.save()
        // iOS mirrors the whole button quad (position + texture) on the back — flipXOnBack.
        if (part.flippedOnBack) ctx.scale(-1.0, 1.0)
        ctx.translate(x, y)
        val rotation = evalAttr(part.rotation, env)
        if (rotation != 0.0) ctx.rotate(rotation)
        ctx.drawImage(loaded.image, -w / 2, -h / 2, w, h, opacity)
        ctx.restore()
    } finally {
        env.evaluatingButton = null
    }
}

// ============================================================================
// Image
// ============================================================================

private fun drawImage(ctx: DrawingContext, part: ImagePart, env: Environment, images: Map<String, LoadedImage>) {
    val src = part.src ?: return
    val loaded = images[src] ?: return

    val x = evalAttr(part.x, env)
    val y = -evalAttr(part.y, env)  // XML Y-up → Y-down
    val alpha = if (part.alpha != null) evalAttr(part.alpha, env) else 1.0
    if (alpha <= 0) return

    val xmlScale = if (part.scale != null) evalAttr(part.scale, env) else 1.0
    val drawW = loaded.image.width * loaded.scale * xmlScale
    val drawH = loaded.image.height * loaded.scale * xmlScale

    ctx.save()
    ctx.drawImage(loaded.image, x - drawW / 2, y - drawH / 2, drawW, drawH, alpha)
    ctx.restore()

    // Terra: blue city dots over the continents map (Robinson projection of the 24 ring cities).
    if (part.special == "specialDotsMap") {
        val blue = 0xFF0000FF.toInt()
        for ((px, py) in com.plasticsmoke.beryl.astro.terraCityDotPositions()) {
            ctx.fillCircle(x + px, y + py, 1.5, blue)
        }
    }
}

// ============================================================================
// Bezel (simplified solid ring — the layered gradient bezel is a later refinement)
// ============================================================================

/**
 * Layered metallic bezel, ported from chronometer-web renderer.ts drawBezel: a convex radial
 * base, a top-lit directional gradient, a tight conic specular reflection, fine machining
 * hairlines, and the inner-shadow / outer-highlight edge rings. (The iPhone used a case image
 * here; the web port invented this procedural bezel and it's the faithful reference.)
 */
internal fun drawBezel(ctx: DrawingContext, watch: Watch) {
    if (watch.bezelColor.isEmpty()) return
    val faceR = watch.faceWidth / 2
    val outerR = faceR + BEZEL_THICKNESS_XML
    val base = parseCssColorToArgb(watch.bezelColor)

    // 1. Convex metallic profile: dark inner lip → bright crown → dark outer lip.
    ctx.fillRingRadialGradient(0.0, 0.0, faceR, outerR, listOf(
        GradientStop(0.00, darkenArgb(base, 0.30)),
        GradientStop(0.06, darkenArgb(base, 0.55)),
        GradientStop(0.15, base),
        GradientStop(0.35, lightenArgb(base, 1.25)),
        GradientStop(0.50, lightenArgb(base, 1.30)),
        GradientStop(0.65, lightenArgb(base, 1.25)),
        GradientStop(0.85, base),
        GradientStop(0.94, darkenArgb(base, 0.55)),
        GradientStop(1.00, darkenArgb(base, 0.30)),
    ))

    // 2. Directional top-light (top bright, bottom shadow).
    ctx.fillRingLinearGradient(0.0, 0.0, faceR, outerR, 0.0, -outerR, 0.0, outerR, listOf(
        GradientStop(0.00, whiteAlpha(115)),
        GradientStop(0.20, whiteAlpha(51)),
        GradientStop(0.45, 0),
        GradientStop(0.65, blackAlpha(26)),
        GradientStop(1.00, blackAlpha(56)),
    ))

    // 3. Conic specular — a tight focused reflection near 10–11 o'clock + a faint opposite hint.
    ctx.fillRingSweepGradient(0.0, 0.0, faceR, outerR, -PI * 0.72, listOf(
        GradientStop(0.00, whiteAlpha(0)),
        GradientStop(0.02, whiteAlpha(38)),
        GradientStop(0.06, whiteAlpha(140)),
        GradientStop(0.10, whiteAlpha(153)),
        GradientStop(0.14, whiteAlpha(140)),
        GradientStop(0.20, whiteAlpha(31)),
        GradientStop(0.28, whiteAlpha(0)),
        GradientStop(0.52, whiteAlpha(0)),
        GradientStop(0.57, whiteAlpha(20)),
        GradientStop(0.62, whiteAlpha(20)),
        GradientStop(0.67, whiteAlpha(0)),
        GradientStop(1.00, whiteAlpha(0)),
    ))

    // 4. Fine concentric hairlines — brushed-metal texture.
    for (i in 1..5) {
        val r = faceR + BEZEL_THICKNESS_XML * (i / 6.0)
        ctx.strokeCircle(0.0, 0.0, r, if (i % 2 == 0) whiteAlpha(31) else blackAlpha(31), 0.25)
    }

    // 5. Inner shadow (bezel meets glass) + 6. faint outer-edge highlight.
    ctx.strokeCircle(0.0, 0.0, faceR, blackAlpha(179), 0.7)
    ctx.strokeCircle(0.0, 0.0, outerR, whiteAlpha(51), 0.4)

    // 7. Solar-noon indicator mark (etched groove at 12 o'clock).
    if (watch.bezelNoonMark) {
        val markOuter = faceR + BEZEL_THICKNESS_XML * 0.55
        ctx.beginPath(); ctx.moveTo(0.0, -faceR); ctx.lineTo(0.0, -markOuter); ctx.strokePath(blackAlpha(140), 0.6)
        ctx.beginPath(); ctx.moveTo(0.5, -faceR); ctx.lineTo(0.5, -markOuter); ctx.strokePath(whiteAlpha(64), 0.35)
    }
}

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
