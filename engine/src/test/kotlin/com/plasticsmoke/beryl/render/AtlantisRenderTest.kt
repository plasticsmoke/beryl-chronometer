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
 * Renders Atlantis (canonical iOS) — the PERPETUAL-CALENDAR + POSITION watch. Front: weekday/day/
 * month subdials, a 4-digit YEAR (digit dials + pointer hands), a small clock subdial, and DST /
 * AM-PM / leap-year / BC-AD indicator hands. Back: Latitude + Longitude as digit dials driven by
 * digitValue(latitudeDegrees/longitudeDegrees) with N/S/E/W. New leaves: digitValue, latitudeDegrees,
 * longitudeDegrees, leapYearIndicatorAngle1 (all added to the engine).
 */
class AtlantisRenderTest {

    private fun resourceText(name: String) =
        AtlantisRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        AtlantisRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    private fun img(name: String, scale: Double) = LoadedImage(AwtImage(resourceImage(name)), scale)

    private fun render(mode: ModeFilter, bg: java.awt.Color, suffix: String, images: Map<String, LoadedImage>) {
        val xml = resourceText("/Atlantis.xml")
        val watch = parseWatchXml(xml, mode)
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()  // 10:08:36 PDT, Fri Jun 12 2026
        }
        applyInits(watch, env)
        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "atlantis$suffix")
        assertTrue(watch.parts.isNotEmpty(), "Atlantis$suffix parts should be present")
    }

    @Test fun rendersAtlantisFront() = render(
        ModeFilter.FRONT, java.awt.Color.WHITE, "",
        mapOf("../partsBin/logos/black.png" to img("/logo-black-4x.png", 0.25)),
    )

    @Test fun rendersAtlantisNight() = render(ModeFilter.NIGHT, java.awt.Color.BLACK, "-night", emptyMap())

    @Test fun rendersAtlantisBack() = render(
        ModeFilter.BACK, java.awt.Color.WHITE, "-back",
        mapOf("../partsBin/berry.png" to img("/berry-4x.png", 0.25)),
    )
}
