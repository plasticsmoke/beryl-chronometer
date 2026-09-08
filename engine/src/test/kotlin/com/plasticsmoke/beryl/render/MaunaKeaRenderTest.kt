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
 * Renders Mauna Kea (canonical iOS, no web equivalent) — an astronomical 24-hour sky watch. FRONT:
 * a 24-hour day/night ring referenced to apparent solar time (longitude + EOT), dawn/dusk twilight
 * wedges, sun position (rayed) + moon position at their hour angles, a rotating zodiac sky wheel, a
 * UT 24h hand, the Equation-of-Time dial + hand, and date/month/year windows. BACK: a moon-phase
 * terminator porthole + little-moon, a 24h moon dial driven by moonNoonAngle, moonrise/moonset
 * 'rise'/'set' limb hands, and Sidereal/Solar sub-dials. Exercises new leaves (latitude, polar
 * summer/winter, moonNoonAngle, vernalEquinoxAngle, sun rise/set indicators) + rise/set hand bodies.
 */
class MaunaKeaRenderTest {

    private fun resourceText(name: String) =
        MaunaKeaRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        MaunaKeaRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    private fun img(name: String, scale: Double) = LoadedImage(AwtImage(resourceImage(name)), scale)

    private fun render(mode: ModeFilter, bg: java.awt.Color, suffix: String, images: Map<String, LoadedImage>) {
        val xml = resourceText("/MaunaKea.xml")
        val watch = parseWatchXml(xml, mode)
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()  // 10:08:36 PDT
        }
        applyInits(watch, env)
        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "maunakea$suffix")
        assertTrue(watch.parts.isNotEmpty(), "MaunaKea$suffix parts should be present")
    }

    @Test fun rendersMaunaKeaFront() = render(
        ModeFilter.FRONT, java.awt.Color.WHITE, "",
        mapOf(
            "astro-face.png" to img("/maunakea-face-4x.png", 0.25),
            "EOT.png" to img("/maunakea-eot-4x.png", 0.25),
            "zodiacWheel.png" to img("/maunakea-zodiac-4x.png", 0.25),
            "morningHD.png" to img("/maunakea-morning-4x.png", 0.25),
            "eveningHD.png" to img("/maunakea-evening-4x.png", 0.25),
            "moon25.png" to img("/maunakea-moon25-4x.png", 0.25),
        ),
    )

    @Test fun rendersMaunaKeaNight() = render(
        ModeFilter.NIGHT, java.awt.Color.BLACK, "-night",
        mapOf(
            "CMa.png" to img("/maunakea-cma.png", 1.0),
            "zodiacWheelN.png" to img("/maunakea-zodiacn-4x.png", 0.25),
            "moon25black.png" to img("/maunakea-moon25black-4x.png", 0.25),
        ),
    )

    @Test fun rendersMaunaKeaBack() = render(
        ModeFilter.BACK, java.awt.Color.WHITE, "-back",
        mapOf(
            "stars.png" to img("/maunakea-stars.png", 1.0),
            "../partsBin/berry-green.png" to img("/maunakea-berry-green-4x.png", 0.25),
            "../partsBin/moonES80.png" to img("/maunakea-moones80-4x.png", 0.25),
            "moon25f.png" to img("/maunakea-moon25f-4x.png", 0.25),
        ),
    )
}
