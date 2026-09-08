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
 * Renders McAlester's BACK — the closed pocket-watch back: just the case (case.png) and the hinged
 * cover (cover.png). The "inmates"/"guts" layers (the open-the-case animation) orbit off-screen at
 * the default stat=0 (closed), so they don't appear and their images aren't needed.
 */
class McAlesterBackRenderTest {

    private fun resourceImage(name: String) =
        McAlesterBackRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun rendersMcAlesterBackToPng() {
        val xml = McAlesterBackRenderTest::class.java.getResourceAsStream("/McAlester.xml")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val watch = parseWatchXml(xml, ModeFilter.BACK)

        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()
        }
        applyInits(watch, env)

        val images = mapOf(
            "case.png" to LoadedImage(AwtImage(resourceImage("/mcalester-case-4x.png")), 0.25),
            "stem.png" to LoadedImage(AwtImage(resourceImage("/mcalester-stem-4x.png")), 0.25),
            "resetH.png" to LoadedImage(AwtImage(resourceImage("/mcalester-reseth-4x.png")), 0.25),
            "cover.png" to LoadedImage(AwtImage(resourceImage("/mcalester-cover-4x.png")), 0.25),
        )

        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "mcalester-back")

        assertTrue(watch.parts.isNotEmpty(), "McAlester back parts should be present")
    }
}
