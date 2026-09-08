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

/** Renders Istanbul's BACK — just the engraved case-back image (back.png) over the case. */
class IstanbulBackRenderTest {

    private fun resourceImage(name: String) =
        IstanbulBackRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun rendersIstanbulBackToPng() {
        val xml = IstanbulBackRenderTest::class.java.getResourceAsStream("/Istanbul.xml")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val watch = parseWatchXml(xml, ModeFilter.BACK)

        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()
        }
        env.functions["alarmTypeTarget"] = { 0.0 }
        applyInits(watch, env)

        val images = mapOf(
            "back.png" to LoadedImage(AwtImage(resourceImage("/istanbul-back-4x.png")), 0.25),
        )

        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "istanbul-back")

        assertTrue(watch.parts.isNotEmpty(), "Istanbul back parts should be present")
    }
}
