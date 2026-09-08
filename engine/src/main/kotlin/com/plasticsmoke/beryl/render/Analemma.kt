package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.astro.sunAltitude
import com.plasticsmoke.beryl.astro.sunAzimuth
import com.plasticsmoke.beryl.astro.sunSkyOrientationAngle
import com.plasticsmoke.beryl.astro.timeIntervalFromUTCComponents
import com.plasticsmoke.beryl.astro.utcComponentsFromTimeInterval
import com.plasticsmoke.beryl.expr.Environment
import com.plasticsmoke.beryl.watch.AnalemmaPart
import com.plasticsmoke.beryl.watch.EotDialPart
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The Sun's analemma (figure-8) on a circular disc, rotated to the observer's sky orientation.
 * Ported from chronometer-web `analemma.ts` (drawn directly here rather than via cached bitmaps).
 * The path is the Sun's alt/az delta from the reference day (vernal equinox 2024, 12:00 UT, 45°N)
 * sampled over a year; the live Sun is plotted on it for the current date.
 */

private const val REF_LAT = 45 * PI / 180
private const val REF_LON = 0.0
private const val REF_EPOCH = 732628800.0  // 2024-03-20 12:00:00 UT, Apple epoch seconds
private const val PATH_DAYS = 365
private const val PATH_MARGIN = 0.15

/** Season ticks (dayIndex → ARGB): vernal equinox / summer solstice / autumnal equinox / winter. */
private val SEASON_TICKS = listOf(
    0 to 0xFF22AA22.toInt(), 93 to 0xFFDDCC00.toInt(),
    184 to 0xFFEE7722.toInt(), 275 to 0xFF2266CC.toInt(),
)

private fun normDelta(d: Double): Double {
    var x = d
    while (x > PI) x -= 2 * PI
    while (x < -PI) x += 2 * PI
    return x
}

internal fun drawAnalemma(ctx: DrawingContext, part: AnalemmaPart, env: Environment, images: Map<String, LoadedImage>) {
    val cx = evalAttr(part.x, env)
    val cy = -evalAttr(part.y, env)
    val radius = evalAttr(part.radius, env).let { if (it <= 0) 40.0 else it }
    val sunRadius = evalAttr(part.sunRadius, env).let { if (it <= 0) 2.5 else it }
    val sunFill = if (part.sunFillColor != null) evalColorArgb(part.sunFillColor, env) else 0xFFF2E407.toInt()
    val sunStroke = if (part.sunStrokeColor != null) evalColorArgb(part.sunStrokeColor, env) else 0xFF8B814B.toInt()
    val channelColor = if (part.channelColor != null) evalColorArgb(part.channelColor, env) else 0xFF000000.toInt()
    val channelWidth = evalAttr(part.channelWidth, env).let { if (it <= 0) 0.8 else it }
    val bgRotates = evalAttr(part.bgRotates, env) != 0.0

    // --- Build the path: per-day alt/az delta from the reference day ---
    val refAlt = sunAltitude(REF_EPOCH, REF_LAT, REF_LON)
    val refAz = sunAzimuth(REF_EPOCH, REF_LAT, REF_LON)
    val deltaAz = DoubleArray(PATH_DAYS)
    val deltaAlt = DoubleArray(PATH_DAYS)
    for (d in 0 until PATH_DAYS) {
        val di = REF_EPOCH + d * 86400.0
        deltaAlt[d] = sunAltitude(di, REF_LAT, REF_LON) - refAlt
        deltaAz[d] = normDelta(sunAzimuth(di, REF_LAT, REF_LON) - refAz)
    }

    // --- Scale to fit the disc (azimuth foreshortened by cos(altitude)), keep aspect ratio ---
    val usable = radius * (1 - PATH_MARGIN)
    var maxExtent = 0.0
    for (d in 0 until PATH_DAYS) {
        maxExtent = max(maxExtent, max(abs(deltaAz[d] * cos(refAlt + deltaAlt[d])), abs(deltaAlt[d])))
    }
    val scaleF = if (maxExtent > 0) usable / maxExtent else 1.0
    val sx = DoubleArray(PATH_DAYS); val sy = DoubleArray(PATH_DAYS)
    for (d in 0 until PATH_DAYS) {
        sx[d] = deltaAz[d] * cos(refAlt + deltaAlt[d]) * scaleF
        sy[d] = deltaAlt[d] * scaleF
    }
    // Center on the bounding-box midpoint.
    var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
    for (d in 0 until PATH_DAYS) { minX = min(minX, sx[d]); maxX = max(maxX, sx[d]); minY = min(minY, sy[d]); maxY = max(maxY, sy[d]) }
    val offX = (minX + maxX) / 2; val offY = (minY + maxY) / 2
    for (d in 0 until PATH_DAYS) { sx[d] -= offX; sy[d] -= offY }

    // --- Live Sun position (noon UT of the current date at the reference location) ---
    val di = env.functions["_dateInterval"]?.invoke(DoubleArray(0)) ?: 0.0
    val c = utcComponentsFromTimeInterval(di)
    val noonDI = timeIntervalFromUTCComponents(c.era, c.year, c.month, c.day, 12, 0, 0.0)
    val sunAlt = sunAltitude(noonDI, REF_LAT, REF_LON)
    val sunAz = sunAzimuth(noonDI, REF_LAT, REF_LON)
    val sunX = normDelta(sunAz - refAz) * cos(sunAlt) * scaleF - offX
    val sunY = (sunAlt - refAlt) * scaleF - offY
    val rotation = sunSkyOrientationAngle(di, env.observerLatRad ?: 0.0, env.observerLonRad ?: 0.0)

    // --- Draw ---
    ctx.save()
    ctx.translate(cx, cy)

    // Background disc (the face texture, clipped to a circle; optionally rotating).
    val bg = part.bgSrc?.let { images[it] }
    if (bg != null) {
        ctx.save()
        if (bgRotates) ctx.rotate(rotation)
        ctx.clipCircle(0.0, 0.0, radius)
        val w = bg.image.width * bg.scale; val h = bg.image.height * bg.scale
        ctx.drawImage(bg.image, -w / 2, -h / 2, w, h, 1.0)
        ctx.restore()
    }

    ctx.save()
    ctx.clipCircle(0.0, 0.0, radius)
    ctx.rotate(rotation)

    ctx.fillCircle(0.0, 0.0, radius, 0x17000000)  // subtle dark overlay (black @ ~9%)

    // The figure-8 channel.
    ctx.beginPath()
    ctx.moveTo(sx[0], -sy[0])
    for (d in 1 until PATH_DAYS) ctx.lineTo(sx[d], -sy[d])
    ctx.closePath()
    ctx.strokePath(channelColor, channelWidth)

    drawSeasonTicks(ctx, sx, sy, channelWidth)
    drawAnalemmaSun(ctx, sunX, -sunY, sunRadius, sunFill, sunStroke)

    ctx.restore()
    ctx.restore()
}

/** Colored square ticks straddling the channel at the four season points. */
private fun drawSeasonTicks(ctx: DrawingContext, sx: DoubleArray, sy: DoubleArray, channelWidth: Double) {
    val n = sx.size
    val tickLen = 2.0; val tickAlong = 0.5; val gap = channelWidth / 2
    for ((idx, color) in SEASON_TICKS) {
        val i = idx % n
        val prev = (i - 1 + n) % n; val next = (i + 1) % n
        val dx = sx[next] - sx[prev]; val dy = sy[next] - sy[prev]
        val len = Math.hypot(dx, dy)
        if (len == 0.0) continue
        val nx = -(dy / len); val ny = dx / len   // unit normal
        val px = sx[i]; val py = sy[i]
        for (side in intArrayOf(1, -1)) {
            val mx = px + side * nx * (gap + tickLen / 2)
            val my = py + side * ny * (gap + tickLen / 2)
            // small square centered at (mx, my); approximate as a filled rect (axis-aligned is fine at this size)
            ctx.fillRect(mx - tickAlong, -my - tickLen / 2, tickAlong * 2, tickLen, color)
        }
    }
}

/** Equation-of-time dial: arc backbone + minute ticks + ±15 labels + title. Ported from drawEotDial. */
internal fun drawEotDial(ctx: DrawingContext, part: EotDialPart, env: Environment) {
    val cx = evalAttr(part.x, env)
    val cy = evalAttr(part.y, env)
    val radius = evalAttr(part.radius, env).let { if (it <= 0) 20.0 else it }
    val color = if (part.strokeColor != null) evalColorArgb(part.strokeColor, env) else 0xFF000000.toInt()
    val fontSize = evalAttr(part.fontSize, env).let { if (it <= 0) 6.0 else it }
    val labelText = part.labelText ?: "Equation of Time"
    val radPerMin = PI / 30
    val fadedColor = (((color ushr 24) and 0xFF).let { (it * 0.20).toInt() } shl 24) or (color and 0x00FFFFFF)

    ctx.save()
    ctx.translate(cx, -cy)

    // Arc backbone between the EOT extremes, with a faded extension on the negative side.
    val arcDrawStart = -PI / 2 + (-14.2) * radPerMin
    val arcDrawEnd = -PI / 2 + 16.5 * radPerMin
    ctx.strokeArc(0.0, 0.0, radius, arcDrawStart, arcDrawEnd, color, 0.4)
    ctx.strokeArc(0.0, 0.0, radius, -PI / 2 + (-15.0) * radPerMin, arcDrawStart, fadedColor, 0.4)
    ctx.fillCircle(0.0, 0.0, 1.2, color)

    // Minute ticks (−15..+16), major every 5.
    val majorLen = radius * 0.15; val minorLen = radius * 0.07
    for (m in -15..16) {
        val a = -PI / 2 + m * radPerMin
        val major = m % 5 == 0 && abs(m) <= 15
        val inner = if (major) radius - majorLen else radius - minorLen
        val ca = cos(a); val sa = sin(a)
        val c = if (m == -15) fadedColor else color
        ctx.beginPath(); ctx.moveTo(ca * inner, sa * inner); ctx.lineTo(ca * radius, sa * radius)
        ctx.strokePath(c, if (major) 0.6 else 0.3)
    }

    // Numeric labels at the major ticks (0 at top, 5/10/15 on both sides).
    val labelFS = fontSize * 1.92
    ctx.setFont("Arial", labelFS)
    val labelR = radius - majorLen - labelFS * 0.55 - 1
    fun text(s: String, ang: Double, r: Double) = ctx.fillText(s, cos(ang) * r, sin(ang) * r + ctx.textVisualCenterY(), color)
    text("0", -PI / 2, labelR)
    for (m in intArrayOf(5, 10, 15)) {
        text(m.toString(), -PI / 2 + m * radPerMin, labelR)
        text(m.toString(), -PI / 2 - m * radPerMin, labelR)
    }
    // ± symbols, further in.
    val symR = labelR - labelFS - 2
    ctx.setFont("Arial", fontSize * 2.0)
    text("−", -PI / 2 - 15 * radPerMin, symR)
    text("+", -PI / 2 + 15 * radPerMin, symR)

    // Title below the arc.
    val titleFS = if (part.titleFontSize != null) evalAttr(part.titleFontSize, env) else fontSize * 3
    val titleYOff = if (part.titleYOffset != null) evalAttr(part.titleYOffset, env) else 0.0
    ctx.setFont("Arial", titleFS)
    val arcBottomY = sin(arcDrawEnd) * radius + 2 - titleYOff
    ctx.fillText(labelText, 0.0, arcBottomY + ctx.textVisualCenterY(), color)

    ctx.restore()
}

/** Sun glyph (8-ray star + disc) used as the analemma marker. */
private fun drawAnalemmaSun(ctx: DrawingContext, cx: Double, cy: Double, radius: Double, fill: Int, stroke: Int) {
    val inner = radius * 0.5
    val nRays = 8
    ctx.save()
    ctx.translate(cx, cy)
    ctx.beginPath()
    for (i in 0 until nRays) {
        val theta = 2 * PI * i / nRays
        ctx.moveTo(radius * cos(theta), radius * sin(theta))
        ctx.lineTo(inner * cos(theta + PI / nRays), inner * sin(theta + PI / nRays))
        ctx.lineTo(inner * cos(theta - PI / nRays), inner * sin(theta - PI / nRays))
        ctx.closePath()
    }
    ctx.fillPath(fill)
    ctx.fillCircle(0.0, 0.0, inner, fill)
    ctx.strokeCircle(0.0, 0.0, inner, stroke, 0.4)
    ctx.restore()
}
