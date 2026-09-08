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
 * Renders the FRONT of Chandra (the moon watch) from the canonical iOS XML — a big realistic moon
 * (moonES.png rotated to its sky orientation via moonRelativeAngle) under the terminator phase
 * shadow, with quad hour/minute hands, alt/az star markers, and month/day/am-pm digit wheels.
 * Second face ported; exercises the new quad hand + moonRelativeAngle. (Back side deferred.)
 */
class ChandraRenderTest {

    private fun resourceText(name: String) =
        ChandraRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        ChandraRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun rendersChandraFrontToPng() {
        val xml = resourceText("/Chandra-ios.xml")
        val watch = parseWatchXml(xml, ModeFilter.FRONT)

        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T10:09:36Z").toEpochMilli()
        }
        applyInits(watch, env)

        val images = mapOf(
            "face.png" to LoadedImage(AwtImage(resourceImage("/chandra-face-4x.png")), 0.25),
            "../partsBin/moonES.png" to LoadedImage(AwtImage(resourceImage("/moonES-4x.png")), 0.25),
            "redStar.png" to LoadedImage(AwtImage(resourceImage("/redStar-4x.png")), 0.25),
            "blueStar.png" to LoadedImage(AwtImage(resourceImage("/blueStar-4x.png")), 0.25),
            "../partsBin/logos/white.png" to LoadedImage(AwtImage(resourceImage("/logo-white-4x.png")), 0.25),
        )

        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "chandra")

        assertTrue(watch.parts.isNotEmpty(), "Chandra front parts should be present")
    }
}
