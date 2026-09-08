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
 * Renders Kyoto (canonical iOS, no web equivalent) — a WADOKEI (traditional Japanese variable-hour
 * clock) in a portrait case. Daylight (sunrise→sunset) and night (sunset→sunrise) are each divided
 * into 6 equal "hours", so the hour positions shift with the season. Front = fixed traditional dial
 * (zodiac-sign + numeral rings) with one constant-rate wadokei hand; back = the variable layout with
 * 12 zodiac-sign + 12 numeral spokes + 48 'wire' ticks all placed by angleForJapanHour(N), a
 * day/night ring (planetMidnightSun), and standard 24h hr/min/sec. Like McAlester, a portrait canvas
 * + a large faceWidth (no bezelColor) disables the circular clip / procedural bezel.
 */
class KyotoRenderTest {

    private fun resourceText(name: String) =
        KyotoRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        KyotoRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    private fun img(name: String, scale: Double) = LoadedImage(AwtImage(resourceImage(name)), scale)

    private fun render(mode: ModeFilter, bg: java.awt.Color, suffix: String, images: Map<String, LoadedImage>) {
        val xml = resourceText("/Kyoto.xml")
        val watch = parseWatchXml(xml, mode)
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()  // 10:08:36 PDT
        }
        applyInits(watch, env)
        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "kyoto$suffix")
        assertTrue(watch.parts.isNotEmpty(), "Kyoto$suffix parts should be present")
    }

    @Test fun rendersKyotoFront() = render(
        ModeFilter.FRONT, java.awt.Color.WHITE, "",
        mapOf(
            "case15.png" to img("/kyoto-case-2x.png", 0.5),
            "reset.png" to img("/kyoto-reset.png", 1.0),
            "button-bottom.png" to img("/kyoto-button-bottom.png", 1.0),
            // 4x hand (556x556) derived from the web port's higher-res Kyoto-hand art (same design as
            // iOS hand-2x, IoU 0.78, pivot-aligned) — supersamples the rotating hand so the rosette and
            // pointer stay crisp. iOS ships only hand-2x (278); this is above-iOS art. Loaded at 0.25.
            "hand.png" to img("/kyoto-hand-4x.png", 0.25),
            // Kyoto's winding crown reuses McAlester's stem art (canonical XML cross-reference).
            "../McAlester/stem.png" to img("/mcalester-stem-4x.png", 0.25),
            "../partsBin/berry-shadow.png" to img("/kyoto-berry-shadow.png", 1.0),
            "../partsBin/berry.png" to img("/kyoto-berry-4x.png", 0.25),
        ),
    )

    @Test fun rendersKyotoNight() = render(
        ModeFilter.NIGHT, java.awt.Color.BLACK, "-night",
        mapOf(
            "case15n.png" to img("/kyoto-casen.png", 1.0),
            "reset.png" to img("/kyoto-reset.png", 1.0),
            "button-bottom.png" to img("/kyoto-button-bottom.png", 1.0),
            // 4x luminous hand (556x556): crisp web-derived silhouette + the 1x teal glow gradient
            // (iOS ships lumi-hand at 1x only). Matches the front 4x hand's resolution. Loaded at 0.25.
            "lumi-hand.png" to img("/kyoto-lumihand-4x.png", 0.25),
            "../McAlester/stem-n.png" to img("/mcalester-stem-n-2x.png", 0.5),
            "../partsBin/berry-lum.png" to img("/kyoto-berry-lum-4x.png", 0.25),
        ),
    )

    @Test fun rendersKyotoBack() = render(
        ModeFilter.BACK, java.awt.Color.WHITE, "-back",
        mapOf(
            "case15b.png" to img("/kyoto-caseb-2x.png", 0.5),
            "rose.png" to img("/kyoto-rose.png", 1.0),
            "../partsBin/berry-shadow.png" to img("/kyoto-berry-shadow.png", 1.0),
            "../partsBin/berry.png" to img("/kyoto-berry-4x.png", 0.25),
        ),
    )
}
