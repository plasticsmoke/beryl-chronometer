package com.plasticsmoke.beryl.app

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.plasticsmoke.beryl.render.LoadedImage
import com.plasticsmoke.beryl.render.renderFrame
import kotlin.math.min

/**
 * The live face grid: ALL faces loaded at mini scale and re-rendered once a second while the
 * picker is open — the iPhone's animated grid, and a deliberate stress test.
 *
 * Affordable without the static-cache split because everything shrinks with the cell:
 * rendering at ~1/3 the linear scale is ~1/9 the pixels, and the 4x art decodes subsampled
 * (1/16 the memory), so all 25 faces stay resident (~tens of MB) and a full sweep is fast.
 *
 * Cell bitmaps are double-buffered by sweep parity: each sweep renders into one parity's
 * bitmaps while the UI displays the other, and [display] is republished after every face so
 * the first sweep fills in progressively. Everything runs on one dedicated thread; [display]
 * is safe to read from the UI thread.
 */
class MiniFaces(
    private val assets: AssetManager,
    private val location: LocationStore,
    private val faces: List<FaceInfo>,
    private val cellW: Int,
    private val cellH: Int,
    private val varStore: FaceVarStore? = null,
    /** Persisted per-watch mode index (0=front, 1=night, 2=back): iOS's grid repositions the
     *  LIVE watches, so a face left on its back or in night shows that side in the grid. */
    private val modeFor: (String) -> Int = { 0 },
    private val onFrame: () -> Unit,
) {
    private val thread = HandlerThread("mini-faces").apply { start() }
    private val handler = Handler(thread.looper)

    private class Mini(
        val state: LoadedFace.ModeState,
        val images: Map<String, LoadedImage>,
        val time: com.plasticsmoke.beryl.astro.WatchTimeState,
        /** The FaceRepository.MODES index this mini was built for. */
        val modeIdx: Int,
    )

    // All state below is touched only on the mini thread, except [display] (volatile publish).
    private val minis = arrayOfNulls<Mini>(faces.size)
    private val buffers = Array(2) { arrayOfNulls<Bitmap>(faces.size) }
    private var parity = 0
    private var pool: LayerBitmapPool? = null
    private var wallpaper: Bitmap? = null
    private var failed = BooleanArray(faces.size)

    @Volatile var display: Array<Bitmap?> = arrayOfNulls(faces.size)
        private set

    @Volatile private var running = false
    private var lastSweepSecond = -1L

    fun start() {
        if (running) return
        running = true
        // Re-sync each mini with the persisted per-face state (time warp, button-set vars,
        // MODE): iOS's grid repositions the LIVE resident watches, so a stopped/warped watch
        // looks stopped in the grid and a flipped/night watch shows that side; our minis are
        // separate envs and would otherwise show the state from when they were first built.
        // A mode change since the build needs a re-parse — drop the mini and let the next
        // sweep rebuild it in the new mode.
        handler.post {
            for (i in faces.indices) {
                val mini = minis[i] ?: continue
                if (mini.modeIdx != clampedMode(faces[i].name)) minis[i] = null
                else resync(faces[i].name, mini)
            }
        }
        handler.post(loop)
    }

    private fun clampedMode(faceName: String): Int =
        modeFor(faceName).coerceIn(0, FaceRepository.MODES.size - 1)

    private fun resync(faceName: String, mini: Mini) {
        val saved = varStore?.load(faceName) ?: return
        saved["__timeWarp"]?.let {
            mini.time.restore(it, saved["__timeWarpBefore"] ?: 1.0, saved["__timeZeroMs"] ?: 0.0)
        }
        var varsChanged = false
        for ((k, v) in saved) {
            if (k.startsWith("__")) continue
            if (mini.state.env.variables[k] != v) { mini.state.env.variables[k] = v; varsChanged = true }
        }
        if (varsChanged) mini.state.cache.invalidate()
    }

    fun stop() {
        running = false
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            val second = System.currentTimeMillis() / 1000
            if (second != lastSweepSecond) {
                lastSweepSecond = second
                sweep()
            }
            handler.postDelayed(this, 100)
        }
    }

    private fun sweep() {
        val t0 = SystemClock.uptimeMillis()
        val firstSweep = minis.any { it == null }
        val target = buffers[parity]
        for (i in faces.indices) {
            if (!running) return
            val mini = minis[i] ?: load(i) ?: continue
            renderMini(i, mini, target)
            // During the initial load-as-you-go sweep, publish after every face so the grid
            // fills in progressively instead of appearing all at once seconds later.
            if (firstSweep) publish(target)
        }
        publish(target)
        parity = 1 - parity
        Log.d(TAG, "mini sweep: ${faces.size} faces in ${SystemClock.uptimeMillis() - t0}ms")
    }

    private fun publish(target: Array<Bitmap?>) {
        display = target.copyOf()
        onFrame()
    }

    private fun load(i: Int): Mini? {
        if (failed[i]) return null
        val face = faces[i]
        return try {
            val t0 = SystemClock.uptimeMillis()
            val xml = FaceLoader.text(assets, "facedata/${face.xml}")
            val images = HashMap<String, LoadedImage>()
            for (spec in face.images) {
                images[spec.key] = FaceLoader.imageSampled(assets, "facedata/${spec.file}", spec.scale)
            }
            images.putAll(FaceLoader.loadChrome(assets, xml, images.keys, sampled = true))
            if (wallpaper == null) {
                // Pre-scaled to the cell once — a filtered rescale per face per sweep adds up.
                val raw = FaceLoader.bitmap(assets, "${FaceLoader.DATA_DIR}/wallpaper-2x.png", 4)
                wallpaper = Bitmap.createScaledBitmap(raw, cellW, cellH, true)
                    .also { it.density = Bitmap.DENSITY_NONE }
            }
            // Per-face state: persisted vars (chosen planet, stem stat) + the face's own time
            // base, restored read-only (no onChange hook — the live face owns persistence).
            val time = com.plasticsmoke.beryl.astro.WatchTimeState()
            val modeIdx = clampedMode(face.name)
            val mini = Mini(
                buildModeState(face, xml, FaceRepository.MODES[modeIdx], location, varStore, time),
                images, time, modeIdx,
            )
            resync(face.name, mini)
            minis[i] = mini
            Log.d(TAG, "mini loaded ${face.name} in ${SystemClock.uptimeMillis() - t0}ms")
            mini
        } catch (e: Exception) {
            Log.e(TAG, "mini load failed for ${face.name}", e)
            failed[i] = true
            null
        }
    }

    private fun renderMini(i: Int, mini: Mini, target: Array<Bitmap?>) {
        val bmp = target[i] ?: Bitmap.createBitmap(cellW, cellH, Bitmap.Config.ARGB_8888)
            .also { it.density = Bitmap.DENSITY_NONE; target[i] = it }
        bmp.eraseColor(Color.BLACK)
        val canvas = Canvas(bmp)
        wallpaper?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val p = pool?.takeIf { it.width == cellW && it.height == cellH }
            ?: LayerBitmapPool(cellW, cellH).also { pool = it }
        val ctx = AndroidCanvasDrawingContext(canvas, cellW, cellH, assets, p)
        val scale = min(cellW / 320.0, cellH / 480.0)
        mini.state.clock.startFrame(mini.time.displayedNow())
        try {
            renderFrame(ctx, mini.state.watch, mini.state.env, scale, cellW, cellH, mini.images, mini.state.cache, mini.state.clock.beat)
        } catch (e: Exception) {
            Log.e(TAG, "mini render failed for ${faces[i].name}", e)
            failed[i] = true
        }
    }

    private companion object {
        const val TAG = "Beryl"
    }
}
