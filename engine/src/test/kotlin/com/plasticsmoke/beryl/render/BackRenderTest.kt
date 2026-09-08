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
 * Renders Geneva's REVERSE (back) face from the canonical iOS XML — the sidereal-time astronomical
 * back with zodiac/month rings, equinox/solstice markers, sun/moon day-night rings, civil/UTC/solar
 * subdials, year/century digit wheels and the eclipse display. Exercises the new back astronomy and
 * the Qtext / QdayNightRing part types.
 */
class BackRenderTest {

    private fun resourceText(name: String) =
        BackRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        BackRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun rendersGenevaBackToPng() {
        val xml = resourceText("/Geneva-ios.xml")
        val watch = parseWatchXml(xml, ModeFilter.BACK)

        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T10:09:36Z").toEpochMilli()
        }
        applyInits(watch, env)

        val images = mapOf(
            "faceBack.png" to LoadedImage(AwtImage(resourceImage("/faceBack-4x.png")), 0.25),
            "zodiac.png" to LoadedImage(AwtImage(resourceImage("/zodiac-4x.png")), 0.25),
            "dateRing.png" to LoadedImage(AwtImage(resourceImage("/dateRing-4x.png")), 0.25),
            "Node.png" to LoadedImage(AwtImage(resourceImage("/Node-4x.png")), 0.25),
            "../partsBin/berry.png" to LoadedImage(AwtImage(resourceImage("/berry-4x.png")), 0.25),
            "../Mauna Kea/moon25.png" to LoadedImage(AwtImage(resourceImage("/moon25-4x.png")), 0.25),
        )

        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "geneva-back")

        assertTrue(watch.parts.isNotEmpty(), "back parts should be present")
    }
}
