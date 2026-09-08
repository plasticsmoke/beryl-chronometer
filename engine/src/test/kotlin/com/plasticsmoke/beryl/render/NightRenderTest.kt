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
 * Renders Geneva's NIGHT mode (the dark-themed parallel face) from the original iOS XML — which
 * still has the night parts the web port stripped. Proves mode switching + night rendering work.
 */
class NightRenderTest {

    private fun resourceText(name: String) =
        NightRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        NightRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun rendersGenevaNightToPng() {
        val xml = resourceText("/Geneva-ios.xml")
        val watch = parseWatchXml(xml, ModeFilter.NIGHT)

        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T10:09:36Z").toEpochMilli()
        }
        applyInits(watch, env)

        val images = mapOf(
            "../partsBin/moonES80.png" to LoadedImage(AwtImage(resourceImage("/moonES80-4x.png")), 0.25),
            "../partsBin/moonNightcastAW80.png" to LoadedImage(AwtImage(resourceImage("/moonNightcastAW80-2x.png")), 0.5),
        )

        val out = PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "geneva-night")

        // Night face is predominantly dark (vs the light front). Sample the inner disc.
        var dark = 0; var total = 0
        val px = out.width; val cxp = out.width / 2; val cyp = out.height / 2; val faceR = (watch.faceWidth / 2) * PhoneFrame.SCALE
        var y = 0
        while (y < out.height) {
            var x = 0
            while (x < px) {
                val dx = x - cxp; val dy = y - cyp
                if (dx * dx + dy * dy < (faceR * 0.9) * (faceR * 0.9)) {
                    val c = out.getRGB(x, y)
                    total++
                    if (((c ushr 16) and 0xFF) < 80 && ((c ushr 8) and 0xFF) < 80 && (c and 0xFF) < 80) dark++
                }
                x += 4
            }
            y += 4
        }
        assertTrue(dark.toDouble() / total > 0.6, "night face should be predominantly dark (${dark * 100 / total}%)")
        assertTrue(watch.parts.isNotEmpty(), "night parts should be present")
    }
}
