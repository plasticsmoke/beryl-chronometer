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
 * Renders Vienna's BACK — the canonical iOS reverse face (Watches/Builtin/Vienna, modes='back').
 * It mirrors the front to a midnight-on-top orientation: the demi 24-hour dial reads 24,1,…,23 with
 * 24 at the top, the day/night rings have no masterOffset (=0), and the hands drop the +π the front
 * adds (hr = hour24ValueAngle(), UT = hour24ValueAngle()-tzOffsetAngle()). The chronometer-web port
 * stripped Vienna's back entirely (64×256 stub), so the iOS XML is the only reference.
 */
class ViennaBackRenderTest {

    private fun resourceText(name: String) =
        ViennaBackRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        ViennaBackRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun rendersViennaBackToPng() {
        val xml = resourceText("/Vienna.xml")
        val watch = parseWatchXml(xml, ModeFilter.BACK)

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

        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "vienna-back")

        assertTrue(watch.parts.isNotEmpty(), "Vienna back parts should be present")
    }
}
