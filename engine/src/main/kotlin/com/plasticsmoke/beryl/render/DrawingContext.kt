package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.expr.ASTNode
import com.plasticsmoke.beryl.expr.Environment
import com.plasticsmoke.beryl.expr.EvalError
import com.plasticsmoke.beryl.expr.evaluate

/**
 * Platform-independent drawing surface — the Canvas-2D subset the watch renderer needs.
 *
 * Mirrors the Cocoa→Canvas-2D mapping the web port uses; here it maps onto either
 * `android.graphics.Canvas` (the app) or `java.awt.Graphics2D` (headless tests).
 *
 * Coordinates are in the current transformed space. Colors are packed ARGB Ints
 * (0xAARRGGBB), matching the watch XML color convention.
 */
interface DrawingContext {
    fun save()
    fun restore()
    fun translate(dx: Double, dy: Double)
    fun rotate(radians: Double)
    fun scale(sx: Double, sy: Double)

    fun fillRect(x: Double, y: Double, w: Double, h: Double, argb: Int)
    fun fillCircle(cx: Double, cy: Double, r: Double, argb: Int)
    /** Filled annulus (even-odd): the area between [innerR] and [outerR]. */
    fun fillRing(cx: Double, cy: Double, outerR: Double, innerR: Double, argb: Int)
    fun strokeCircle(cx: Double, cy: Double, r: Double, argb: Int, lineWidth: Double)
    /** Filled ellipse with horizontal radius [rx] and vertical radius [ry]. */
    fun fillEllipse(cx: Double, cy: Double, rx: Double, ry: Double, argb: Int)
    /** Stroked ellipse outline with horizontal radius [rx] and vertical radius [ry]. */
    fun strokeEllipse(cx: Double, cy: Double, rx: Double, ry: Double, argb: Int, lineWidth: Double)
    /** Stroke a partial arc from [startRad] to [endRad] (radians, 0 = +X, CCW), no chord. */
    fun strokeArc(cx: Double, cy: Double, r: Double, startRad: Double, endRad: Double, argb: Int, lineWidth: Double)

    fun drawImage(image: ImageRef, dx: Double, dy: Double, dw: Double, dh: Double, alpha: Double)

    /**
     * Draw [image] scaled into the rect with the PDF/CSS 'luminosity' blend (iOS
     * kCGBlendModeLuminosity): the result keeps the backdrop's hue/saturation and takes the source's
     * luminosity — a gray brushed overlay turns a gold gear into brushed gold (Tombstone's mainspring
     * blender). Source alpha composites Porter-Duff over. Default: plain source-over (a platform
     * without the blend still shows the overlay).
     */
    fun drawImageLuminosity(image: ImageRef, dx: Double, dy: Double, dw: Double, dh: Double) =
        drawImage(image, dx, dy, dw, dh, 1.0)

    fun setFont(name: String, sizePx: Double)
    fun measureTextWidth(text: String): Double
    /** Y-offset to add to a target center so [fillText] (alphabetic baseline) is vertically centered. */
    fun textVisualCenterY(): Double
    /** Font line height (ascent+descent+leading) — iOS's NSString sizeWithFont height, for dial text placement. */
    fun textHeight(): Double
    /** Font ascent (baseline→top). iOS top-aligns dial text in its rect, i.e. baseline = top − ascent. */
    fun fontAscent(): Double

    /** Full font bounding-box height (extreme glyph bounds, > line height) — iOS's
     *  CGFontGetFontBBox, which anchors Terra's circular ring/subdial text. */
    fun fontBBoxHeight(): Double = textHeight()
    /** Draw [text] horizontally centered at [x], baseline-adjusted at [y]. */
    fun fillText(text: String, x: Double, y: Double, argb: Int)

    /**
     * Like [fillText] but composited with a difference blend (|dst−src| per channel), so white text
     * auto-contrasts with whatever is beneath — white over black → white, white over white → black.
     * Used for numbers straddling a half-black/half-white dial (Terra's 24-hour ring 6/18). iOS uses
     * kCGBlendModeDifference for the same effect.
     */
    fun fillTextDifference(text: String, x: Double, y: Double)

    // Path API — for arbitrary polygon shapes (hands). fill/stroke act on the current path
    // until the next beginPath(), matching Canvas 2D semantics.
    fun beginPath()
    fun moveTo(x: Double, y: Double)
    fun lineTo(x: Double, y: Double)
    /** Quadratic Bézier from the current point to (x,y) with control point (cpx,cpy). */
    fun quadraticCurveTo(cpx: Double, cpy: Double, x: Double, y: Double)
    fun closePath()
    fun fillPath(argb: Int)
    fun strokePath(argb: Int, lineWidth: Double)

    /** Intersect the clip region with a circle (used to round the face). Affected by save/restore. */
    fun clipCircle(cx: Double, cy: Double, r: Double)
    /** Intersect the clip region with a rectangle. Affected by save/restore. */
    fun clipRect(x: Double, y: Double, w: Double, h: Double)

    /**
     * Fill the annulus [innerR]..[outerR] with a radial gradient. Stop positions are 0 at [innerR]
     * and 1 at [outerR] (the bezel band), matching the web port's `createRadialGradient`.
     */
    fun fillRingRadialGradient(cx: Double, cy: Double, innerR: Double, outerR: Double, stops: List<GradientStop>)

    /** Fill the annulus with a linear gradient along the line (x0,y0)→(x1,y1). Source-over. */
    fun fillRingLinearGradient(
        cx: Double, cy: Double, innerR: Double, outerR: Double,
        x0: Double, y0: Double, x1: Double, y1: Double, stops: List<GradientStop>,
    )

    /**
     * Fill the annulus with a sweep/conic gradient around (cx,cy), stops 0..1 over a full turn
     * starting at [startAngleRad]. Source-over (which equals "screen" for white-on-mid-grey,
     * as the bezel specular uses). On Android this maps to SweepGradient.
     */
    fun fillRingSweepGradient(
        cx: Double, cy: Double, innerR: Double, outerR: Double,
        startAngleRad: Double, stops: List<GradientStop>,
    )

    // Offscreen-layer + hole-cutting, for window cutouts (a part is drawn to a layer, holes are
    // punched in it, then it's composited over what was drawn underneath — e.g. a moon porthole).
    fun pushLayer()
    fun popLayerToParent()
    fun cutCircle(cx: Double, cy: Double, r: Double)
    fun cutRect(x: Double, y: Double, w: Double, h: Double)

    /**
     * Finish the current layer (started with [pushLayer]) and return it as a reusable sprite
     * WITHOUT compositing it — the static-cache path bakes a run of parts once and re-blits the
     * sprite every subsequent frame. Returns null if nothing was drawn into the layer.
     * Implementations may crop to the drawn region; the sprite remembers its device position.
     */
    fun captureLayerSprite(): LayerSprite?

    /** Blit a sprite captured by [captureLayerSprite] back at its device-space position. */
    fun drawSprite(sprite: LayerSprite)

    /**
     * Blit [sprite] rotated by [radians] about the device-space pivot, optionally translated by
     * ([dxDev],[dyDev]) device pixels first — the per-frame half of the baked-hand model: content
     * rasterizes once, then only the quad transform changes (the iOS per-part texture scheme).
     */
    fun drawSpriteRotated(
        sprite: LayerSprite, pivotXDev: Double, pivotYDev: Double, radians: Double,
        dxDev: Double = 0.0, dyDev: Double = 0.0,
    )

    /**
     * Build a drop-shadow sprite from [source]: its alpha silhouette at [opacity], Gaussian-blurred
     * by [sigmaDevicePx], colored black — baked ONCE per hand and re-blitted per frame (blur is
     * rotation-invariant, so rotating the baked shadow equals blurring the rotated silhouette).
     */
    fun makeShadowSprite(source: LayerSprite, sigmaDevicePx: Double, opacity: Double): LayerSprite?

    /** Map a point in the current local (transformed) space to device pixels. */
    fun devicePoint(x: Double, y: Double): DoubleArray

    /** Current device-space scale of the active transform (watch-units → pixels). */
    fun deviceScale(): Double
    /**
     * Pop the current layer, recolor it to a black silhouette at [opacity], Gaussian-blur it by
     * [sigmaDevicePx], and composite it onto the parent translated by ([dxDevicePx],[dyDevicePx])
     * in device space — a drop shadow that does NOT rotate with the captured content (matching iOS,
     * where the shadow falls in a fixed screen direction). Pairs with a preceding [pushLayer].
     */
    fun popLayerAsShadow(dxDevicePx: Double, dyDevicePx: Double, sigmaDevicePx: Double, opacity: Double)
}

/** A radial-gradient color stop: [pos] is 0..1 across the gradient band. */
data class GradientStop(val pos: Double, val argb: Int)

/** A captured layer: a device-space bitmap plus the device pixel position to re-blit it at. */
class LayerSprite(val image: ImageRef, val x: Int, val y: Int)

/** A platform bitmap, sized in pixels. */
interface ImageRef {
    val width: Int
    val height: Int
}

/** A loaded image plus the factor that converts its pixels to XML coordinate units (e.g. 1/4 for 4x assets). */
data class LoadedImage(val image: ImageRef, val scale: Double = 1.0)

// --- Attribute / color evaluation helpers (ported from astro-env.ts evalAttr/evalColor) ---

/**
 * Evaluate a numeric attribute expression; absent → 0. An undefined *variable* yields 0 (matching
 * the iOS VM, where some watch XML references unset color/number vars); an undefined *function*
 * still throws (it signals a not-yet-ported function — a real porting gap).
 */
fun evalAttr(expr: ASTNode?, env: Environment): Double {
    if (expr == null) return 0.0
    return try {
        evaluate(expr, env)
    } catch (e: EvalError) {
        if (e.message?.startsWith("Undefined variable") == true) 0.0 else throw e
    }
}

/** Evaluate a color attribute to a packed ARGB Int; absent or undefined-variable → transparent (0). */
fun evalColorArgb(expr: ASTNode?, env: Environment): Int {
    if (expr == null) return 0
    return try {
        (evaluate(expr, env).toLong() and 0xFFFFFFFFL).toInt()
    } catch (e: EvalError) {
        if (e.message?.startsWith("Undefined variable") == true) 0 else throw e
    }
}

/** True when the ARGB color is fully transparent (alpha byte == 0). */
fun isTransparentArgb(argb: Int): Boolean = (argb ushr 24) and 0xFF == 0

/**
 * Parse a CSS color string (`rgb(r,g,b)`, `#rgb`, `#rrggbb`) to opaque ARGB, used for the
 * bezelColor attribute. Falls back to silver. Ported from renderer.ts parseColorComponents.
 */
fun parseCssColorToArgb(css: String): Int {
    val c = css.trim().lowercase()
    val (r, g, b) = when {
        c.startsWith("#") -> {
            val hex = c.substring(1)
            if (hex.length == 3) {
                Triple(
                    "${hex[0]}${hex[0]}".toInt(16),
                    "${hex[1]}${hex[1]}".toInt(16),
                    "${hex[2]}${hex[2]}".toInt(16),
                )
            } else {
                Triple(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
            }
        }
        else -> {
            val m = Regex("""rgb\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)""").find(c)
            if (m != null) Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            else Triple(192, 192, 192)
        }
    }
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/** White at alpha [a] (0..255), as packed ARGB. */
fun whiteAlpha(a: Int): Int = (a shl 24) or 0x00FFFFFF

/** Black at alpha [a] (0..255), as packed ARGB. */
fun blackAlpha(a: Int): Int = (a shl 24)

/** Interpolate a color (incl. alpha) at position [t] (0..1) across an ordered list of [stops]. */
fun sampleGradient(stops: List<GradientStop>, t: Double): Int {
    if (stops.isEmpty()) return 0
    if (t <= stops.first().pos) return stops.first().argb
    if (t >= stops.last().pos) return stops.last().argb
    var i = 1
    while (i < stops.size && stops[i].pos < t) i++
    val lo = stops[i - 1]
    val hi = stops[i]
    val span = hi.pos - lo.pos
    val f = if (span <= 0) 0.0 else (t - lo.pos) / span
    fun lerp(shift: Int): Int {
        val a = (lo.argb ushr shift) and 0xFF
        val b = (hi.argb ushr shift) and 0xFF
        return (a + (b - a) * f).toInt().coerceIn(0, 255)
    }
    return (lerp(24) shl 24) or (lerp(16) shl 16) or (lerp(8) shl 8) or lerp(0)
}

/** Scale an ARGB color's RGB channels by [factor] (clamped to 0..255), preserving alpha. */
fun darkenArgb(argb: Int, factor: Double): Int = scaleRgb(argb, factor)
fun lightenArgb(argb: Int, factor: Double): Int = scaleRgb(argb, factor)

private fun scaleRgb(argb: Int, factor: Double): Int {
    val a = (argb ushr 24) and 0xFF
    val r = (((argb ushr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
    val g = (((argb ushr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
    val b = ((argb and 0xFF) * factor).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
