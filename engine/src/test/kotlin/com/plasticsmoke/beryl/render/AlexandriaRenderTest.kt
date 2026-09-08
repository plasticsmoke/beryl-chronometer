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
 * Renders Alexandria (canonical iOS) — the world-clock/Earth-globe watch. A rotating Earth disk
 * (longitude()) sits under a day/night terminator whose phase tilts with the season
 * (vernalEquinoxAngle) and whose rotation tracks solar time. A star field rotates by sidereal time,
 * the Moon orbits at radius 75 (moonAgeAngle), and a sun Qhand points at the sub-solar hour.
 * The back is the southern-hemisphere mirror (earthS, negated angles).
 */
class AlexandriaRenderTest {

    private fun resourceText(name: String) =
        AlexandriaRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        AlexandriaRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    private fun img(name: String, scale: Double) = LoadedImage(AwtImage(resourceImage(name)), scale)

    private fun render(mode: ModeFilter, bg: java.awt.Color, suffix: String, images: Map<String, LoadedImage>) {
        val xml = resourceText("/Alexandria.xml")
        val watch = parseWatchXml(xml, mode)
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()  // 10:08:36 PDT, Fri Jun 12 2026
        }
        applyInits(watch, env)
        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "alexandria$suffix")
        assertTrue(watch.parts.isNotEmpty(), "Alexandria$suffix parts should be present")
    }

    @Test fun rendersAlexandriaFront() = render(
        ModeFilter.FRONT, java.awt.Color.WHITE, "",
        mapOf(
            "earthN.png" to img("/alex-earthN-4x.png", 0.25),
            "stars.png" to img("/alex-stars-4x.png", 0.25),
            "../Mauna Kea/moon25.png" to img("/moon25-4x.png", 0.25),
        ),
    )

    @Test fun rendersAlexandriaNight() = render(
        ModeFilter.NIGHT, java.awt.Color.BLACK, "-night",
        mapOf(
            "earthN.png" to img("/alex-earthN-4x.png", 0.25),
            "starsn.png" to img("/alex-starsn-4x.png", 0.25),
            "blueSun.png" to img("/alex-blueSun.png", 1.0),
            "halfblueMoon.png" to img("/alex-halfblueMoon.png", 1.0),
        ),
    )

    @Test fun rendersAlexandriaBack() = render(
        ModeFilter.BACK, java.awt.Color.WHITE, "-back",
        mapOf(
            "earthS.png" to img("/alex-earthS-4x.png", 0.25),
            "starss.png" to img("/alex-starss-4x.png", 0.25),
            "../Mauna Kea/moon25.png" to img("/moon25-4x.png", 0.25),
        ),
    )
}
