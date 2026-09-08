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
 * Asset compiler for the PebbleOS port (../pebble): renders each face's STATIC layer —
 * renderStaticLayer draws only the static part types (dials, images, windows, rects, statics),
 * never hands/wheels/terminators — at Pebble resolution onto a transparent canvas, face-only
 * (no band/case chrome, no wallpaper: chrome images simply aren't supplied, so those parts
 * no-op). The Pebble face's C runtime draws the dynamic parts over/under these PNGs.
 *
 * Output: build/pebble/<face>-static.png (260×260, gabbro-sized: display radius 130px = the
 * face's dial radius in XML units). The Pebble resource compiler quantizes to 64 colors.
 */
class PebbleStaticExportTest {

    private fun resourceText(name: String) =
        PebbleStaticExportTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        PebbleStaticExportTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    private fun img(name: String, scale: Double = 0.25) = LoadedImage(AwtImage(resourceImage(name)), scale)

    private fun export(xmlRes: String, outName: String, dialRadiusUnits: Double, images: Map<String, LoadedImage>) {
        val watch = parseWatchXml(resourceText(xmlRes), ModeFilter.FRONT)
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()
        }
        applyInits(watch, env)
        // One static per round-platform display: gabbro 260x260, chalk 180x180.
        // Chalk also has only a 64KB app heap - a 260px bitmap doesn't even fit.
        for (px in listOf(260, 180)) {
            val scale = (px / 2.0) / dialRadiusUnits
            val out = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            renderStaticLayer(AwtDrawingContext(g, px, px), watch, env, scale, px, px, images)
            g.dispose()
            val f = File("build/pebble/$outName-static-$px.png")
            f.parentFile.mkdirs()
            ImageIO.write(out, "png", f)
        }
        assertTrue(watch.parts.isNotEmpty(), "$outName parts should be present")
    }

    @Test fun exportsMilano() = export(
        "/Milano.xml", "milano", 143.0,
        mapOf("../partsBin/berryWhite.png" to img("/milano-berrywhite-4x.png")),
    )

    @Test fun exportsNeuchatel() = export(
        "/Neuchatel.xml", "neuchatel", 140.0,
        mapOf(
            "numbers4.png" to img("/neuchatel-numbers4-4x.png"),
            "../partsBin/logos/curved.png" to img("/neuchatel-curved-4x.png"),
            "notSwiss.png" to img("/neuchatel-notswiss-4x.png"),
        ),
    )

    @Test fun exportsHaleakala() = export(
        "/Haleakala.xml", "haleakala", 143.0,
        mapOf(
            "Haleakala-face.png" to img("/haleakala-face.png", 1.0),
            "../partsBin/logos/black.png" to img("/haleakala-logo-black-4x.png"),
        ),
    )

    @Test fun exportsUraniborg() = export(
        "/Uraniborg.xml", "uraniborg", 143.0,
        mapOf(
            "../partsBin/HD/white/face.png" to img("/uraniborg-face-4x.png"),
            "zodiacNames.png" to img("/uraniborg-zodiac-2x.png", 0.5),
            "../partsBin/berry-shadow.png" to img("/uraniborg-berry-shadow.png", 1.0),
            "../partsBin/berry.png" to img("/uraniborg-berry-4x.png"),
        ),
    )

    @Test fun exportsVienna() = export(
        "/Vienna.xml", "vienna", 139.0,
        mapOf(
            "face.png" to img("/vienna-face-4x.png"),
            "../partsBin/logos/black.png" to img("/vienna-logo-4x.png"),
            "blanker.png" to img("/vienna-blanker.png", 1.0),
        ),
    )
}
