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
 * Renders Thebes (canonical iOS, no web equivalent) — the INTERVAL-TIMER / COUNTDOWN + ALARM
 * chronograph. The main 24-hour dial is driven by the interval (countdown) timer hands; two
 * sub-dials show the alarm "Ending Time" (upper-left) and the "Current Time" (upper-right), with an
 * alarm-state note indicator and AM/PM portholes. The alarm + interval-timer subsystems are
 * iOS-only (same as Istanbul), so they're stubbed to the no-alarm / zero-timer default: alarm
 * 12:00 AM disabled, timer at reset (all interval hands at 12). The Current-Time sub-dial uses the
 * real clock leaves so it reads the actual render time.
 */
class ThebesRenderTest {

    private fun resourceText(name: String) =
        ThebesRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        ThebesRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    private fun img(name: String, scale: Double) = LoadedImage(AwtImage(resourceImage(name)), scale)

    /** Stub the iOS-only alarm/interval leaves: alarm 12:00 AM disabled, countdown timer at zero. */
    private fun stubAlarmAndTimer(env: Environment) {
        val f = env.functions
        f["alarmTypeInterval"] = { 0.0 }
        f["alarmEnabled"] = { 0.0 }
        f["alarmRinging"] = { 0.0 }
        f["rings"] = { 0.0 }
        f["alarmIsZero"] = { 1.0 }
        f["alarmHour24Number"] = { 0.0 }        // midnight → AM
        f["alarmHour12ValueAngle"] = { 0.0 }    // 12
        f["alarmMinuteValueAngle"] = { 0.0 }    // :00
        f["alarmSecondValueAngle"] = { 0.0 }
        f["intervalHour24ValueAngle"] = { 0.0 } // timer reset → hands at 12
        f["intervalMinuteValueAngle"] = { 0.0 }
        f["intervalSecondValueAngle"] = { 0.0 }
    }

    private fun render(mode: ModeFilter, bg: java.awt.Color, suffix: String, images: Map<String, LoadedImage>) {
        val xml = resourceText("/Thebes.xml")
        val watch = parseWatchXml(xml, mode)
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()  // 10:08:36 PDT, Fri Jun 12 2026
        }
        stubAlarmAndTimer(env)
        applyInits(watch, env)
        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "thebes$suffix")
        assertTrue(watch.parts.isNotEmpty(), "Thebes$suffix parts should be present")
    }

    @Test fun rendersThebesFront() = render(
        ModeFilter.FRONT, java.awt.Color.WHITE, "",
        mapOf(
            "../partsBin/HD/white/face.png" to img("/thebes-face-4x.png", 0.25),
            "../partsBin/logos/black.png" to img("/thebes-logo-4x.png", 0.25),
            "../partsBin/HD/white/dial076.png" to img("/thebes-dial076-4x.png", 0.25),
        ),
    )

    @Test fun rendersThebesNight() = render(
        ModeFilter.NIGHT, java.awt.Color.BLACK, "-night", emptyMap(),
    )

    @Test fun rendersThebesBack() = render(
        ModeFilter.BACK, java.awt.Color.WHITE, "-back",
        mapOf(
            "back.png" to img("/thebes-back-4x.png", 0.25),
            "../partsBin/berry.png" to img("/thebes-berry-4x.png", 0.25),
        ),
    )
}
