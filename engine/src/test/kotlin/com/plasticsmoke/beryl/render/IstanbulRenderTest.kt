package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.astro.registerWatchFunctions
import com.plasticsmoke.beryl.expr.Environment
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
 * Renders Istanbul's front — an alarm watch (canonical iOS, no web equivalent): the main 12-hour
 * dial plus an alarm sub-dial (upper-left) and an interval/"Remain" sub-dial (upper-right), with a
 * note-state indicator and AM/PM windows. The alarm + interval subsystems aren't implemented in the
 * engine or the web (iOS-only), so they're stubbed to the iPhone's no-alarm default: alarm 12:00 AM,
 * disabled, zero remaining.
 */
class IstanbulRenderTest {

    private fun resourceText(name: String) =
        IstanbulRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        IstanbulRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    /** Stub the iOS-only alarm/interval/state leaves to the no-alarm default (alarm 12:00 AM, off). */
    private fun stubAlarm(env: Environment) {
        val f = env.functions
        f["alarmTypeTarget"] = { 0.0 }
        f["alarmEnabled"] = { 0.0 }
        f["alarmRinging"] = { 0.0 }
        f["rings"] = { 0.0 }
        f["alarmManualSet"] = { 0.0 }
        f["alarmHour12ValueAngle"] = { 0.0 }   // 12 o'clock
        f["alarmMinuteNumberAngle"] = { 0.0 }  // :00
        f["alarmHour24Number"] = { 0.0 }       // midnight → AM
        f["intervalHour24ValueAngle"] = { 0.0 }
        f["intervalMinuteValueAngle"] = { 0.0 }
    }

    @Test fun rendersIstanbulToPng() {
        val xml = resourceText("/Istanbul.xml")
        val watch = parseWatchXml(xml, ModeFilter.FRONT)

        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()  // 10:08:36 PDT
        }
        stubAlarm(env)
        applyInits(watch, env)

        val images = mapOf(
            "../partsBin/HD/rose/face.png" to LoadedImage(AwtImage(resourceImage("/istanbul-face-4x.png")), 0.25),
            "../partsBin/HD/rose/dial118.png" to LoadedImage(AwtImage(resourceImage("/istanbul-dial118-4x.png")), 0.25),
            "../partsBin/HD/rose/dial076.png" to LoadedImage(AwtImage(resourceImage("/istanbul-dial076-4x.png")), 0.25),
            "../partsBin/ampm2.png" to LoadedImage(AwtImage(resourceImage("/istanbul-ampm2-4x.png")), 0.25),
            "../partsBin/logos/black.png" to LoadedImage(AwtImage(resourceImage("/istanbul-logo-4x.png")), 0.25),
        )

        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "istanbul")

        assertTrue(watch.parts.isNotEmpty(), "Istanbul parts should be present")
    }
}
