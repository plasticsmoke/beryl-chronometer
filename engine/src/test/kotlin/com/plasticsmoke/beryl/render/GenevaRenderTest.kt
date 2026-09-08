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
 * Renders the Geneva face through the engine renderer to a viewable PNG. Geneva exercises the
 * newly-added pieces: QDial marks (outer/tickOut/center), hand ornaments + tail circles, and
 * image hands, on top of the faceFront.png background.
 *
 * The core time/calendar hands (hr/min/sec/day/month/weekday/24h/year/century) use functions the
 * engine provides and are correct. The astronomy bits (moon phase, rise/set, seasons) aren't
 * ported yet — their functions are stubbed to 0 so rendering doesn't crash; those indicators park
 * at neutral positions. QWheel/QWedge/terminator/window parts are not yet rendered.
 */
class GenevaRenderTest {

    private fun resourceText(name: String): String =
        GenevaRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String): BufferedImage =
        GenevaRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun rendersGenevaToPng() {
        val xml = resourceText("/Geneva-ios.xml")
        val watch = parseWatchXml(xml, ModeFilter.FRONT)

        val env = createDefaultEnvironment()
        // Live astronomy at San Francisco — no stubs; the moon disc libration / terminator
        // rotation (moonRelativeAngle/PositionAngle) remain stubbed in WatchEnvironment.
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T10:09:36Z").toEpochMilli()
        }
        applyInits(watch, env)

        // Use the 4x hi-res assets at scale 0.25 (same XML size, 4x pixels) so the baked-in text
        // ("Emerald Sequoia", Sunrise/Sunset/Moonrise/Moonset, season labels) stays sharp.
        // seasonrefs has no 4x variant, so it stays 1x.
        val images = mapOf(
            "faceFront.png" to LoadedImage(AwtImage(resourceImage("/faceFront-4x.png")), 0.25),
            "seasons.png" to LoadedImage(AwtImage(resourceImage("/seasons-4x.png")), 0.25),
            "../partsBin/moonES80.png" to LoadedImage(AwtImage(resourceImage("/moonES80-4x.png")), 0.25),
            "seasonrefs.png" to LoadedImage(AwtImage(resourceImage("/seasonrefs.png")), 1.0),
            "YEAR.png" to LoadedImage(AwtImage(resourceImage("/geneva-year.png")), 1.0),
            "BCE.png" to LoadedImage(AwtImage(resourceImage("/geneva-bce.png")), 1.0),
            "CE.png" to LoadedImage(AwtImage(resourceImage("/geneva-ce.png")), 1.0),
            "BCE-arrow.png" to LoadedImage(AwtImage(resourceImage("/geneva-bce-arrow.png")), 1.0),
            "CE-arrow.png" to LoadedImage(AwtImage(resourceImage("/geneva-ce-arrow.png")), 1.0),
        )

        val out = PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "geneva")

        // Sanity: a rich, non-trivial rendering (face image + dials + hands).
        assertTrue(distinctColors(out) > 200, "expected a rich Geneva rendering")
    }

    private fun distinctColors(img: BufferedImage): Int {
        val seen = HashSet<Int>()
        var y = 0
        while (y < img.height && seen.size <= 220) {
            var x = 0
            while (x < img.width) { seen.add(img.getRGB(x, y)); x += 3 }
            y += 3
        }
        return seen.size
    }
}
