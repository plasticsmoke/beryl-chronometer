package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.astro.registerWatchFunctions
import com.plasticsmoke.beryl.expr.createDefaultEnvironment
import com.plasticsmoke.beryl.watch.ModeFilter
import com.plasticsmoke.beryl.watch.parseWatchXml
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders Milano's static layer end-to-end through the engine renderer onto an AWT-backed
 * [DrawingContext], writes a viewable PNG, and asserts a few key pixels. This exercises the
 * full pipeline: XML → model → init/env → static render. (Fonts/metrics differ from Android,
 * so this is a smoke + visual check, not a pixel-exact match to the device.)
 */
class StaticRenderTest {

    private fun resourceText(name: String): String =
        StaticRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String): BufferedImage =
        StaticRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun rendersMilanoStaticLayerToPng() {
        val watch = parseWatchXml(resourceText("/Milano-I.xml"), ModeFilter.FRONT)

        val env = createDefaultEnvironment()
        // Static dials don't depend on time, but the env needs the watch functions registered.
        registerWatchFunctions(env, 0.0) { Instant.parse("2026-06-12T10:09:00Z").toEpochMilli() }
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
        renderStaticLayer(AwtDrawingContext(g, px, px), watch, env, scale, px, px, images)
        g.dispose()

        val outFile = File("build/milano-static.png")
        outFile.parentFile.mkdirs()
        ImageIO.write(out, "png", outFile)
        println("Wrote ${outFile.absolutePath} (${px}x$px)")

        // Center is covered by the black mask QRect.
        val center = out.getRGB(px / 2, px / 2)
        assertTrue(channelsBelow(center, 40), "center should be black mask, got ${center.toUInt().toString(16)}")

        // A point in the bezel band (~132 XML units out, band is 127.5..137.5): metallic grey
        // (gradient-shaded, so check it's a bright neutral grey rather than an exact value).
        val bezelX = px / 2 + (132 * scale).toInt()
        val bezel = out.getRGB(bezelX, px / 2)
        assertTrue(isBrightNeutralGrey(bezel), "bezel should be bright neutral grey, got ${bezel.toUInt().toString(16)}")

        // The face shows light dial text on black, so the image isn't a single flat color.
        assertTrue(distinctColorCount(out) > 50, "expected a non-trivial rendering")
    }

    private fun channelsBelow(argb: Int, max: Int): Boolean {
        val r = (argb ushr 16) and 0xFF; val g = (argb ushr 8) and 0xFF; val b = argb and 0xFF
        return r < max && g < max && b < max
    }

    private fun isBrightNeutralGrey(argb: Int): Boolean {
        val r = (argb ushr 16) and 0xFF; val g = (argb ushr 8) and 0xFF; val b = argb and 0xFF
        return r > 120 && kotlin.math.abs(r - g) <= 10 && kotlin.math.abs(g - b) <= 10
    }

    private fun distinctColorCount(img: BufferedImage): Int {
        val seen = HashSet<Int>()
        var y = 0
        while (y < img.height && seen.size <= 60) {
            var x = 0
            while (x < img.width) { seen.add(img.getRGB(x, y)); x += 4 }
            y += 4
        }
        return seen.size
    }
}
