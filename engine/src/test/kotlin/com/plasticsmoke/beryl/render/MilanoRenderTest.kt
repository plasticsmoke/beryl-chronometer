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
 * Renders Milano (canonical iOS Watches/Builtin/Milano) — a retrograde watch: the front has
 * retrograde month/date/weekday arcs + a power-reserve arc + Times-Roman time, night is the
 * teal-on-black variant, and the back is the white-dial year display. Replaces the earlier
 * web "Milano-I" front-only variant.
 */
class MilanoRenderTest {

    private fun resourceText(name: String) =
        MilanoRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        MilanoRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    private fun render(mode: ModeFilter, images: Map<String, LoadedImage>, bg: java.awt.Color, outName: String) {
        val xml = resourceText("/Milano.xml")
        val watch = parseWatchXml(xml, mode)
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()  // 10:08:36 PDT
        }
        applyInits(watch, env)
        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "${outName.removeSuffix(".png")}")
        assertTrue(watch.parts.isNotEmpty(), "$outName parts should be present")
    }

    private fun img(name: String, scale: Double) = LoadedImage(AwtImage(resourceImage(name)), scale)

    @Test fun rendersMilanoFront() = render(
        ModeFilter.FRONT,
        // Sharp white berry generated from berry-4x (berryWhite ships only at 1x = blurry).
        mapOf("../partsBin/berryWhite.png" to img("/milano-berrywhite-4x.png", 0.25)),
        java.awt.Color.WHITE, "milano.png",
    )

    @Test fun rendersMilanoNight() = render(
        ModeFilter.NIGHT,
        mapOf(
            "../partsBin/berry-lum.png" to img("/milano-berry-lum-4x.png", 0.25),
            // The hr-mask is a black ring with a wedge; at native scale (1.0) it covers the numeral
            // ring so only the 1-2 numerals in the wedge (rotated to the hour hand) stay lit.
            "mask.png" to img("/milano-mask.png", 1.0),
        ),
        java.awt.Color.BLACK, "milano-night.png",
    )

    @Test fun rendersMilanoBack() = render(
        ModeFilter.BACK,
        mapOf(
            "../partsBin/berry.png" to img("/milano-berry-4x.png", 0.25),
            "../partsBin/case12back.png" to img("/milano-case12back-2x.png", 0.5),
        ),
        java.awt.Color.WHITE, "milano-back.png",
    )
}
