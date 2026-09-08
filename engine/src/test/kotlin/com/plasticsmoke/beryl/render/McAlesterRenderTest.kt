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
 * Renders McAlester's front — a fully image-based pocket watch (canonical iOS, no web equivalent).
 * Unlike the QDial faces, the dial/numbers are baked into face.png, the case frame is case.png, and
 * the hands are PNGs pivoted at xAnchor/yAnchor: hour/minute at the main dial (y=-45) and a small
 * seconds hand in its own sub-dial at the bottom (y=-120). Portrait geometry (320x480), so the test
 * drives a portrait canvas and a large faceWidth disables the circular clip / procedural bezel
 * (case.png supplies the silhouette).
 */
class McAlesterRenderTest {

    private fun resourceText(name: String) =
        McAlesterRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        McAlesterRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun rendersMcAlesterToPng() {
        val xml = resourceText("/McAlester.xml")
        val watch = parseWatchXml(xml, ModeFilter.FRONT)

        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()  // 10:08:36 PDT
        }
        applyInits(watch, env)

        val images = mapOf(
            "face.png" to LoadedImage(AwtImage(resourceImage("/mcalester-face-4x.png")), 0.25),
            "stem.png" to LoadedImage(AwtImage(resourceImage("/mcalester-stem-4x.png")), 0.25),
            "resetH.png" to LoadedImage(AwtImage(resourceImage("/mcalester-reseth-4x.png")), 0.25),
            "case.png" to LoadedImage(AwtImage(resourceImage("/mcalester-case-4x.png")), 0.25),
            "second.png" to LoadedImage(AwtImage(resourceImage("/mcalester-second-4x.png")), 0.25),
            "hour.png" to LoadedImage(AwtImage(resourceImage("/mcalester-hour-4x.png")), 0.25),
            "minute.png" to LoadedImage(AwtImage(resourceImage("/mcalester-minute-4x.png")), 0.25),
        )

        // Face image is 320x480 XML units; render portrait at ~1024 tall.
        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "mcalester")

        assertTrue(watch.parts.isNotEmpty(), "McAlester parts should be present")
    }
}
