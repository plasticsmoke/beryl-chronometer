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
 * Renders the BACK of Chandra — the phase-tracking dial: the moon's far side, the lunar phase dial
 * (ecliptic-longitude tick ring) with the phase-angle hand, the four cardinal phase portholes
 * (new / first-quarter / full / third-quarter moon) each labelled with the day-of-month of the
 * closest such phase, and the year/month/day digit wheels. Exercises the new closest-phase
 * day-number astronomy.
 */
class ChandraBackRenderTest {

    private fun resourceText(name: String) =
        ChandraBackRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        ChandraBackRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun rendersChandraBackToPng() {
        val xml = resourceText("/Chandra-ios.xml")
        val watch = parseWatchXml(xml, ModeFilter.BACK)

        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T10:09:36Z").toEpochMilli()
        }
        applyInits(watch, env)

        val images = mapOf(
            "moon-farside.png" to LoadedImage(AwtImage(resourceImage("/moon-farside-4x.png")), 0.25),
            "phaseN.png" to LoadedImage(AwtImage(resourceImage("/phaseN.png")), 1.0),
            "phase1.png" to LoadedImage(AwtImage(resourceImage("/phase1-4x.png")), 0.25),
            "phase3.png" to LoadedImage(AwtImage(resourceImage("/phase3-4x.png")), 0.25),
            "phaseF.png" to LoadedImage(AwtImage(resourceImage("/phaseF.png")), 1.0),
            "../partsBin/logos/black.png" to LoadedImage(AwtImage(resourceImage("/logo-black-4x.png")), 0.25),
        )

        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "chandra-back")

        assertTrue(watch.parts.isNotEmpty(), "Chandra back parts should be present")
    }
}
