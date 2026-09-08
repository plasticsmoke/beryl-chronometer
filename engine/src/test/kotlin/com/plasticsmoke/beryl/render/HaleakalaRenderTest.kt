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
 * Renders Haleakala (canonical iOS, no web equivalent) — a sun/moon compass watch. The FRONT is a
 * SUN compass: azimuth ring (N/E/S/W + degrees), altitude scale with twilight dots, sun azimuth +
 * altitude hands, two sunrise/sunset sub-dials with hr/min hands, date/month/weekday windows, am/pm
 * porthole, and main hr/min/sec hands. The BACK is the MOON equivalent: moon azimuth/altitude hands,
 * moonrise/moonset sub-dials + AM/PM wheels, and a moon-phase terminator porthole (moonAgeAngle /
 * moonRelativePositionAngle). NIGHT is the dark dial. All part types pre-exist; no new astronomy.
 */
class HaleakalaRenderTest {

    private fun resourceText(name: String) =
        HaleakalaRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        HaleakalaRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    private fun img(name: String, scale: Double) = LoadedImage(AwtImage(resourceImage(name)), scale)

    private fun render(mode: ModeFilter, bg: java.awt.Color, suffix: String, images: Map<String, LoadedImage>) {
        val xml = resourceText("/Haleakala.xml")
        val watch = parseWatchXml(xml, mode)
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()  // 10:08:36 PDT
        }
        applyInits(watch, env)
        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "haleakala$suffix")
        assertTrue(watch.parts.isNotEmpty(), "Haleakala$suffix parts should be present")
    }

    @Test fun rendersHaleakalaFront() = render(
        ModeFilter.FRONT, java.awt.Color.WHITE, "",
        mapOf(
            "Haleakala-face.png" to img("/haleakala-face.png", 1.0),
            "../partsBin/logos/black.png" to img("/haleakala-logo-black-4x.png", 0.25),
        ),
    )

    @Test fun rendersHaleakalaNight() = render(
        ModeFilter.NIGHT, java.awt.Color.BLACK, "-night", emptyMap(),
    )

    @Test fun rendersHaleakalaBack() = render(
        ModeFilter.BACK, java.awt.Color.WHITE, "-back",
        mapOf(
            "Haleakala-back.png" to img("/haleakala-back-4x.png", 0.25),
            "../partsBin/logos/black.png" to img("/haleakala-logo-black-4x.png", 0.25),
            "pm-window-border-shadow.png" to img("/haleakala-pm-winshadow.png", 1.0),
        ),
    )
}
