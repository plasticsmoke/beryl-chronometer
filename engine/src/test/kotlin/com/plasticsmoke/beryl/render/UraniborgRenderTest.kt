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
 * Renders Uraniborg (canonical iOS, no web equivalent) — Tycho Brahe's observatory clock. The FRONT
 * is a Local Sidereal Time face: a 24-hour sidereal dial (0..23h), minute ring, sidereal hr/min/sec
 * hands, sun/moon day-night rings referenced to LST (masterOffset), moonrise/set hands, sun & moon
 * position icons at their RA, the zodiac-names ring, and the curved "Local Sidereal Time" label
 * (the new circular Qtext). NIGHT is the lumed sidereal dial; BACK is the Civil-Time face with UT
 * hand, tri hour/min/sec hands, sun/moon rings and the curved "Civil Time" label.
 */
class UraniborgRenderTest {

    private fun resourceText(name: String) =
        UraniborgRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        UraniborgRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    private fun img(name: String, scale: Double) = LoadedImage(AwtImage(resourceImage(name)), scale)

    private fun render(mode: ModeFilter, bg: java.awt.Color, suffix: String, images: Map<String, LoadedImage>) {
        val xml = resourceText("/Uraniborg.xml")
        val watch = parseWatchXml(xml, mode)
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()  // 10:08:36 PDT
        }
        applyInits(watch, env)
        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "uraniborg$suffix")
        assertTrue(watch.parts.isNotEmpty(), "Uraniborg$suffix parts should be present")
    }

    @Test fun rendersUraniborgFront() = render(
        ModeFilter.FRONT, java.awt.Color.WHITE, "",
        mapOf(
            "../partsBin/HD/white/face.png" to img("/uraniborg-face-4x.png", 0.25),
            "zodiacNames.png" to img("/uraniborg-zodiac-2x.png", 0.5),
            "../partsBin/berry-shadow.png" to img("/uraniborg-berry-shadow.png", 1.0),
            "../partsBin/berry.png" to img("/uraniborg-berry-4x.png", 0.25),
            "../Mauna Kea/moon25.png" to img("/uraniborg-moon25-4x.png", 0.25),
        ),
    )

    @Test fun rendersUraniborgNight() = render(
        ModeFilter.NIGHT, java.awt.Color.BLACK, "-night",
        mapOf("../partsBin/berry-lum.png" to img("/uraniborg-berry-lum-4x.png", 0.25)),
    )

    @Test fun rendersUraniborgBack() = render(
        ModeFilter.BACK, java.awt.Color.WHITE, "-back",
        mapOf(
            "../partsBin/HD/white/face.png" to img("/uraniborg-face-4x.png", 0.25),
            "../partsBin/logos/black.png" to img("/uraniborg-logo-black-4x.png", 0.25),
        ),
    )
}
