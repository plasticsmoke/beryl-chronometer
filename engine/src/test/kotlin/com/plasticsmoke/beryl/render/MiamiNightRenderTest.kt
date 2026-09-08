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
 * Renders Miami's NIGHT mode — the planetary 7-ring face in the dim teal night palette on a black
 * dial (the ring labels are shared front|night). Same WB planet theory as the front.
 */
class MiamiNightRenderTest {

    private fun resourceText(name: String) =
        MiamiNightRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        MiamiNightRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun rendersMiamiNightToPng() {
        val xml = resourceText("/Miami.xml")
        val watch = parseWatchXml(xml, ModeFilter.NIGHT)

        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        env.variables["body"] = 7.0
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:09:36Z").toEpochMilli()
        }
        applyInits(watch, env)

        // Literal entries only — tools/gen_face_manifest.py extracts these into faces.json.
        val images = mapOf(
            "SunLabel.png" to LoadedImage(AwtImage(resourceImage("/miami-sunlabel-4x.png")), 0.25),
            "SaturnLabel.png" to LoadedImage(AwtImage(resourceImage("/miami-saturnlabel-4x.png")), 0.25),
            "JupiterLabel.png" to LoadedImage(AwtImage(resourceImage("/miami-jupiterlabel-4x.png")), 0.25),
            "MarsLabel.png" to LoadedImage(AwtImage(resourceImage("/miami-marslabel-4x.png")), 0.25),
            "VenusLabel.png" to LoadedImage(AwtImage(resourceImage("/miami-venuslabel-4x.png")), 0.25),
            "MercuryLabel.png" to LoadedImage(AwtImage(resourceImage("/miami-mercurylabel-4x.png")), 0.25),
            "MoonLabel.png" to LoadedImage(AwtImage(resourceImage("/miami-moonlabel-4x.png")), 0.25),
        )

        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "miami-night")

        assertTrue(watch.parts.isNotEmpty(), "Miami night parts should be present")
    }
}
