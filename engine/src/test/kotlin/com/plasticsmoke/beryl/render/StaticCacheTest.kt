package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.astro.registerWatchFunctions
import com.plasticsmoke.beryl.expr.Environment
import com.plasticsmoke.beryl.expr.createDefaultEnvironment
import com.plasticsmoke.beryl.watch.ModeFilter
import com.plasticsmoke.beryl.watch.parseWatchXml
import java.awt.image.BufferedImage
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The static-run cache must be invisible: rendering with a warm cache has to produce the same
 * pixels as the single-pass path. Compositing a run through an extra offscreen layer re-rounds
 * anti-aliased edges (8-bit premultiplied SRC_OVER is only associative in exact arithmetic), so
 * a small per-channel tolerance is allowed on a small fraction of pixels.
 *
 * Faces chosen for the tricky splits: Paris (plain static run + buttons + chrome), AtlantisIV
 * (window-before-static reveal — windows cut the starfield to expose digit wheels drawn earlier),
 * Thebes (top-level windows + subdial art + stubbed leaves).
 */
class StaticCacheTest {

    private fun resourceText(name: String) =
        StaticCacheTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        StaticCacheTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    private fun img(name: String, scale: Double) = LoadedImage(AwtImage(resourceImage(name)), scale)

    private fun buildEnv(stubs: Map<String, Double>): Environment {
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()
        }
        for ((k, v) in stubs) env.functions[k] = { v }
        return env
    }

    private fun renderOnce(
        xml: String, mode: ModeFilter, env: Environment, images: Map<String, LoadedImage>,
        cache: StaticCache?, frames: Int,
    ): BufferedImage {
        val watch = parseWatchXml(xml, mode)
        applyInits(watch, env)
        val w = 512
        val h = 768
        lateinit var out: BufferedImage
        repeat(frames) {
            out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            renderFrame(AwtDrawingContext(g, w, h), watch, env, w / 320.0, w, h, images, cache)
            g.dispose()
        }
        return out
    }

    private fun assertCacheInvisible(name: String, xmlRes: String, mode: ModeFilter, stubs: Map<String, Double>, images: Map<String, LoadedImage>) {
        val xml = resourceText(xmlRes)
        val all = images + PhoneFrame.loadChrome(xml)
        // Same env instance for both paths so the displayed instant is identical.
        val env = buildEnv(stubs)
        val plain = renderOnce(xml, mode, env, all, cache = null, frames = 1)
        val cached = renderOnce(xml, mode, env, all, cache = StaticCache(), frames = 3)

        var off = 0
        val total = plain.width * plain.height
        for (y in 0 until plain.height) for (x in 0 until plain.width) {
            val a = plain.getRGB(x, y)
            val b = cached.getRGB(x, y)
            val d = maxOf(
                abs(((a shr 24) and 0xFF) - ((b shr 24) and 0xFF)),
                abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
                abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
                abs((a and 0xFF) - (b and 0xFF)),
            )
            if (d > 2) off++
        }
        val frac = off.toDouble() / total
        assertTrue(frac < 0.005, "$name: cached render deviates on ${"%.2f".format(frac * 100)}% of pixels ($off)")
    }

    @Test fun parisCacheMatchesSinglePass() = assertCacheInvisible(
        "paris", "/Paris.xml", ModeFilter.FRONT, emptyMap(),
        mapOf(
            "../partsBin/berry-shadow.png" to img("/paris-berry-shadow.png", 1.0),
            "../partsBin/berry.png" to img("/paris-berry-4x.png", 0.25),
        ),
    )

    @Test fun atlantisIVCacheMatchesSinglePass() = assertCacheInvisible(
        "atlantisiv", "/AtlantisIV.xml", ModeFilter.FRONT,
        mapOf(
            "SIUnits" to 1.0, "altitude" to 16.0,
            "horizontalPositionError" to 5.0, "locationIndicatorAngle" to Math.PI / 4,
        ),
        mapOf(
            "../Chandra/stars.png" to img("/chandra-stars-4x.png", 0.25),
            "../partsBin/logos/white.png" to img("/logo-white-4x.png", 0.25),
            "../partsBin/logos/white-blackback.png" to img("/atlantisiv-logoblackback-2x.png", 0.5),
            "polarGrey.png" to img("/atlantisiv-polargrey-4x.png", 0.25),
        ),
    )

    @Test fun tombstoneCacheMatchesSinglePass() = assertCacheInvisible(
        "tombstone", "/Tombstone.xml", ModeFilter.FRONT, emptyMap(),
        mapOf(
            "../Kyoto/reset.png" to img("/kyoto-reset.png", 1.0),
            "../partsBin/berry.png" to img("/tombstone-berry-4x.png", 0.25),
            "blender.png" to img("/tombstone-blender-2x.png", 0.5),
            "blenderk.png" to img("/tombstone-blenderk-2x.png", 0.5),
            "case19.png" to img("/tombstone-case19.png", 1.0),
            "escWheelf.png" to img("/tombstone-escwheelf.png", 1.0),
            "escapeLever.png" to img("/tombstone-esclever.png", 1.0),
            "guts18front.png" to img("/tombstone-guts.png", 1.0),
            "stem19.png" to img("/tombstone-stem19.png", 1.0),
        ),
    )

    @Test fun londonCacheMatchesSinglePass() = assertCacheInvisible(
        "london", "/London.xml", ModeFilter.FRONT, emptyMap(),
        mapOf(
            "bbfaceplus.png" to img("/london-bbfaceplus.png", 1.0),
            "band.png" to img("/london-band.png", 1.0),
            "bbhour.png" to img("/london-bbhour.png", 1.0),
            "bbminute.png" to img("/london-bbminute.png", 1.0),
        ),
    )

    @Test fun thebesCacheMatchesSinglePass() = assertCacheInvisible(
        "thebes", "/Thebes.xml", ModeFilter.FRONT,
        mapOf(
            "alarmTypeInterval" to 0.0, "alarmEnabled" to 0.0, "alarmRinging" to 0.0,
            "rings" to 0.0, "alarmIsZero" to 1.0, "alarmHour24Number" to 0.0,
            "alarmHour12ValueAngle" to 0.0, "alarmMinuteValueAngle" to 0.0,
            "alarmSecondValueAngle" to 0.0, "intervalHour24ValueAngle" to 0.0,
            "intervalMinuteValueAngle" to 0.0, "intervalSecondValueAngle" to 0.0,
        ),
        mapOf(
            "../partsBin/HD/white/face.png" to img("/thebes-face-4x.png", 0.25),
            "../partsBin/HD/white/dial076.png" to img("/thebes-dial076-4x.png", 0.25),
            "../partsBin/logos/black.png" to img("/thebes-logo-4x.png", 0.25),
            "../partsBin/berry.png" to img("/thebes-berry-4x.png", 0.25),
            "back.png" to img("/thebes-back-4x.png", 0.25),
        ),
    )
}
