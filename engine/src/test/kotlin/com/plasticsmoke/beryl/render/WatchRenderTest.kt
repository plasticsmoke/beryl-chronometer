package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.astro.registerWatchFunctions
import com.plasticsmoke.beryl.expr.createDefaultEnvironment
import com.plasticsmoke.beryl.watch.ModeFilter
import com.plasticsmoke.beryl.watch.parseWatchXml
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the full Milano face (static layer + hands) at a fixed time through the engine
 * renderer onto an AWT-backed [DrawingContext], writes a viewable PNG, and sanity-checks
 * that the hands actually drew (colored ink appears off the dials, where only hands reach).
 */
class WatchRenderTest {

    private fun resourceText(name: String): String =
        WatchRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String): BufferedImage =
        WatchRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun rendersFullMilanoWithHandsToPng() {
        val watch = parseWatchXml(resourceText("/Milano-I.xml"), ModeFilter.FRONT)

        val env = createDefaultEnvironment()
        // 10:09:36 — distinct hour/minute/second so the three time hands point different ways.
        registerWatchFunctions(env, 0.0) { Instant.parse("2026-06-12T10:09:36Z").toEpochMilli() }
        applyInits(watch, env)

        val images = mapOf(
            "../partsBin/berryWhite.png" to LoadedImage(AwtImage(resourceImage("/berryWhite.png")), 1.0),
        )

        val px = 1024
        val scale = px / (watch.faceWidth + 2 * BEZEL_THICKNESS_XML)

        val out = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, px, px)
        renderFrame(AwtDrawingContext(g, px, px), watch, env, scale, px, px, images)
        g.dispose()

        val outFile = File("build/milano-full.png")
        outFile.parentFile.mkdirs()
        ImageIO.write(out, "png", outFile)
        println("Wrote ${outFile.absolutePath} (${px}x$px)")

        // The red second hand (secClr=red) should leave red ink somewhere on the black face.
        assertTrue(hasReddishPixel(out), "expected the red second hand to be visible")

        // Center hub: the second hand's oCenter dot is red over the black mask.
        // (Just assert the image is a non-trivial rendering overall.)
        assertTrue(distinctColors(out) > 60, "expected a non-trivial rendering")
    }

    private fun hasReddishPixel(img: BufferedImage): Boolean {
        var y = 0
        while (y < img.height) {
            var x = 0
            while (x < img.width) {
                val argb = img.getRGB(x, y)
                val r = (argb ushr 16) and 0xFF; val gg = (argb ushr 8) and 0xFF; val b = argb and 0xFF
                if (r > 150 && gg < 90 && b < 90) return true
                x += 2
            }
            y += 2
        }
        return false
    }

    private fun distinctColors(img: BufferedImage): Int {
        val seen = HashSet<Int>()
        var y = 0
        while (y < img.height && seen.size <= 70) {
            var x = 0
            while (x < img.width) { seen.add(img.getRGB(x, y)); x += 4 }
            y += 4
        }
        return seen.size
    }
}
