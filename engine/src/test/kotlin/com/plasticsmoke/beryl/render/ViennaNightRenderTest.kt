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
 * Renders Vienna's NIGHT mode (canonical iOS, modes='night'): a black face with teal (nfgColor
 * 0xff00c0ac) ticks and sparse demi 24-hour numbers (12,,,15,,,18,…), noon-on-top like the front
 * (masterOffset=pi, hands +pi), and the day/night rings in the dim night palette (nleafFill /
 * nMoonLeafFill teal). No guilloché face image or logo in night. The web stripped Vienna's back/night,
 * so the iOS XML is the only reference.
 */
class ViennaNightRenderTest {

    private fun resourceText(name: String) =
        ViennaNightRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        ViennaNightRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun rendersViennaNightToPng() {
        val xml = resourceText("/Vienna.xml")
        val watch = parseWatchXml(xml, ModeFilter.NIGHT)

        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:09:36Z").toEpochMilli()  // 10:09 PDT — daytime, long summer day
        }
        applyInits(watch, env)

        // Night references no guilloché/logo/blanker; the map is harmless if unused.
        val images = mapOf(
            "face.png" to LoadedImage(AwtImage(resourceImage("/vienna-face-4x.png")), 0.25),
            "../partsBin/logos/black.png" to LoadedImage(AwtImage(resourceImage("/vienna-logo-4x.png")), 0.25),
            "blanker.png" to LoadedImage(AwtImage(resourceImage("/vienna-blanker.png")), 1.0),
        )

        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "vienna-night")

        assertTrue(watch.parts.isNotEmpty(), "Vienna night parts should be present")
    }
}
