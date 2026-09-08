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
 * Renders Tombstone (canonical iOS) — the SKELETON watch: the full going train is drawn as procedural
 * type='gear' QHands (mainspring barrel, center/third/fourth wheels, escape wheel, balance, pinions)
 * plus escape-wheel/pallet-lever image hands, under a Roman-numeral dial with Breguet hr/min hands,
 * all inside a round pocket-watch case (case19.png, with a pendant loop → non-round, so a portrait
 * canvas + large faceWidth disables the circular clip/bezel like McAlester). Back = mirrored movement
 * over the engraved back plate. The slow third wheel uses currentTime(); everything else is clockwork.
 */
class TombstoneRenderTest {

    private fun resourceText(name: String) =
        TombstoneRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        TombstoneRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    private fun img(name: String, scale: Double) = LoadedImage(AwtImage(resourceImage(name)), scale)

    private fun render(mode: ModeFilter, bg: java.awt.Color, suffix: String, images: Map<String, LoadedImage>) {
        val xml = resourceText("/Tombstone.xml")
        val watch = parseWatchXml(xml, mode)
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()  // 10:08:36 PDT, Fri Jun 12 2026
        }
        applyInits(watch, env)
        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "tombstone$suffix")
        assertTrue(watch.parts.isNotEmpty(), "Tombstone$suffix parts should be present")
    }

    /**
     * Canvas backdrop standing in for the iOS app's granite wallpaper (sampled from the iPhone
     * screenshots, ≈ RGB 50/52/47): the case's dial opening is a transparent hole in case19.png,
     * so whatever is behind the watch shows through between the gears — exactly as on the phone.
     */
    private val granite = java.awt.Color(50, 52, 47)

    @Test fun rendersTombstoneFront() = render(
        ModeFilter.FRONT, granite, "",
        mapOf(
            "case19.png" to img("/tombstone-case19.png", 1.0),
            "stem19.png" to img("/tombstone-stem19.png", 1.0),
            "../Kyoto/reset.png" to img("/kyoto-reset.png", 1.0),
            "guts18front.png" to img("/tombstone-guts.png", 1.0),
            "escWheelf.png" to img("/tombstone-escwheelf.png", 1.0),
            "escapeLever.png" to img("/tombstone-esclever.png", 1.0),
            "blender.png" to img("/tombstone-blender-2x.png", 0.5),
            "../partsBin/berry.png" to img("/tombstone-berry-4x.png", 0.25),
        ),
    )

    @Test fun rendersTombstoneNight() = render(
        ModeFilter.NIGHT, java.awt.Color.BLACK, "-night",
        mapOf(
            "case19n.png" to img("/tombstone-case19n.png", 1.0),
            "stem19n.png" to img("/tombstone-stem19n.png", 1.0),
            "../Kyoto/reset.png" to img("/kyoto-reset.png", 1.0),
            "escWheelf.png" to img("/tombstone-escwheelf.png", 1.0),
            "escapeLevern.png" to img("/tombstone-esclevern.png", 1.0),
            "../partsBin/berry-lum.png" to img("/tombstone-berrylum-4x.png", 0.25),
        ),
    )

    @Test fun rendersTombstoneBack() = render(
        ModeFilter.BACK, granite, "-back",
        mapOf(
            "case19.png" to img("/tombstone-case19.png", 1.0),
            "stem19.png" to img("/tombstone-stem19.png", 1.0),
            "../Kyoto/reset.png" to img("/kyoto-reset.png", 1.0),
            "../partsBin/berry.png" to img("/tombstone-berry-4x.png", 0.25),
            "blender.png" to img("/tombstone-blender-2x.png", 0.5),
            "blenderk.png" to img("/tombstone-blenderk-2x.png", 0.5),
            "escapeLever.png" to img("/tombstone-esclever.png", 1.0),
            "escWheel.png" to img("/tombstone-escwheel.png", 1.0),
            "back.png" to img("/tombstone-back-2x.png", 0.5),
        ),
    )
}
