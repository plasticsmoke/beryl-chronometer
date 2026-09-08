package com.plasticsmoke.beryl.app

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.plasticsmoke.beryl.render.LoadedImage

/**
 * Low-level asset access for faces. All face data lives flat under `assets/facedata/` — a
 * build-time copy of the engine's test resources (staged XMLs, face art, flattened
 * `partsbin-…` chrome, wallpaper), so the app and the test previews render from identical bytes.
 */
object FaceLoader {

    const val DATA_DIR = "facedata"

    fun text(assets: AssetManager, path: String): String =
        assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun bitmap(assets: AssetManager, path: String, sampleSize: Int = 1): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inScaled = false
            inSampleSize = sampleSize
        }
        val bmp = assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Failed to decode asset $path")
        bmp.density = Bitmap.DENSITY_NONE
        return bmp
    }

    fun image(assets: AssetManager, path: String, scale: Double): LoadedImage =
        LoadedImage(AndroidImage(bitmap(assets, path)), scale)

    /**
     * Load an image subsampled for small-scale rendering (the live picker minis): a 4x asset
     * (scale 0.25) decodes at inSampleSize 4 → 1x pixels, 1/16 the memory, same unit size.
     */
    fun imageSampled(assets: AssetManager, path: String, scale: Double): LoadedImage {
        val sample = when {
            scale <= 0.25 -> 4
            scale <= 0.5 -> 2
            else -> 1
        }
        return LoadedImage(AndroidImage(bitmap(assets, path, sample)), scale * sample)
    }

    private var dataFiles: Set<String>? = null

    /** Cached listing of the facedata dir (≈400 entries, listed once). */
    private fun dataFiles(assets: AssetManager): Set<String> =
        dataFiles ?: (assets.list(DATA_DIR)?.toSet() ?: emptySet()).also { dataFiles = it }

    /** Mirror of PhoneFrame.loadChrome: resolve `../partsBin/…` srcs to flattened chrome assets. */
    fun loadChrome(
        assets: AssetManager,
        xmlText: String,
        alreadyLoaded: Set<String> = emptySet(),
        sampled: Boolean = false,
    ): Map<String, LoadedImage> {
        val out = HashMap<String, LoadedImage>()
        val available = dataFiles(assets)
        Regex("src='(\\.\\./partsBin/[^']*)'").findAll(xmlText).forEach { m ->
            val key = m.groupValues[1]
            if (key in alreadyLoaded || out.containsKey(key)) return@forEach
            val flat = "partsbin-" + key.removePrefix("../partsBin/").removeSuffix(".png")
                .lowercase().replace('/', '-')
            for ((suffix, sc) in listOf("-4x" to 0.25, "-2x" to 0.5, "" to 1.0)) {
                val name = "$flat$suffix.png"
                if (name in available) {
                    val path = "$DATA_DIR/$name"
                    out[key] = if (sampled) imageSampled(assets, path, sc) else image(assets, path, sc)
                    break
                }
            }
        }
        return out
    }
}
