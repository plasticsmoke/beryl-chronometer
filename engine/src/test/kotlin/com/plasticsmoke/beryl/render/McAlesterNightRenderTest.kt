package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.astro.registerWatchFunctions
import com.plasticsmoke.beryl.expr.createDefaultEnvironment
import com.plasticsmoke.beryl.watch.ModeFilter
import com.plasticsmoke.beryl.watch.parseWatchXml
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders McAlester's NIGHT mode — the luminous dial (face-night.png) with glowing hour/minute hands
 * (hour-lum.png / minute-lum.png); no seconds hand and no gold case in night. Same portrait geometry
 * as the front.
 */
class McAlesterNightRenderTest {

    private fun resourceImage(name: String) =
        McAlesterNightRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun rendersMcAlesterNightToPng() {
        val xml = McAlesterNightRenderTest::class.java.getResourceAsStream("/McAlester.xml")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val watch = parseWatchXml(xml, ModeFilter.NIGHT)

        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()
        }
        applyInits(watch, env)

        val images = mapOf(
            "face-night.png" to LoadedImage(AwtImage(resourceImage("/mcalester-face-night-2x.png")), 0.5),
            "stem-n.png" to LoadedImage(AwtImage(resourceImage("/mcalester-stem-n-2x.png")), 0.5),
            "resetH.png" to LoadedImage(AwtImage(resourceImage("/mcalester-reseth-4x.png")), 0.25),
            "hour-lum.png" to LoadedImage(AwtImage(resourceImage("/mcalester-hour-lum-4x.png")), 0.25),
            "minute-lum.png" to LoadedImage(AwtImage(resourceImage("/mcalester-minute-lum-4x.png")), 0.25),
        )

        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "mcalester-night")

        assertTrue(watch.parts.isNotEmpty(), "McAlester night parts should be present")
    }
}
