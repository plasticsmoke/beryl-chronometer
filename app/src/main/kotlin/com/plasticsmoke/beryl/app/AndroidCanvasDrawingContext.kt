package com.plasticsmoke.beryl.app

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import com.plasticsmoke.beryl.render.DrawingContext
import com.plasticsmoke.beryl.render.GradientStop
import com.plasticsmoke.beryl.render.ImageRef
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * A [DrawingContext] backed by `android.graphics.Canvas`, drawing into a software Bitmap.
 *
 * Mirrors the semantics of the test-side AwtDrawingContext: an explicit offscreen-layer stack
 * (window cutouts punch holes with PorterDuff.CLEAR inside a layer, then the layer composites
 * over the parent at device pixels), fractional font metrics, and the difference/luminosity
 * blends via [BlendMode] (API 29+).
 *
 * Every primitive drawn inside a layer unions its (conservative, device-space) bounds into the
 * layer's dirty rect. That keeps layer costs proportional to CONTENT size, not frame size:
 * shadows crop to the silhouette before the native blur, composites blit only the dirty region,
 * and pooled bitmaps erase only what was touched — Terra draws 31 hand shadows per frame, so
 * full-frame passes per layer are unaffordable.
 *
 * The current transform is tracked in [cur] (mirroring every translate/rotate/scale and
 * save/restore) rather than read back from the Canvas — Canvas.getMatrix is deprecated and
 * unreliable, and a new layer's canvas must inherit the transform anyway.
 */
class AndroidCanvasDrawingContext(
    canvas0: Canvas,
    private val width: Int,
    private val height: Int,
    private val assets: AssetManager? = null,
    private val layerPool: LayerBitmapPool? = null,
) : DrawingContext {

    private class LayerRec(val canvas: Canvas, val bmp: Bitmap?) {
        val dirty = RectF()
        var hasDirty = false
    }

    private var current = LayerRec(canvas0, null)
    private val stack = ArrayDeque<LayerRec>()
    private val canvas: Canvas get() = current.canvas

    private var cur = Matrix()
    private val matrixStack = ArrayDeque<Matrix>()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT   // HTML5-canvas default; the engine assumes it for tick marks
        strokeJoin = Paint.Join.MITER
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Debug counters: [0]=ms in popLayerAsShadow, [1]=shadow calls, [2]=ms in drawImage. */
    val stats = LongArray(3)

    // --- dirty tracking ---

    private val tmpRect = RectF()

    /** Union a local-space rect (padded by [pad] local units) into the current layer's dirty box. */
    private fun markDirty(l: Double, t: Double, r: Double, b: Double, pad: Double = 0.0) {
        if (current.bmp == null) return   // root canvas: no layer bookkeeping needed
        tmpRect.set((l - pad).toFloat(), (t - pad).toFloat(), (r + pad).toFloat(), (b + pad).toFloat())
        cur.mapRect(tmpRect)
        tmpRect.inset(-2f, -2f)   // anti-aliasing slop in device px
        if (current.hasDirty) current.dirty.union(tmpRect) else current.dirty.set(tmpRect)
        current.hasDirty = true
    }

    /** The layer's dirty rect clipped to the frame and rounded out; null if nothing was drawn. */
    private fun dirtyDeviceRect(layer: LayerRec, padDevicePx: Int = 0): Rect? {
        if (!layer.hasDirty) return null
        val r = Rect()
        layer.dirty.roundOut(r)
        r.inset(-padDevicePx, -padDevicePx)
        if (!r.intersect(0, 0, width, height)) return null
        return r
    }

    // --- transform & state ---

    override fun save() {
        canvas.save()
        matrixStack.addLast(Matrix(cur))
    }

    override fun restore() {
        canvas.restore()
        cur = matrixStack.removeLast()
    }

    override fun translate(dx: Double, dy: Double) {
        canvas.translate(dx.toFloat(), dy.toFloat())
        cur.preTranslate(dx.toFloat(), dy.toFloat())
    }

    override fun rotate(radians: Double) {
        val deg = Math.toDegrees(radians).toFloat()
        canvas.rotate(deg)
        cur.preRotate(deg)
    }

    override fun scale(sx: Double, sy: Double) {
        canvas.scale(sx.toFloat(), sy.toFloat())
        cur.preScale(sx.toFloat(), sy.toFloat())
    }

    override fun deviceScale(): Double {
        val v = FloatArray(9)
        cur.getValues(v)
        // magnitude of the transformed x-axis
        return hypot(v[Matrix.MSCALE_X].toDouble(), v[Matrix.MSKEW_Y].toDouble())
    }

    // --- layers ---

    override fun pushLayer() {
        val bmp = layerPool?.acquire()
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                .also { it.density = Bitmap.DENSITY_NONE }
        val lc = Canvas(bmp)
        lc.setMatrix(cur)   // inherit the current transform (clip is NOT inherited, matching AWT)
        stack.addLast(current)
        current = LayerRec(lc, bmp)
    }

    override fun popLayerToParent() {
        val layer = current
        current = stack.removeLast()
        val bmp = layer.bmp ?: return
        val r = dirtyDeviceRect(layer)
        if (r != null) {
            canvas.save()
            canvas.setMatrix(IDENTITY)   // composite at device pixels
            canvas.drawBitmap(bmp, r, r, null)
            canvas.restore()
            // What the layer drew is now part of the parent's content.
            markDirtyDevice(r)
        }
        layerPool?.release(bmp, r)
    }

    /** Union an already-device-space rect into the (layer) parent's dirty box. */
    private fun markDirtyDevice(r: Rect) {
        if (current.bmp == null) return
        tmpRect.set(r)
        if (current.hasDirty) current.dirty.union(tmpRect) else current.dirty.set(tmpRect)
        current.hasDirty = true
    }

    override fun captureLayerSprite(): com.plasticsmoke.beryl.render.LayerSprite? {
        val layer = current
        current = stack.removeLast()
        val bmp = layer.bmp ?: return null
        val r = dirtyDeviceRect(layer)
        if (r == null || r.width() <= 0 || r.height() <= 0) {
            layerPool?.release(bmp, null)
            return null
        }
        // Crop to the drawn region: cached static runs live for the face's lifetime, so a
        // full-frame 10 MB bitmap per run would add up fast across faces and modes.
        // CAUTION: when the crop equals the whole bitmap, createBitmap returns the SOURCE
        // OBJECT, not a copy — releasing it to the pool then aliases the "immutable" sprite to
        // a scratch buffer that gets erased/redrawn every frame (Tombstone's full-height band
        // art hit this: the watch vanished into flickering fragments). Keep ownership instead.
        val crop = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
        if (crop !== bmp) layerPool?.release(bmp, r)
        return com.plasticsmoke.beryl.render.LayerSprite(AndroidImage(crop), r.left, r.top)
    }

    // Per-blit scratch (sprites blit ~50×/frame at 60 fps — fresh Matrix/Rect per call was
    // ~6000 allocations/s of GC pressure, showing up as occasional 50-80 ms frame spikes).
    private val spriteMatrix = Matrix()
    private val spriteDirty = Rect()

    override fun drawSprite(sprite: com.plasticsmoke.beryl.render.LayerSprite) {
        val bmp = (sprite.image as AndroidImage).bitmap
        canvas.save()
        canvas.setMatrix(IDENTITY)
        canvas.drawBitmap(bmp, sprite.x.toFloat(), sprite.y.toFloat(), null)
        canvas.restore()
        spriteDirty.set(sprite.x, sprite.y, sprite.x + bmp.width, sprite.y + bmp.height)
        markDirtyDevice(spriteDirty)
    }

    override fun drawSpriteRotated(
        sprite: com.plasticsmoke.beryl.render.LayerSprite, pivotXDev: Double, pivotYDev: Double, radians: Double,
        dxDev: Double, dyDev: Double,
    ) {
        val bmp = (sprite.image as AndroidImage).bitmap
        val m = spriteMatrix
        m.reset()
        m.preTranslate((pivotXDev + dxDev).toFloat(), (pivotYDev + dyDev).toFloat())
        m.preRotate(Math.toDegrees(radians).toFloat())
        m.preTranslate((-pivotXDev).toFloat(), (-pivotYDev).toFloat())
        canvas.save()
        canvas.setMatrix(m)
        canvas.drawBitmap(bmp, sprite.x.toFloat(), sprite.y.toFloat(), bitmapPaint)
        canvas.restore()
        tmpRect.set(
            sprite.x.toFloat(), sprite.y.toFloat(),
            (sprite.x + bmp.width).toFloat(), (sprite.y + bmp.height).toFloat(),
        )
        m.mapRect(tmpRect)
        tmpRect.roundOut(spriteDirty)
        spriteDirty.inset(-2, -2)
        markDirtyDevice(spriteDirty)
    }

    override fun makeShadowSprite(
        source: com.plasticsmoke.beryl.render.LayerSprite, sigmaDevicePx: Double, opacity: Double,
    ): com.plasticsmoke.beryl.render.LayerSprite? {
        val src = (source.image as AndroidImage).bitmap
        val blurPaint = Paint()
        if (sigmaDevicePx > 0.5) {
            val radius = ((sigmaDevicePx - 0.5) / 0.57735).toFloat()
            blurPaint.maskFilter = BlurMaskFilter(radius.coerceAtLeast(0.1f), BlurMaskFilter.Blur.NORMAL)
        }
        val offset = IntArray(2)
        val alpha = src.extractAlpha(blurPaint, offset)
        // Pre-colorize: an ALPHA_8 mask drawn with a black paint at the shadow opacity, so the
        // per-frame path is a plain bitmap blit.
        val out = Bitmap.createBitmap(alpha.width, alpha.height, Bitmap.Config.ARGB_8888)
        out.density = Bitmap.DENSITY_NONE
        val c = Canvas(out)
        shadowPaint.color = Color.BLACK
        shadowPaint.alpha = (opacity * 255).toInt().coerceIn(0, 255)
        c.drawBitmap(alpha, 0f, 0f, shadowPaint)
        alpha.recycle()
        return com.plasticsmoke.beryl.render.LayerSprite(AndroidImage(out), source.x + offset[0], source.y + offset[1])
    }

    override fun devicePoint(x: Double, y: Double): DoubleArray {
        val pts = floatArrayOf(x.toFloat(), y.toFloat())
        cur.mapPoints(pts)
        return doubleArrayOf(pts[0].toDouble(), pts[1].toDouble())
    }

    override fun popLayerAsShadow(dxDevicePx: Double, dyDevicePx: Double, sigmaDevicePx: Double, opacity: Double) {
        val t0 = android.os.SystemClock.uptimeMillis()
        popLayerAsShadowImpl(dxDevicePx, dyDevicePx, sigmaDevicePx, opacity)
        stats[0] += android.os.SystemClock.uptimeMillis() - t0
        stats[1]++
    }

    private fun popLayerAsShadowImpl(dxDevicePx: Double, dyDevicePx: Double, sigmaDevicePx: Double, opacity: Double) {
        val layer = current
        current = stack.removeLast()
        val bmp = layer.bmp ?: return

        // Crop to the silhouette (plus blur reach) BEFORE the native blur — extractAlpha on the
        // full frame costs ~65 ms; on a hand-sized crop it's ~1 ms.
        val pad = ceil(3 * sigmaDevicePx).toInt() + 2
        val r = dirtyDeviceRect(layer, pad)
        if (r == null || r.width() <= 0 || r.height() <= 0) {
            layerPool?.release(bmp, null)
            return
        }
        // createBitmap may return the source itself for a whole-bitmap crop (see
        // captureLayerSprite) — never recycle or double-release the pooled bitmap then.
        val crop = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())

        // Skia's NORMAL blur uses sigma = 0.57735 * radius + 0.5, so invert for our sigma.
        val blurPaint = Paint()
        if (sigmaDevicePx > 0.5) {
            val radius = ((sigmaDevicePx - 0.5) / 0.57735).toFloat()
            blurPaint.maskFilter = BlurMaskFilter(radius.coerceAtLeast(0.1f), BlurMaskFilter.Blur.NORMAL)
        }
        val offset = IntArray(2)
        val alphaBmp = crop.extractAlpha(blurPaint, offset)
        if (crop !== bmp) crop.recycle()
        layerPool?.release(bmp, r)

        shadowPaint.color = Color.BLACK
        shadowPaint.alpha = (opacity * 255).toInt().coerceIn(0, 255)
        val dx = (r.left + offset[0] + dxDevicePx).toFloat()
        val dy = (r.top + offset[1] + dyDevicePx).toFloat()
        canvas.save()
        canvas.setMatrix(IDENTITY)
        canvas.drawBitmap(alphaBmp, dx, dy, shadowPaint)
        canvas.restore()
        markDirtyDevice(
            Rect(dx.toInt(), dy.toInt(), dx.toInt() + alphaBmp.width, dy.toInt() + alphaBmp.height),
        )
        alphaBmp.recycle()
    }

    override fun cutCircle(cx: Double, cy: Double, r: Double) {
        canvas.drawCircle(cx.toFloat(), cy.toFloat(), r.toFloat(), clearPaint)
    }

    override fun cutRect(x: Double, y: Double, w: Double, h: Double) {
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), clearPaint)
    }

    // --- primitives ---

    override fun fillRect(x: Double, y: Double, w: Double, h: Double, argb: Int) {
        fillPaint.color = argb
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), fillPaint)
        markDirty(x, y, x + w, y + h)
    }

    override fun fillCircle(cx: Double, cy: Double, r: Double, argb: Int) {
        fillPaint.color = argb
        canvas.drawCircle(cx.toFloat(), cy.toFloat(), r.toFloat(), fillPaint)
        markDirty(cx - r, cy - r, cx + r, cy + r)
    }

    override fun fillRing(cx: Double, cy: Double, outerR: Double, innerR: Double, argb: Int) {
        fillPaint.color = argb
        canvas.drawPath(ringPath(cx, cy, innerR, outerR), fillPaint)
        markDirty(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
    }

    override fun strokeCircle(cx: Double, cy: Double, r: Double, argb: Int, lineWidth: Double) {
        strokePaint.color = argb
        strokePaint.strokeWidth = lineWidth.toFloat()
        canvas.drawCircle(cx.toFloat(), cy.toFloat(), r.toFloat(), strokePaint)
        markDirty(cx - r, cy - r, cx + r, cy + r, lineWidth / 2)
    }

    override fun fillEllipse(cx: Double, cy: Double, rx: Double, ry: Double, argb: Int) {
        fillPaint.color = argb
        canvas.drawOval(rectF(cx - rx, cy - ry, 2 * rx, 2 * ry), fillPaint)
        markDirty(cx - rx, cy - ry, cx + rx, cy + ry)
    }

    override fun strokeEllipse(cx: Double, cy: Double, rx: Double, ry: Double, argb: Int, lineWidth: Double) {
        strokePaint.color = argb
        strokePaint.strokeWidth = lineWidth.toFloat()
        canvas.drawOval(rectF(cx - rx, cy - ry, 2 * rx, 2 * ry), strokePaint)
        markDirty(cx - rx, cy - ry, cx + rx, cy + ry, lineWidth / 2)
    }

    override fun strokeArc(
        cx: Double, cy: Double, r: Double, startRad: Double, endRad: Double, argb: Int, lineWidth: Double,
    ) {
        strokePaint.color = argb
        strokePaint.strokeWidth = lineWidth.toFloat()
        // Canvas arc angles are degrees, 0 = +X, positive = clockwise on screen — which in our
        // Y-down user space is exactly increasing radians, so no negation (unlike AWT's Arc2D).
        val startDeg = Math.toDegrees(startRad).toFloat()
        val sweepDeg = Math.toDegrees(endRad - startRad).toFloat()
        canvas.drawArc(rectF(cx - r, cy - r, 2 * r, 2 * r), startDeg, sweepDeg, false, strokePaint)
        markDirty(cx - r, cy - r, cx + r, cy + r, lineWidth / 2)
    }

    override fun drawImage(image: ImageRef, dx: Double, dy: Double, dw: Double, dh: Double, alpha: Double) {
        val t0 = android.os.SystemClock.uptimeMillis()
        val bmp = (image as AndroidImage).bitmap
        bitmapPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        canvas.drawBitmap(bmp, null, rectF(dx, dy, dw, dh), bitmapPaint)
        bitmapPaint.alpha = 255
        markDirty(dx, dy, dx + dw, dy + dh)
        stats[2] += android.os.SystemClock.uptimeMillis() - t0
    }

    override fun drawImageLuminosity(image: ImageRef, dx: Double, dy: Double, dw: Double, dh: Double) {
        val bmp = (image as AndroidImage).bitmap
        bitmapPaint.blendMode = BlendMode.LUMINOSITY
        canvas.drawBitmap(bmp, null, rectF(dx, dy, dw, dh), bitmapPaint)
        bitmapPaint.blendMode = null
        markDirty(dx, dy, dx + dw, dy + dh)
    }

    // --- text ---

    private val typefaceCache = HashMap<String, Typeface>()

    /**
     * Load a bundled font (assets/fonts/) by family, falling back to the platform default.
     * The watch XML names iOS fonts; we ship the same metric-compatible OFL faces the engine
     * previews were validated with (Liberation Sans/Serif/Mono = Arial/Times/Courier metrics,
     * PT Sans = Verdana shapes), so on-device text lands exactly where the previews put it.
     */
    private fun typeface(file: String, fallback: Typeface): Typeface =
        typefaceCache.getOrPut(file) {
            try {
                assets?.let { Typeface.createFromAsset(it, "fonts/$file") } ?: fallback
            } catch (_: Exception) {
                fallback
            }
        }

    override fun setFont(name: String, sizePx: Double) {
        val n = name.lowercase()
        val bold = "bold" in n
        val tf = when {
            "times" in n || "serif" in n ->
                typeface(if (bold) "LiberationSerif-Bold.ttf" else "LiberationSerif-Regular.ttf", Typeface.SERIF)
            "courier" in n || "mono" in n ->
                typeface(if (bold) "LiberationMono-Bold.ttf" else "LiberationMono-Regular.ttf", Typeface.MONOSPACE)
            "verdana" in n ->
                typeface("PTSans-Regular.ttf", Typeface.SANS_SERIF).let {
                    if (bold) Typeface.create(it, Typeface.BOLD) else it
                }
            else ->  // helvetica / arial / default
                typeface(if (bold) "LiberationSans-Bold.ttf" else "LiberationSans-Regular.ttf", Typeface.SANS_SERIF)
        }
        textPaint.typeface = tf
        textPaint.textSize = sizePx.toFloat()
    }

    // Android falls back to system fonts (incl. CJK) automatically for glyphs the typeface lacks,
    // so no explicit CJK handling is needed (unlike AWT).

    // The music glyphs ♪♫♬ (Istanbul/Thebes alarm-state indicator) live in Liberation Sans, but
    // its versions are thin and angular — iOS's "Arial" falls back to a heavier symbol face with a
    // curved flag and large round head. DejaVu Sans matches that shape/weight, so we render just
    // those glyphs from a subset of it (both the measure and the draw swap, so the wheel's radial
    // layout stays consistent with what's drawn). U+2669–U+266F is the musical-symbols block.
    private val musicTypeface: Typeface by lazy { typeface("DejaVuSans-music.ttf", Typeface.DEFAULT) }
    private fun hasMusicGlyph(s: String): Boolean = s.any { it.code in 0x2669..0x266F }
    private fun <T> withFontFor(text: String, block: () -> T): T {
        if (!hasMusicGlyph(text)) return block()
        val prev = textPaint.typeface
        textPaint.typeface = musicTypeface
        try { return block() } finally { textPaint.typeface = prev }
    }

    override fun measureTextWidth(text: String): Double =
        withFontFor(text) { textPaint.measureText(text).toDouble() }

    // Paint.FontMetrics is fractional (like the CoreText metrics iOS centers against);
    // ascent is negative-up in Android, so negate.
    override fun textVisualCenterY(): Double {
        val fm = textPaint.fontMetrics
        return (-fm.ascent - fm.descent) / 2.0
    }

    override fun textHeight(): Double {
        val fm = textPaint.fontMetrics
        return (fm.descent - fm.ascent + fm.leading).toDouble()
    }

    override fun fontAscent(): Double = (-textPaint.fontMetrics.ascent).toDouble()

    // fontMetrics top/bottom are the font's extreme glyph bounds — the CGFontGetFontBBox analog.
    override fun fontBBoxHeight(): Double {
        val fm = textPaint.fontMetrics
        return (fm.bottom - fm.top).toDouble()
    }

    override fun fillText(text: String, x: Double, y: Double, argb: Int) {
        textPaint.color = argb
        drawCenteredText(text, x, y)
    }

    override fun fillTextDifference(text: String, x: Double, y: Double) {
        textPaint.color = Color.WHITE
        textPaint.blendMode = BlendMode.DIFFERENCE
        drawCenteredText(text, x, y)
        textPaint.blendMode = null
    }

    private fun drawCenteredText(text: String, x: Double, y: Double) = withFontFor(text) {
        val w = textPaint.measureText(text)
        canvas.drawText(text, (x - w / 2).toFloat(), y.toFloat(), textPaint)
        val fm = textPaint.fontMetrics
        markDirty(x - w / 2, y + fm.ascent, x + w / 2, y + fm.descent, 1.0)
    }

    // --- path ---

    private var path = Path()
    override fun beginPath() { path = Path() }
    override fun moveTo(x: Double, y: Double) = path.moveTo(x.toFloat(), y.toFloat())
    override fun lineTo(x: Double, y: Double) = path.lineTo(x.toFloat(), y.toFloat())
    override fun quadraticCurveTo(cpx: Double, cpy: Double, x: Double, y: Double) =
        path.quadTo(cpx.toFloat(), cpy.toFloat(), x.toFloat(), y.toFloat())
    override fun closePath() = path.close()

    override fun fillPath(argb: Int) {
        fillPaint.color = argb
        canvas.drawPath(path, fillPaint)
        markDirtyPath(0.0)
    }

    override fun strokePath(argb: Int, lineWidth: Double) {
        strokePaint.color = argb
        strokePaint.strokeWidth = lineWidth.toFloat()
        canvas.drawPath(path, strokePaint)
        markDirtyPath(lineWidth / 2)
    }

    private val pathBounds = RectF()
    private fun markDirtyPath(pad: Double) {
        path.computeBounds(pathBounds, true)
        markDirty(
            pathBounds.left.toDouble(), pathBounds.top.toDouble(),
            pathBounds.right.toDouble(), pathBounds.bottom.toDouble(), pad,
        )
    }

    // --- clip ---

    override fun clipCircle(cx: Double, cy: Double, r: Double) {
        val p = Path()
        p.addCircle(cx.toFloat(), cy.toFloat(), r.toFloat(), Path.Direction.CW)
        canvas.clipPath(p)
    }

    override fun clipRect(x: Double, y: Double, w: Double, h: Double) {
        canvas.clipRect(rectF(x, y, w, h))
    }

    // --- gradients (bezel) ---

    override fun fillRingRadialGradient(
        cx: Double, cy: Double, innerR: Double, outerR: Double, stops: List<GradientStop>,
    ) {
        // Stop positions are 0 at innerR and 1 at outerR; map to fractions of outerR and prepend
        // a 0 stop (the inner disc is clipped away by the ring path).
        val colors = IntArray(stops.size + 1)
        val positions = FloatArray(stops.size + 1)
        colors[0] = stops.first().argb
        positions[0] = 0f
        for ((i, s) in stops.withIndex()) {
            colors[i + 1] = s.argb
            positions[i + 1] = ((innerR + s.pos * (outerR - innerR)) / outerR).toFloat()
        }
        fillPaint.shader = RadialGradient(
            cx.toFloat(), cy.toFloat(), outerR.toFloat(), colors, positions, Shader.TileMode.CLAMP,
        )
        canvas.drawPath(ringPath(cx, cy, innerR, outerR), fillPaint)
        fillPaint.shader = null
        markDirty(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
    }

    override fun fillRingLinearGradient(
        cx: Double, cy: Double, innerR: Double, outerR: Double,
        x0: Double, y0: Double, x1: Double, y1: Double, stops: List<GradientStop>,
    ) {
        fillPaint.shader = LinearGradient(
            x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(),
            IntArray(stops.size) { stops[it].argb },
            FloatArray(stops.size) { stops[it].pos.toFloat() },
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(ringPath(cx, cy, innerR, outerR), fillPaint)
        fillPaint.shader = null
        markDirty(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
    }

    override fun fillRingSweepGradient(
        cx: Double, cy: Double, innerR: Double, outerR: Double,
        startAngleRad: Double, stops: List<GradientStop>,
    ) {
        // SweepGradient starts at +X sweeping clockwise on screen = increasing Y-down radians,
        // matching the contract directly; rotate the shader to startAngleRad.
        val shader = SweepGradient(
            cx.toFloat(), cy.toFloat(),
            IntArray(stops.size) { stops[it].argb },
            FloatArray(stops.size) { stops[it].pos.toFloat() },
        )
        val m = Matrix()
        m.setRotate(Math.toDegrees(startAngleRad).toFloat(), cx.toFloat(), cy.toFloat())
        shader.setLocalMatrix(m)
        fillPaint.shader = shader
        canvas.drawPath(ringPath(cx, cy, innerR, outerR), fillPaint)
        fillPaint.shader = null
        markDirty(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
    }

    // --- helpers ---

    private fun ringPath(cx: Double, cy: Double, innerR: Double, outerR: Double): Path {
        val p = Path()
        p.fillType = Path.FillType.EVEN_ODD
        p.addCircle(cx.toFloat(), cy.toFloat(), outerR.toFloat(), Path.Direction.CW)
        p.addCircle(cx.toFloat(), cy.toFloat(), innerR.toFloat(), Path.Direction.CW)
        return p
    }

    private fun rectF(x: Double, y: Double, w: Double, h: Double) =
        RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())

    private companion object {
        val IDENTITY = Matrix()
    }
}

/** A [Bitmap]-backed [ImageRef]. */
class AndroidImage(val bitmap: Bitmap) : ImageRef {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
}

/**
 * Reuse pool for full-frame layer bitmaps: every window cutout / hand shadow pushes an
 * offscreen layer, and at 1080×2424 each is ~10 MB — allocating them fresh per frame churns
 * the GC hard. Released bitmaps remember their dirty region so re-acquiring erases only that
 * (a full-frame eraseColor is a 10 MB memset — 31 of them per Terra frame adds up).
 * Not thread-safe; use from the single render thread only.
 */
class LayerBitmapPool(val width: Int, val height: Int) {
    private class Entry(val bmp: Bitmap, var dirty: Rect?)

    private val free = ArrayDeque<Entry>()
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    fun acquire(): Bitmap {
        val e = free.removeLastOrNull()
            ?: return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                .also { it.density = Bitmap.DENSITY_NONE }
        val d = e.dirty
        if (d == null) {
            e.bmp.eraseColor(0)
        } else if (!d.isEmpty) {
            val c = Canvas(e.bmp)
            c.drawRect(
                (d.left - 2).toFloat(), (d.top - 2).toFloat(),
                (d.right + 2).toFloat(), (d.bottom + 2).toFloat(), clearPaint,
            )
        }
        return e.bmp
    }

    /** Return [bmp] with the region that was drawn into ([dirty]; null = unknown → full erase). */
    fun release(bmp: Bitmap, dirty: Rect?) {
        if (bmp.width == width && bmp.height == height) free.addLast(Entry(bmp, dirty))
    }
}
