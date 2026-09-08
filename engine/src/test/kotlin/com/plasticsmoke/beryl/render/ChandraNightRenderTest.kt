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
 * Renders Chandra's NIGHT face — the night-themed moon (moonESnight) under the terminator phase
 * shadow on a starfield, with cyan quad hands and the moon alt/az star markers.
 */
class ChandraNightRenderTest {

    private fun resourceText(name: String) =
        ChandraNightRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        ChandraNightRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun rendersChandraNightToPng() {
        val xml = resourceText("/Chandra-ios.xml")
        val watch = parseWatchXml(xml, ModeFilter.NIGHT)

        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T10:09:36Z").toEpochMilli()
        }
        applyInits(watch, env)

        val images = mapOf(
            "stars.png" to LoadedImage(AwtImage(resourceImage("/chandra-stars-4x.png")), 0.25),
            "../partsBin/moonESnight.png" to LoadedImage(AwtImage(resourceImage("/moonESnight-4x.png")), 0.25),
            "redStar.png" to LoadedImage(AwtImage(resourceImage("/redStar-4x.png")), 0.25),
            "blueStar.png" to LoadedImage(AwtImage(resourceImage("/blueStar-4x.png")), 0.25),
        )

        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "chandra-night")

        assertTrue(watch.parts.isNotEmpty(), "Chandra night parts should be present")
    }
}
