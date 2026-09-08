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
 * Renders Neuchatel's front — a classic round dial (canonical iOS, no web equivalent): procedural
 * silver chapter rings/dots/guilloché QDials, a Roman-numeral image (numbers4.png), the curved
 * Emerald-Sequoia logo and "Swiss-made" disclaimer image, a date sub-window, and blued-steel
 * BREGUET pomme hour/minute hands (the new breguet hand type) plus a long counter-weighted seconds.
 */
class NeuchatelRenderTest {

    private fun resourceText(name: String) =
        NeuchatelRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        NeuchatelRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun rendersNeuchatelToPng() {
        val xml = resourceText("/Neuchatel.xml")
        val watch = parseWatchXml(xml, ModeFilter.FRONT)

        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()  // 10:08:36 PDT
        }
        applyInits(watch, env)

        val images = mapOf(
            "numbers4.png" to LoadedImage(AwtImage(resourceImage("/neuchatel-numbers4-4x.png")), 0.25),
            "../partsBin/logos/curved.png" to LoadedImage(AwtImage(resourceImage("/neuchatel-curved-4x.png")), 0.25),
            "notSwiss.png" to LoadedImage(AwtImage(resourceImage("/neuchatel-notswiss-4x.png")), 0.25),
        )

        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "neuchatel")

        assertTrue(watch.parts.isNotEmpty(), "Neuchatel parts should be present")
    }
}
