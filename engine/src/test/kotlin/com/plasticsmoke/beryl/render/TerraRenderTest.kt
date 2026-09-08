package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.astro.registerTerraFunctions
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
 * Renders Terra (canonical iOS, world-time watch). Phase 2 = the BACK: four timezone subdials (one
 * large local + three small), each a day/night sun ring + hr/min/sec hands + weekday/AM-PM windows
 * driven by the per-slot world-time leaves (slots 1–4), plus a moon-phase terminator. Front (the
 * 24-city ring) is phase 3.
 */
class TerraRenderTest {

    private fun resourceText(name: String) =
        TerraRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        TerraRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    private fun img(name: String, scale: Double) = LoadedImage(AwtImage(resourceImage(name)), scale)

    private fun render(mode: ModeFilter, bg: java.awt.Color, suffix: String, images: Map<String, LoadedImage>) {
        val xml = resourceText("/Terra.xml")
        val watch = parseWatchXml(xml, mode)
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        val nowMs = Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) { nowMs }
        registerTerraFunctions(env) { nowMs }
        applyInits(watch, env)
        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "terra$suffix")
        assertTrue(watch.parts.isNotEmpty(), "Terra$suffix parts should be present")
    }

    @Test fun rendersTerraFront() = render(
        ModeFilter.FRONT, java.awt.Color.WHITE, "",
        mapOf(
            "../partsBin/HD/rose/face.png" to img("/terra-face.png", 1.0),
            "worldtimeRingBackground.png" to img("/terra-ring-4x.png", 0.25),
            "continents.png" to img("/terra-continents-4x.png", 0.25),
            "../partsBin/logos/black.png" to img("/terra-logo-4x.png", 0.25),
        ),
    )

    @Test fun rendersTerraNight() = render(
        ModeFilter.NIGHT, java.awt.Color.BLACK, "-night",
        mapOf(
            "../partsBin/HD/rose/faceNight.png" to img("/terra-facenight.png", 1.0),
            "continentsN.png" to img("/terra-continentsn-4x.png", 0.25),
        ),
    )

    @Test fun rendersTerraBack() = render(
        ModeFilter.BACK, java.awt.Color.WHITE, "-back",
        mapOf(
            "../partsBin/HD/rose/face.png" to img("/terra-face.png", 1.0),
            "holeyLatLongGrid.png" to img("/terra-grid-4x.png", 0.25),
            "../partsBin/berry.png" to img("/terra-berry-4x.png", 0.25),
            "../partsBin/HD/rose/dial104.png" to img("/terra-dial104-4x.png", 0.25),
            "../partsBin/HD/rose/dial158.png" to img("/terra-dial158-4x.png", 0.25),
            "../partsBin/moonES28.png" to img("/terra-moon28-4x.png", 0.25),
        ),
    )
}
