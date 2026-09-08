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
 * Renders Hernandez (canonical iOS) — the SUN/MOON ALTITUDE-AZIMUTH watch. An azimuth compass ring
 * (N/E/S/W + degrees) surrounds an inner 12-hour dial; the center is a log-scaled altitude gauge
 * (a fan of 'wire' hands) over a twilight-gradient disk, with sun/moon position icons placed by
 * sunAzimuth/moonAzimuth + sunAltitude/moonAltitude. Plus a moon-phase terminator porthole and
 * date/month/AM-PM windows. Pure assembly — all leaves (incl. log()) already exist.
 */
class HernandezRenderTest {

    private fun resourceText(name: String) =
        HernandezRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        HernandezRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    private fun img(name: String, scale: Double) = LoadedImage(AwtImage(resourceImage(name)), scale)

    private fun render(mode: ModeFilter, bg: java.awt.Color, suffix: String, images: Map<String, LoadedImage>) {
        val xml = resourceText("/Hernandez.xml")
        val watch = parseWatchXml(xml, mode)
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()  // 10:08:36 PDT, Fri Jun 12 2026
        }
        applyInits(watch, env)
        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "hernandez$suffix")
        assertTrue(watch.parts.isNotEmpty(), "Hernandez$suffix parts should be present")
    }

    @Test fun rendersHernandezFront() = render(
        ModeFilter.FRONT, java.awt.Color.WHITE, "",
        mapOf(
            "../partsBin/HD/white/faceNight.png" to img("/hernandez-facenight-4x.png", 0.25),
            "twilightDisk3.png" to img("/hernandez-twilight.png", 1.0),
            "../Mauna Kea/moon25.png" to img("/moon25-4x.png", 0.25),
            "moon8.png" to img("/hernandez-moon8.png", 1.0),
        ),
    )

    @Test fun rendersHernandezNight() = render(
        ModeFilter.NIGHT, java.awt.Color.BLACK, "-night",
        mapOf(
            "../partsBin/HD/white/faceNight.png" to img("/hernandez-facenight-4x.png", 0.25),
        ),
    )

    @Test fun rendersHernandezBack() = render(
        ModeFilter.BACK, java.awt.Color.WHITE, "-back",
        mapOf(
            "back.png" to img("/hernandez-back-4x.png", 0.25),
        ),
    )
}
