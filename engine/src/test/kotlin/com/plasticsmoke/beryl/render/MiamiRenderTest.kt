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
 * Renders Miami's front — the planetary watch (canonical iOS): seven concentric day/night rings
 * (Sun, Moon, Saturn, Jupiter, Mars, Venus, Mercury from outer to inner), each showing when that
 * body is up, with a label hand on each ring pointing to the body's transit. Exercises the newly
 * ported WB planet theory (Mercury..Saturn) through the day/night-ring + transit leaves.
 */
class MiamiRenderTest {

    private fun resourceText(name: String) =
        MiamiRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        MiamiRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun rendersMiamiToPng() {
        val xml = resourceText("/Miami.xml")
        val watch = parseWatchXml(xml, ModeFilter.FRONT)

        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        // The selected back body; harmless for the front but lets the bodySlot init evaluate.
        env.variables["body"] = 7.0  // Saturn
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:09:36Z").toEpochMilli()  // 10:09 PDT
        }
        applyInits(watch, env)

        // Literal entries only — tools/gen_face_manifest.py extracts these into faces.json.
        val images = mapOf(
            "../partsBin/HD/white/face.png" to LoadedImage(AwtImage(resourceImage("/miami-face-4x.png")), 0.25),
            "SunLabel.png" to LoadedImage(AwtImage(resourceImage("/miami-sunlabel-4x.png")), 0.25),
            "SaturnLabel.png" to LoadedImage(AwtImage(resourceImage("/miami-saturnlabel-4x.png")), 0.25),
            "JupiterLabel.png" to LoadedImage(AwtImage(resourceImage("/miami-jupiterlabel-4x.png")), 0.25),
            "MarsLabel.png" to LoadedImage(AwtImage(resourceImage("/miami-marslabel-4x.png")), 0.25),
            "VenusLabel.png" to LoadedImage(AwtImage(resourceImage("/miami-venuslabel-4x.png")), 0.25),
            "MercuryLabel.png" to LoadedImage(AwtImage(resourceImage("/miami-mercurylabel-4x.png")), 0.25),
            "MoonLabel.png" to LoadedImage(AwtImage(resourceImage("/miami-moonlabel-4x.png")), 0.25),
        )

        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "miami")

        assertTrue(watch.parts.isNotEmpty(), "Miami parts should be present")
    }
}
