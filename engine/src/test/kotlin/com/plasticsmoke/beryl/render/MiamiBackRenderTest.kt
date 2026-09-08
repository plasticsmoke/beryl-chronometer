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
 * Renders Miami's BACK — the planetary instrument panel for the selected body (here Saturn): an
 * alt-az compass with the Sun + selected-planet az/alt/RA hands, rise/transit/set sub-dials with
 * hands at the body's rise/transit/set for the day, a colour-coded compass rose, the planet
 * selector, and the date. Exercises the rise/set/transit-of-planet-for-day leaves.
 */
class MiamiBackRenderTest {

    private fun resourceText(name: String) =
        MiamiBackRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        MiamiBackRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun rendersMiamiBackToPng() {
        val xml = resourceText("/Miami.xml")
        val watch = parseWatchXml(xml, ModeFilter.BACK)

        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        env.variables["body"] = 7.0  // Saturn selected
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:09:36Z").toEpochMilli()  // 10:09 PDT
        }
        applyInits(watch, env)

        val images = mapOf(
            "../partsBin/logos/black.png" to LoadedImage(AwtImage(resourceImage("/miami-logo-4x.png")), 0.25),
            // saturnb is the porthole-framed Saturn (the sharp saturn-4x has a larger glyph that
            // overflows the porthole); saturnb only ships at 2x, so it's slightly soft in the 4x preview.
            "../partsBin/planets/saturnb.png" to LoadedImage(AwtImage(resourceImage("/miami-icon-saturnb-2x.png")), 0.5),
            "../partsBin/planets/jupiter.png" to LoadedImage(AwtImage(resourceImage("/miami-icon-jupiter-4x.png")), 0.25),
            "../partsBin/planets/mars.png" to LoadedImage(AwtImage(resourceImage("/miami-icon-mars-4x.png")), 0.25),
            "../partsBin/planets/venus.png" to LoadedImage(AwtImage(resourceImage("/miami-icon-venus-4x.png")), 0.25),
            "../partsBin/planets/mercury.png" to LoadedImage(AwtImage(resourceImage("/miami-icon-mercury-4x.png")), 0.25),
            "../partsBin/planets/bighalfMoon.png" to LoadedImage(AwtImage(resourceImage("/miami-icon-bighalfMoon-4x.png")), 0.25),
        )

        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "miami-back")

        assertTrue(watch.parts.isNotEmpty(), "Miami back parts should be present")
    }
}
