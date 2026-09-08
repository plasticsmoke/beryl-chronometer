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
 * Renders London (canonical iOS, no web equivalent) — a fully image-based SQUARE Big Ben wristwatch.
 * The ornate Westminster clock dial is baked into bbfaceplus.png; band.png is the leather strap that
 * runs top and bottom; the only moving parts are the hour/minute image hands (no second hand,
 * beatsPerSecond=0). Front/back share the hands; night swaps to the illuminated dial. Like McAlester
 * it's a non-round image face, so the staged watch tag carries a large faceWidth (and no bezelColor)
 * to disable the circular clip / procedural bezel — the band art supplies the silhouette.
 *
 * The iOS art is low-res (304px face upscaled into a 512 atlas), so the dial is inherently soft; no
 * higher-res asset exists (London is not in the web port). Bicubic interpolation keeps the upscale
 * smooth rather than blocky.
 */
class LondonRenderTest {

    private fun resourceText(name: String) =
        LondonRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        LondonRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    private fun img(name: String) = LoadedImage(AwtImage(resourceImage(name)), 1.0)

    private fun render(mode: ModeFilter, bg: java.awt.Color, suffix: String, images: Map<String, LoadedImage>) {
        val xml = resourceText("/London.xml")
        val watch = parseWatchXml(xml, mode)
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()  // 10:08:36 PDT
        }
        applyInits(watch, env)
        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "london$suffix")
        assertTrue(watch.parts.isNotEmpty(), "London$suffix parts should be present")
    }

    private val hands = mapOf(
        "bbhour.png" to img("/london-bbhour.png"),
        "bbminute.png" to img("/london-bbminute.png"),
    )

    @Test fun rendersLondonFront() = render(
        ModeFilter.FRONT, java.awt.Color.WHITE, "",
        hands + mapOf(
            "bbfaceplus.png" to img("/london-bbfaceplus.png"),
            "band.png" to img("/london-band.png"),
        ),
    )

    @Test fun rendersLondonNight() = render(
        ModeFilter.NIGHT, java.awt.Color.BLACK, "-night",
        hands + mapOf(
            "bbfaceplusnight.png" to img("/london-bbfaceplusnight.png"),
            "band-night.png" to img("/london-band-night.png"),
        ),
    )

    @Test fun rendersLondonBack() = render(
        ModeFilter.BACK, java.awt.Color.WHITE, "-back",
        hands + mapOf(
            "bbfaceplusback.png" to img("/london-bbfaceplusback.png"),
            "band.png" to img("/london-band.png"),
        ),
    )
}
