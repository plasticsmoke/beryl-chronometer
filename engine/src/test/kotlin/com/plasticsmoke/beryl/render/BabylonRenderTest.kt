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
 * Renders Babylon (canonical iOS) — a perpetual GRID CALENDAR watch: a scrolling month grid (the new
 * calendar-wheel machinery: 4-quadrant day wheels, row covers/underlays showing adjacent-month days,
 * a weekday header, and day-indicator wires boxing today), a month window, a 4-digit year, a
 * moon-phase porthole, and Breguet hour/minute hands.
 */
class BabylonRenderTest {

    private fun resourceText(name: String) =
        BabylonRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        BabylonRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    private fun img(name: String) = LoadedImage(AwtImage(resourceImage(name)), 0.25)

    private fun render(mode: ModeFilter, bg: java.awt.Color, suffix: String, images: Map<String, LoadedImage>) {
        val xml = resourceText("/Babylon.xml")
        val watch = parseWatchXml(xml, mode)
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()  // 10:08:36 PDT, Fri Jun 12 2026
        }
        applyInits(watch, env)
        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "babylon$suffix")
        assertTrue(watch.parts.isNotEmpty(), "Babylon$suffix parts should be present")
    }

    @Test fun rendersBabylonFront() = render(
        ModeFilter.FRONT, java.awt.Color.WHITE, "",
        mapOf(
            "../partsBin/HD/rose/face.png" to img("/babylon-rose-face-4x.png"),
            "../partsBin/logos/black.png" to img("/babylon-logo-4x.png"),
            "../partsBin/moonES80.png" to img("/babylon-moon-4x.png"),
        ),
    )

    @Test fun rendersBabylonNight() = render(
        ModeFilter.NIGHT, java.awt.Color.BLACK, "-night",
        mapOf(
            "faceNight.png" to img("/babylon-facenight-4x.png"),
            "../partsBin/moonES80N.png" to img("/babylon-moonn-4x.png"),
        ),
    )

    @Test fun rendersBabylonBack() = render(
        ModeFilter.BACK, java.awt.Color.WHITE, "-back",
        mapOf(
            "back.png" to img("/babylon-back-4x.png"),
            "../partsBin/berry.png" to img("/babylon-berry-4x.png"),
        ),
    )
}
