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
 * Renders Neuchatel's NIGHT mode — a black dial with the luminous "berry" accent (berry-lum.png) and
 * the blued breguet hour/minute hands plus the seconds hand.
 */
class NeuchatelNightRenderTest {

    private fun resourceImage(name: String) =
        NeuchatelNightRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun rendersNeuchatelNightToPng() {
        val xml = NeuchatelNightRenderTest::class.java.getResourceAsStream("/Neuchatel.xml")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val watch = parseWatchXml(xml, ModeFilter.NIGHT)

        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()
        }
        applyInits(watch, env)

        val images = mapOf(
            "../partsBin/berry-lum.png" to LoadedImage(AwtImage(resourceImage("/neuchatel-berry-lum-4x.png")), 0.25),
        )

        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "neuchatel-night")

        assertTrue(watch.parts.isNotEmpty(), "Neuchatel night parts should be present")
    }
}
