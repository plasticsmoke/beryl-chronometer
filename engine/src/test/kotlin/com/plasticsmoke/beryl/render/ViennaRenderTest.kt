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
 * Renders Vienna's front — the canonical iOS 24-hour watch (Watches/Builtin/Vienna): a demi-oriented
 * 24-hour dial (12 at top, 24 at bottom), sun/moon day-night rings (noon-on-top via masterOffset=pi),
 * the EMERALD ◆ SEQUOIA logo, and the UT/hour/minute/second hands. The analemma + equation-of-time
 * variant lives only in the abandoned Android "Vienna I" build, so it is NOT what the iPhone shows.
 */
class ViennaRenderTest {

    private fun resourceText(name: String) =
        ViennaRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        ViennaRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun rendersViennaToPng() {
        val xml = resourceText("/Vienna.xml")
        val watch = parseWatchXml(xml, ModeFilter.FRONT)

        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:09:36Z").toEpochMilli()  // 10:09 PDT — daytime, long summer day
        }
        applyInits(watch, env)

        val images = mapOf(
            "face.png" to LoadedImage(AwtImage(resourceImage("/vienna-face-4x.png")), 0.25),
            "../partsBin/logos/black.png" to LoadedImage(AwtImage(resourceImage("/vienna-logo-4x.png")), 0.25),
            "blanker.png" to LoadedImage(AwtImage(resourceImage("/vienna-blanker.png")), 1.0),
        )

        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "vienna")

        assertTrue(watch.parts.isNotEmpty(), "Vienna parts should be present")
    }
}
