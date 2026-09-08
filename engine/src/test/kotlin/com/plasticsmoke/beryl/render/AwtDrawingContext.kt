package com.plasticsmoke.beryl.render

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage

/** A [DrawingContext] backed by `java.awt.Graphics2D`, for headless rendering in tests. */
class AwtDrawingContext(g0: Graphics2D, private val width: Int, private val height: Int) : DrawingContext {

    companion object {
        /**
         * Family names of any real iOS fonts (Arial, Times New Roman, …) found locally and
         * registered with AWT, so the preview can match the iPhone's exact glyph shapes instead of
         * the Liberation metric-clones. These are proprietary and git-ignored — drop the .ttf/.otf
         * into `docs/` (or set FONT_DIR) and they're picked up; absent, we fall back to Liberation.
         */
        val registeredFamilies: Set<String> by lazy {
            val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            // The test working dir varies (android/ vs android/engine/), so probe several candidates.
            val isFont = { f: java.io.File -> f.extension.lowercase() in setOf("ttf", "otf") }
            val dir = listOfNotNull(System.getenv("FONT_DIR"), "docs", "../docs", "android/docs")
                .map { java.io.File(it) }
                .firstOrNull { it.isDirectory && it.listFiles(isFont)?.isNotEmpty() == true }
            val found = mutableSetOf<String>()
            dir?.listFiles(isFont)?.forEach { file ->
                try {
                    val font = Font.createFont(Font.TRUETYPE_FONT, file)
                    ge.registerFont(font)
                    found.add(font.family)
                } catch (_: Exception) { /* skip unreadable/unsupported font files */ }
            }
            // Bundled free fonts (committed test resources; OFL-licensed). PT Sans substitutes
            // Verdana: it's the only free sans matching Verdana's underline-less ordinal º and
            // wedge-tapered apostrophe (Atlantis lat/long º′; DejaVu underlines its º).
            for (res in listOf("/font-ptsans-regular.ttf")) {
                try {
                    AwtDrawingContext::class.java.getResourceAsStream(res)?.use {
                        val font = Font.createFont(Font.TRUETYPE_FONT, it)
                        ge.registerFont(font)
                        found.add(font.family)
                    }
                } catch (_: Exception) { /* skip */ }
            }
            println("Registered local fonts from ${dir?.absolutePath}: $found")
            found
        }
    }

    private var g: Graphics2D = g0
    private val layers = ArrayDeque<Pair<Graphics2D, BufferedImage>>()

    private data class GState(val transform: AffineTransform, val clip: java.awt.Shape?)
    private val states = ArrayDeque<GState>()

    private fun applyHints(gg: Graphics2D) {
        gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        gg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        // Java2D defaults image interpolation to NEAREST_NEIGHBOR even with RENDER_QUALITY, which makes
        // rotated/scaled bitmaps (notably the rotating hands) jagged. BICUBIC resamples them smoothly —
        // Android's Canvas uses bilinear+ filtering by default, so this also tracks the on-device look.
        gg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    }

    init { applyHints(g) }

    override fun pushLayer() {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val lg = img.createGraphics()
        applyHints(lg)
        lg.transform = g.transform   // inherit the current transform
        layers.addLast(g to img)
        g = lg
    }

    override fun popLayerToParent() {
        val (parentG, img) = layers.removeLast()
        g.dispose()
        val saved = parentG.transform
        parentG.transform = AffineTransform()   // composite at device pixels
        parentG.drawImage(img, 0, 0, null)
        parentG.transform = saved
        g = parentG
    }

    override fun captureLayerSprite(): LayerSprite? {
        val (parentG, img) = layers.removeLast()
        g.dispose()
        g = parentG
        // No dirty tracking in the test adapter: keep the full frame (fine for headless tests).
        return LayerSprite(AwtImage(img), 0, 0)
    }

    override fun drawSprite(sprite: LayerSprite) {
        val saved = g.transform
        g.transform = AffineTransform()
        g.drawImage((sprite.image as AwtImage).buffered, sprite.x, sprite.y, null)
        g.transform = saved
    }

    override fun drawSpriteRotated(
        sprite: LayerSprite, pivotXDev: Double, pivotYDev: Double, radians: Double,
        dxDev: Double, dyDev: Double,
    ) {
        val saved = g.transform
        val t = AffineTransform()
        t.translate(pivotXDev + dxDev, pivotYDev + dyDev)
        t.rotate(radians)
        t.translate(-pivotXDev, -pivotYDev)
        g.transform = t
        g.drawImage((sprite.image as AwtImage).buffered, sprite.x, sprite.y, null)
        g.transform = saved
    }

    override fun makeShadowSprite(source: LayerSprite, sigmaDevicePx: Double, opacity: Double): LayerSprite? {
        val src = (source.image as AwtImage).buffered
        val radius = Math.ceil(3 * sigmaDevicePx).toInt().coerceAtLeast(1)
        val bw = src.width + 2 * radius
        val bh = src.height + 2 * radius
        val alpha = FloatArray(bw * bh)
        for (yy in 0 until src.height) for (xx in 0 until src.width) {
            val a = (src.getRGB(xx, yy) ushr 24) and 0xFF
            if (a != 0) alpha[(yy + radius) * bw + (xx + radius)] = (a * opacity).toFloat()
        }
        val blurred = blurAlpha(alpha, bw, bh, sigmaDevicePx)
        val out = BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB)
        for (yy in 0 until bh) for (xx in 0 until bw) {
            val a = blurred[yy * bw + xx].toInt().coerceIn(0, 255)
            if (a != 0) out.setRGB(xx, yy, a shl 24)
        }
        return LayerSprite(AwtImage(out), source.x - radius, source.y - radius)
    }

    override fun devicePoint(x: Double, y: Double): DoubleArray {
        val p = java.awt.geom.Point2D.Double(x, y)
        g.transform.transform(p, p)
        return doubleArrayOf(p.x, p.y)
    }

    override fun deviceScale(): Double {
        val t = g.transform
        return Math.hypot(t.scaleX, t.shearY)  // magnitude of the transformed x-axis
    }

    override fun popLayerAsShadow(dxDevicePx: Double, dyDevicePx: Double, sigmaDevicePx: Double, opacity: Double) {
        val (parentG, img) = layers.removeLast()
        g.dispose()
        g = parentG

        // Bounding box of the silhouette, padded by the blur radius so the fade fully fits.
        val radius = Math.ceil(3 * sigmaDevicePx).toInt().coerceAtLeast(1)
        val bbox = opaqueBounds(img) ?: return
        val x0 = (bbox[0] - radius).coerceAtLeast(0)
        val y0 = (bbox[1] - radius).coerceAtLeast(0)
        val x1 = (bbox[2] + radius).coerceAtMost(width - 1)
        val y1 = (bbox[3] + radius).coerceAtMost(height - 1)
        val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
        if (bw <= 0 || bh <= 0) return

        // Alpha buffer (silhouette × opacity), Gaussian-blurred with proper edge fade (out-of-bounds
        // contributes 0 — every output pixel is computed, so no hard crop edge), then composited black.
        val alpha = FloatArray(bw * bh)
        for (yy in 0 until bh) for (xx in 0 until bw) {
            val a = (img.getRGB(x0 + xx, y0 + yy) ushr 24) and 0xFF
            if (a != 0) alpha[yy * bw + xx] = (a * opacity).toFloat()
        }
        val blurred = blurAlpha(alpha, bw, bh, sigmaDevicePx)
        val out = BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB)
        for (yy in 0 until bh) for (xx in 0 until bw) {
            val a = blurred[yy * bw + xx].toInt().coerceIn(0, 255)
            if (a != 0) out.setRGB(xx, yy, a shl 24)
        }

        val saved = parentG.transform
        parentG.transform = AffineTransform()
        parentG.drawImage(out, x0 + Math.round(dxDevicePx).toInt(), y0 + Math.round(dyDevicePx).toInt(), null)
        parentG.transform = saved
    }

    /** Separable Gaussian blur of an alpha plane; out-of-bounds samples contribute 0 (clean fade). */
    private fun blurAlpha(src: FloatArray, w: Int, h: Int, sigma: Double): FloatArray {
        if (sigma <= 0.05) return src
        val radius = Math.ceil(3 * sigma).toInt().coerceAtLeast(1)
        val k = DoubleArray(2 * radius + 1)
        var sum = 0.0
        for (i in -radius..radius) { val v = Math.exp(-(i * i) / (2 * sigma * sigma)); k[i + radius] = v; sum += v }
        for (i in k.indices) k[i] /= sum
        val tmp = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var acc = 0.0
            for (i in -radius..radius) { val xx = x + i; if (xx in 0 until w) acc += src[y * w + xx] * k[i + radius] }
            tmp[y * w + x] = acc.toFloat()
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var acc = 0.0
            for (i in -radius..radius) { val yy = y + i; if (yy in 0 until h) acc += tmp[yy * w + x] * k[i + radius] }
            out[y * w + x] = acc.toFloat()
        }
        return out
    }

    /** [minX, minY, maxX, maxY] of non-transparent pixels, or null if fully transparent. */
    private fun opaqueBounds(img: BufferedImage): IntArray? {
        var minX = width; var minY = height; var maxX = -1; var maxY = -1
        for (yy in 0 until height) for (xx in 0 until width) {
            if ((img.getRGB(xx, yy) ushr 24) != 0) {
                if (xx < minX) minX = xx; if (xx > maxX) maxX = xx
                if (yy < minY) minY = yy; if (yy > maxY) maxY = yy
            }
        }
        return if (maxX < 0) null else intArrayOf(minX, minY, maxX, maxY)
    }


    override fun cutCircle(cx: Double, cy: Double, r: Double) {
        val old = g.composite
        g.composite = AlphaComposite.Clear
        g.fill(Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r))
        g.composite = old
    }

    override fun cutRect(x: Double, y: Double, w: Double, h: Double) {
        val old = g.composite
        g.composite = AlphaComposite.Clear
        g.fill(Rectangle2D.Double(x, y, w, h))
        g.composite = old
    }

    override fun save() { states.addLast(GState(g.transform, g.clip)) }
    override fun restore() {
        val s = states.removeLast()
        g.transform = s.transform
        g.clip = s.clip
    }
    override fun translate(dx: Double, dy: Double) = g.translate(dx, dy)
    override fun rotate(radians: Double) = g.rotate(radians)
    override fun scale(sx: Double, sy: Double) = g.scale(sx, sy)

    override fun fillRect(x: Double, y: Double, w: Double, h: Double, argb: Int) {
        g.color = color(argb)
        g.fill(Rectangle2D.Double(x, y, w, h))
    }

    override fun fillCircle(cx: Double, cy: Double, r: Double, argb: Int) {
        g.color = color(argb)
        g.fill(Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r))
    }

    override fun fillRing(cx: Double, cy: Double, outerR: Double, innerR: Double, argb: Int) {
        val area = Area(Ellipse2D.Double(cx - outerR, cy - outerR, 2 * outerR, 2 * outerR))
        area.subtract(Area(Ellipse2D.Double(cx - innerR, cy - innerR, 2 * innerR, 2 * innerR)))
        g.color = color(argb)
        g.fill(area)
    }

    override fun strokeCircle(cx: Double, cy: Double, r: Double, argb: Int, lineWidth: Double) {
        g.color = color(argb)
        g.stroke = BasicStroke(lineWidth.toFloat())
        g.draw(Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r))
    }

    override fun fillEllipse(cx: Double, cy: Double, rx: Double, ry: Double, argb: Int) {
        g.color = color(argb)
        g.fill(Ellipse2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry))
    }

    override fun strokeEllipse(cx: Double, cy: Double, rx: Double, ry: Double, argb: Int, lineWidth: Double) {
        g.color = color(argb)
        g.stroke = BasicStroke(lineWidth.toFloat())
        g.draw(Ellipse2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry))
    }

    override fun strokeArc(cx: Double, cy: Double, r: Double, startRad: Double, endRad: Double, argb: Int, lineWidth: Double) {
        g.color = color(argb)
        g.stroke = BasicStroke(lineWidth.toFloat())
        // Arc2D angles are in degrees, CCW positive; our user space is Y-down so this matches radians directly.
        val startDeg = Math.toDegrees(startRad)
        val extentDeg = Math.toDegrees(endRad - startRad)
        g.draw(java.awt.geom.Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, -startDeg, -extentDeg, java.awt.geom.Arc2D.OPEN))
    }

    override fun drawImage(image: ImageRef, dx: Double, dy: Double, dw: Double, dh: Double, alpha: Double) {
        val buf = (image as AwtImage).buffered
        val old = g.composite
        if (alpha < 1.0) g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.toFloat())
        val at = AffineTransform()
        at.translate(dx, dy)
        at.scale(dw / buf.width, dh / buf.height)
        g.drawImage(buf, at, null)
        g.composite = old
    }

    override fun drawImageLuminosity(image: ImageRef, dx: Double, dy: Double, dw: Double, dh: Double) {
        val buf = (image as AwtImage).buffered
        val old = g.composite
        g.composite = LuminosityComposite
        val at = AffineTransform()
        at.translate(dx, dy)
        at.scale(dw / buf.width, dh / buf.height)
        g.drawImage(buf, at, null)
        g.composite = old
    }

    override fun setFont(name: String, sizePx: Double) {
        // The watch XML names iOS fonts (Arial, Helvetica, Times New Roman). Those aren't installed
        // on a typical Linux CI box, so AWT silently falls back to the wide "Dialog"/DejaVu face,
        // making text overflow. Map to the metric-compatible Liberation faces for a faithful preview
        // (Android will use its own platform fonts).
        val n = name.lowercase()
        // Prefer the real iOS font if it was registered locally (exact glyph shapes), else the
        // metric-compatible Liberation clone.
        fun pick(real: String, clone: String) = if (real in registeredFamilies) real else clone
        val (family, style) = when {
            // Liberation Serif carries EXACTLY Times New Roman's metrics (hhea 0.891/-0.216) —
            // and the faces' ring geometry is designed against those metrics (e.g. Thebes bands
            // are hourRad − fontSize − 1), so metric fidelity beats glyph-shape fidelity here.
            // Nimbus Roman (tried 2026-07-02) has Times-true SHAPES but Type1-era metrics
            // (ascent 0.683, lineGap 0.2) that pushed demi numerals off-center into the ring
            // borders; TeX Gyre Termes likewise. Layout correctness wins: Liberation Serif.
            "times" in n || "serif" in n -> pick("Times New Roman", "Liberation Serif") to (if ("bold" in n) Font.BOLD else Font.PLAIN)
            "courier" in n || "mono" in n -> pick("Courier New", "Liberation Mono") to (if ("bold" in n) Font.BOLD else Font.PLAIN)
            "helvetica" in n -> pick("Helvetica World", "Liberation Sans") to (if ("bold" in n) Font.BOLD else Font.PLAIN)
            // PT Sans (bundled, OFL): the only free sans whose º has no underline and whose
            // apostrophe tapers like Verdana's (iPhone-verified on Atlantis' lat/long symbols).
            "verdana" in n -> pick("Verdana", "PT Sans") to (if ("bold" in n) Font.BOLD else Font.PLAIN)
            else -> pick("Arial", "Liberation Sans") to (if ("bold" in n) Font.BOLD else Font.PLAIN)
        }
        g.font = Font(family, style, 1).deriveFont(sizePx.toFloat())
    }

    // A CJK-capable system font (Droid Sans Fallback etc.), for glyphs the named iOS fonts lack —
    // e.g. Kyoto's Japanese zodiac signs/numerals (午未申…, 九八七…). Android will use its platform CJK.
    private val cjkFont: Font? by lazy {
        sequenceOf("Droid Sans Fallback", "Noto Sans CJK JP", "Noto Sans CJK SC", "WenQuanYi Zen Hei", "Dialog")
            .map { Font(it, Font.PLAIN, 1) }
            .firstOrNull { it.canDisplay('午') }
    }

    /** The current font, or the CJK fallback (at the same size/style) when it can't display [text]. */
    private fun fontFor(text: String): Font {
        val cur = g.font
        if (text.all { it == ' ' || cur.canDisplay(it) }) return cur
        return cjkFont?.deriveFont(cur.style, cur.size2D) ?: cur
    }

    override fun measureTextWidth(text: String): Double =
        g.getFontMetrics(fontFor(text)).stringWidth(text).toDouble()

    // Fractional font metrics — AWT's legacy FontMetrics rounds ascent/descent to whole pixels
    // (e.g. Arial-11 descent 2.33 → 3), which biases vertical centering ~0.3u high. LineMetrics gives
    // the true sub-pixel values, matching the fractional CoreText metrics iOS centers against.
    private fun lineMetrics() = g.font.getLineMetrics("Mg", g.fontRenderContext)

    override fun textVisualCenterY(): Double {
        val lm = lineMetrics()
        return (lm.ascent - lm.descent) / 2.0
    }

    override fun textHeight(): Double = lineMetrics().height.toDouble()

    override fun fontAscent(): Double = lineMetrics().ascent.toDouble()

    override fun fontBBoxHeight(): Double =
        (g.fontMetrics.maxAscent + g.fontMetrics.maxDescent).toDouble()

    override fun fillText(text: String, x: Double, y: Double, argb: Int) {
        g.color = color(argb)
        val w = measureTextWidth(text)
        val saved = g.font; g.font = fontFor(text)
        g.drawString(text, (x - w / 2).toFloat(), y.toFloat())
        g.font = saved
    }

    override fun fillTextDifference(text: String, x: Double, y: Double) {
        val old = g.composite
        g.composite = DifferenceComposite
        g.color = Color.WHITE
        val w = measureTextWidth(text)
        g.drawString(text, (x - w / 2).toFloat(), y.toFloat())
        g.composite = old
    }

    override fun clipCircle(cx: Double, cy: Double, r: Double) {
        g.clip(Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r))
    }

    override fun clipRect(x: Double, y: Double, w: Double, h: Double) {
        g.clip(Rectangle2D.Double(x, y, w, h))
    }

    override fun fillRingRadialGradient(
        cx: Double, cy: Double, innerR: Double, outerR: Double, stops: List<GradientStop>,
    ) {
        val ring = Area(Ellipse2D.Double(cx - outerR, cy - outerR, 2 * outerR, 2 * outerR))
        ring.subtract(Area(Ellipse2D.Double(cx - innerR, cy - innerR, 2 * innerR, 2 * innerR)))

        // Map stop positions (0..1 over innerR..outerR) to fractions of outerR; prepend a 0 stop
        // (the inner disc is clipped away) so AWT's required fractions[0]==0 holds.
        val fractions = ArrayList<Float>()
        val colors = ArrayList<Color>()
        fractions.add(0f); colors.add(color(stops.first().argb))
        for (s in stops) {
            fractions.add(((innerR + s.pos * (outerR - innerR)) / outerR).toFloat())
            colors.add(color(s.argb))
        }
        val paint = java.awt.RadialGradientPaint(
            java.awt.geom.Point2D.Double(cx, cy), outerR.toFloat(),
            fractions.toFloatArray(), colors.toTypedArray(),
        )
        val old = g.paint
        g.paint = paint
        g.fill(ring)
        g.paint = old
    }

    override fun fillRingLinearGradient(
        cx: Double, cy: Double, innerR: Double, outerR: Double,
        x0: Double, y0: Double, x1: Double, y1: Double, stops: List<GradientStop>,
    ) {
        val ring = ringArea(cx, cy, innerR, outerR)
        val fractions = stops.map { it.pos.toFloat() }.toFloatArray()
        val colors = stops.map { color(it.argb) }.toTypedArray()
        val paint = java.awt.LinearGradientPaint(
            java.awt.geom.Point2D.Double(x0, y0), java.awt.geom.Point2D.Double(x1, y1), fractions, colors,
        )
        val old = g.paint
        g.paint = paint
        g.fill(ring)
        g.paint = old
    }

    override fun fillRingSweepGradient(
        cx: Double, cy: Double, innerR: Double, outerR: Double,
        startAngleRad: Double, stops: List<GradientStop>,
    ) {
        // AWT has no conic paint; approximate with N annular wedges sampled from the stops.
        val n = 180
        val startDeg = -Math.toDegrees(startAngleRad)  // user space is Y-down
        val step = 360.0 / n
        for (i in 0 until n) {
            val argb = sampleGradient(stops, (i + 0.5) / n)
            if ((argb ushr 24) and 0xFF == 0) continue  // skip fully transparent wedges
            val wedge = Area(
                java.awt.geom.Arc2D.Double(
                    cx - outerR, cy - outerR, 2 * outerR, 2 * outerR,
                    startDeg - i * step, -step, java.awt.geom.Arc2D.PIE,
                ),
            )
            wedge.subtract(Area(Ellipse2D.Double(cx - innerR, cy - innerR, 2 * innerR, 2 * innerR)))
            g.color = color(argb)
            g.fill(wedge)
        }
    }

    private fun ringArea(cx: Double, cy: Double, innerR: Double, outerR: Double): Area {
        val a = Area(Ellipse2D.Double(cx - outerR, cy - outerR, 2 * outerR, 2 * outerR))
        a.subtract(Area(Ellipse2D.Double(cx - innerR, cy - innerR, 2 * innerR, 2 * innerR)))
        return a
    }

    private var path = java.awt.geom.Path2D.Double()
    override fun beginPath() { path = java.awt.geom.Path2D.Double() }
    override fun moveTo(x: Double, y: Double) = path.moveTo(x, y)
    override fun lineTo(x: Double, y: Double) = path.lineTo(x, y)
    override fun quadraticCurveTo(cpx: Double, cpy: Double, x: Double, y: Double) = path.quadTo(cpx, cpy, x, y)
    override fun closePath() = path.closePath()
    override fun fillPath(argb: Int) { g.color = color(argb); g.fill(path) }
    override fun strokePath(argb: Int, lineWidth: Double) {
        g.color = color(argb)
        // CAP_BUTT to match the HTML5 canvas default (the web port assumes it). Java's default
        // CAP_SQUARE extends each open line by lineWidth/2 past its ends — for thick tick marks
        // (e.g. Vienna's ring-backg, markWidth=5) that 2.5-unit overhang fattens the day/night ring.
        g.stroke = BasicStroke(lineWidth.toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
        g.draw(path)
    }

    private fun color(argb: Int): Color {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val gg = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return Color(r, gg, b, a)
    }
}

/** A [BufferedImage]-backed [ImageRef]. */
class AwtImage(val buffered: BufferedImage) : ImageRef {
    override val width: Int get() = buffered.width
    override val height: Int get() = buffered.height
}

/**
 * Photoshop/CSS "difference" blend (|dst − src| per channel), honoring source alpha for AA edges.
 * White source over a black background yields white; over white yields black — so text on a
 * half-black/half-white dial splits per-pixel (Terra's 24-hour ring 6/18).
 */
/**
 * W3C/PDF non-separable "luminosity" blend (iOS kCGBlendModeLuminosity): B(Cb,Cs) =
 * SetLum(Cb, Lum(Cs)) — the backdrop keeps its hue/saturation and takes the source's luminosity —
 * composited with the standard Porter-Duff over alphas: Co = αs(1−αb)Cs + (1−αs)αbCb + αsαbB.
 * Where the backdrop is transparent the source shows through as-is (matching CoreGraphics).
 */
object LuminosityComposite : java.awt.Composite {
    private fun lum(r: Double, g: Double, b: Double) = 0.3 * r + 0.59 * g + 0.11 * b

    /** SetLum + ClipColor from the compositing spec; channels in 0..1. */
    private fun setLum(c: DoubleArray, l: Double) {
        val d = l - lum(c[0], c[1], c[2])
        for (i in 0..2) c[i] += d
        val cl = lum(c[0], c[1], c[2])
        val n = minOf(c[0], c[1], c[2])
        val x = maxOf(c[0], c[1], c[2])
        if (n < 0) for (i in 0..2) c[i] = cl + (c[i] - cl) * cl / (cl - n)
        if (x > 1) for (i in 0..2) c[i] = cl + (c[i] - cl) * (1 - cl) / (x - cl)
    }

    override fun createContext(
        srcColorModel: java.awt.image.ColorModel,
        dstColorModel: java.awt.image.ColorModel,
        hints: RenderingHints?,
    ): java.awt.CompositeContext = object : java.awt.CompositeContext {
        override fun dispose() {}
        override fun compose(src: java.awt.image.Raster, dstIn: java.awt.image.Raster, dstOut: java.awt.image.WritableRaster) {
            val w = minOf(src.width, dstIn.width); val h = minOf(src.height, dstIn.height)
            val sp = IntArray(4); val dp = IntArray(4)
            val bc = DoubleArray(3)
            for (yy in 0 until h) for (xx in 0 until w) {
                src.getPixel(xx, yy, sp); dstIn.getPixel(xx, yy, dp)
                val sa = (if (src.numBands > 3) sp[3] else 255) / 255.0
                if (sa == 0.0) { dstOut.setPixel(xx, yy, dp); continue }
                val ba = dp[3] / 255.0
                val sl = lum(sp[0] / 255.0, sp[1] / 255.0, sp[2] / 255.0)
                bc[0] = dp[0] / 255.0; bc[1] = dp[1] / 255.0; bc[2] = dp[2] / 255.0
                setLum(bc, sl)
                val ao = sa + ba * (1 - sa)
                for (c in 0..2) {
                    val cs = sp[c] / 255.0; val cb = dp[c] / 255.0
                    val co = sa * (1 - ba) * cs + (1 - sa) * ba * cb + sa * ba * bc[c]
                    dp[c] = ((co / ao) * 255.0).toInt().coerceIn(0, 255)
                }
                dp[3] = (ao * 255.0).toInt().coerceIn(0, 255)
                dstOut.setPixel(xx, yy, dp)
            }
        }
    }
}

object DifferenceComposite : java.awt.Composite {
    override fun createContext(
        srcColorModel: java.awt.image.ColorModel,
        dstColorModel: java.awt.image.ColorModel,
        hints: RenderingHints?,
    ): java.awt.CompositeContext = object : java.awt.CompositeContext {
        override fun dispose() {}
        override fun compose(src: java.awt.image.Raster, dstIn: java.awt.image.Raster, dstOut: java.awt.image.WritableRaster) {
            val w = minOf(src.width, dstIn.width); val h = minOf(src.height, dstIn.height)
            val sp = IntArray(4); val dp = IntArray(4)
            for (yy in 0 until h) for (xx in 0 until w) {
                src.getPixel(xx, yy, sp); dstIn.getPixel(xx, yy, dp)
                val a = sp[3] / 255.0
                for (c in 0..2) {
                    val diff = kotlin.math.abs(dp[c] - sp[c])
                    dp[c] = (dp[c] * (1 - a) + diff * a).toInt().coerceIn(0, 255)
                }
                dstOut.setPixel(xx, yy, dp)
            }
        }
    }
}
