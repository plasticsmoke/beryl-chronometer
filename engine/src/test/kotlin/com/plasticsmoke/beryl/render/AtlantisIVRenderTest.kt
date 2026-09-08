package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.astro.registerWatchFunctions
import com.plasticsmoke.beryl.expr.Environment
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
 * Renders AtlantisIV (canonical iOS) — the all-DIGITAL GPS watch ("Look Ma, no hands!"): time/date/
 * weekday/AM-PM/TZ digit SWheels on the front (revealed through windows in a starfield), and
 * latitude/longitude/altitude/position-error digit wheels on the back. The GPS sensor leaves
 * (altitude, horizontalPositionError, SIUnits, locationIndicatorAngle) are iOS-only, so they're
 * stubbed to a plausible valid fix (metric, 16 m alt, ±5 m). Everything else reads live.
 */
class AtlantisIVRenderTest {

    private fun resourceText(name: String) =
        AtlantisIVRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        AtlantisIVRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    private fun img(name: String, scale: Double) = LoadedImage(AwtImage(resourceImage(name)), scale)

    /** Stub the iOS-only GPS sensor leaves to a valid fix (metric units, 16 m altitude, ±5 m error). */
    private fun stubGps(env: Environment) {
        val f = env.functions
        f["SIUnits"] = { 1.0 }
        f["altitude"] = { 16.0 }
        f["horizontalPositionError"] = { 5.0 }
        f["locationIndicatorAngle"] = { Math.PI / 4 }
    }

    private fun render(mode: ModeFilter, bg: java.awt.Color, suffix: String, images: Map<String, LoadedImage>) {
        val xml = resourceText("/AtlantisIV.xml")
        val watch = parseWatchXml(xml, mode)
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()  // 10:08:36 PDT, Fri Jun 12 2026
        }
        stubGps(env)
        applyInits(watch, env)
        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "atlantisiv$suffix")
        assertTrue(watch.parts.isNotEmpty(), "AtlantisIV$suffix parts should be present")
    }

    private val stars = "../Chandra/stars.png" to img("/chandra-stars-4x.png", 0.25)
    private val whiteLogo = "../partsBin/logos/white.png" to img("/logo-white-4x.png", 0.25)

    @Test fun rendersAtlantisIVFront() = render(
        ModeFilter.FRONT, java.awt.Color.BLACK, "", mapOf(stars, whiteLogo),
    )

    @Test fun rendersAtlantisIVNight() = render(
        ModeFilter.NIGHT, java.awt.Color.BLACK, "-night", mapOf(stars),
    )

    @Test fun rendersAtlantisIVBack() = render(
        ModeFilter.BACK, java.awt.Color.WHITE, "-back",
        mapOf(
            "polarGrey.png" to img("/atlantisiv-polargrey-4x.png", 0.25),
            "../partsBin/logos/white-blackback.png" to img("/atlantisiv-logoblackback-2x.png", 0.5),
        ),
    )
}
