package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.expr.Environment
import com.plasticsmoke.beryl.watch.Watch
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Full-phone-frame test harness: renders a face onto the iOS logical 320×480 screen over the real
 * granite wallpaper (Watches/Builtin/Background/background-2x.png) — exactly the composition the
 * iPhone shows. Faces converted to this harness keep their CANONICAL watch tag (no faceWidth /
 * bezelColor patches): no circular clip, no procedural bezel — the staged band/case/button chrome
 * is the silhouette, and the wallpaper shows around it.
 */
object PhoneFrame {
    const val SCREEN_W = 320.0
    const val SCREEN_H = 480.0
    const val SCALE = 3.2   // → 1024×1536 canvas, face diameter comparable to the old previews

    /**
     * Auto-load every `../partsBin/...` chrome asset the face XML references from the shared
     * staged resources (flattened `partsbin-…` names, best-resolution variant). Assets with no
     * staged flat resource (berry/logos/moons — staged per-face under custom names) are skipped,
     * so face-specific image maps are never overridden.
     */
    fun loadChrome(xmlText: String): Map<String, LoadedImage> {
        val out = HashMap<String, LoadedImage>()
        Regex("src='(\\.\\./partsBin/[^']*)'").findAll(xmlText).forEach { m ->
            val key = m.groupValues[1]
            if (out.containsKey(key)) return@forEach
            val flat = "partsbin-" + key.removePrefix("../partsBin/").removeSuffix(".png")
                .lowercase().replace('/', '-')
            for ((suffix, sc) in listOf("-4x" to 0.25, "-2x" to 0.5, "" to 1.0)) {
                val st = PhoneFrame::class.java.getResourceAsStream("/$flat$suffix.png") ?: continue
                st.use { out[key] = LoadedImage(AwtImage(ImageIO.read(it)), sc) }
                break
            }
        }
        return out
    }

    /** Render [watch] over the wallpaper at full phone frame and write `build/<outName>.png`. */
    fun render(watch: Watch, env: Environment, images: Map<String, LoadedImage>, outName: String): BufferedImage {
        val w = (SCREEN_W * SCALE).roundToInt()
        val h = (SCREEN_H * SCALE).roundToInt()
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        PhoneFrame::class.java.getResourceAsStream("/wallpaper-2x.png")!!.use { st ->
            val wp = ImageIO.read(st)
            val at = AffineTransform()
            at.scale(w.toDouble() / wp.width, h.toDouble() / wp.height)
            g.drawImage(wp, at, null)
        }
        renderFrame(AwtDrawingContext(g, w, h), watch, env, SCALE, w, h, images)
        g.dispose()
        val f = File("build/$outName.png")
        f.parentFile.mkdirs()
        ImageIO.write(out, "png", f)
        return out
    }
}
