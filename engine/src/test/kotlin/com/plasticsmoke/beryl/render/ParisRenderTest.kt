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
 * Renders Paris (canonical iOS, no web equivalent) — a Bauhaus-minimalist two-hand watch: a plain
 * light-gray dial (no numbers/ticks, just a center dot), the berry logo, and triangular hour/minute
 * hands. Night is the same on black with teal hands + lume berry; back is the engraved case image.
 */
class ParisRenderTest {

    private fun resourceText(name: String) =
        ParisRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        ParisRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    private fun img(name: String, scale: Double) = LoadedImage(AwtImage(resourceImage(name)), scale)

    private fun render(mode: ModeFilter, bg: java.awt.Color, suffix: String, images: Map<String, LoadedImage>) {
        val xml = resourceText("/Paris.xml")
        val watch = parseWatchXml(xml, mode)
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()  // 10:08:36 PDT
        }
        applyInits(watch, env)
        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "paris$suffix")
        assertTrue(watch.parts.isNotEmpty(), "Paris$suffix parts should be present")
    }

    @Test fun rendersParisFront() = render(
        ModeFilter.FRONT, java.awt.Color.WHITE, "",
        mapOf(
            "../partsBin/berry-shadow.png" to img("/paris-berry-shadow.png", 1.0),
            "../partsBin/berry.png" to img("/paris-berry-4x.png", 0.25),
        ),
    )

    @Test fun rendersParisNight() = render(
        ModeFilter.NIGHT, java.awt.Color.BLACK, "-night",
        mapOf("../partsBin/berry-lum.png" to img("/paris-berry-lum-4x.png", 0.25)),
    )

    @Test fun rendersParisBack() = render(
        ModeFilter.BACK, java.awt.Color.WHITE, "-back",
        mapOf(
            "back.png" to img("/paris-back-4x.png", 0.25),
            "../partsBin/berry.png" to img("/paris-berry-4x.png", 0.25),
        ),
    )
}
