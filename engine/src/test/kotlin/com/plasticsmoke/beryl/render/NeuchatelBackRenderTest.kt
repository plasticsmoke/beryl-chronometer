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
 * Renders Neuchatel's BACK — the case back plate (back.png) with a procedural power-reserve sub-dial
 * (arc + red low zone + ticks + 0/½/1 numerals) and its pointer. batteryLevel() is unavailable in
 * tests (-1), so the pointer rests at the "full" end (angle 17π/12).
 */
class NeuchatelBackRenderTest {

    private fun resourceImage(name: String) =
        NeuchatelBackRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun rendersNeuchatelBackToPng() {
        val xml = NeuchatelBackRenderTest::class.java.getResourceAsStream("/Neuchatel.xml")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val watch = parseWatchXml(xml, ModeFilter.BACK)

        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()
        }
        applyInits(watch, env)

        val images = mapOf(
            "back.png" to LoadedImage(AwtImage(resourceImage("/neuchatel-back-4x.png")), 0.25),
        )

        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "neuchatel-back")

        assertTrue(watch.parts.isNotEmpty(), "Neuchatel back parts should be present")
    }
}
