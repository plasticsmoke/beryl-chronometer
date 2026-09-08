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
 * Renders Istanbul's NIGHT mode — black dial with teal (nfgColor) 12-hour ticks and teal arrow
 * hour/minute hands (no sub-dials in night). Alarm leaves stubbed to the no-alarm default.
 */
class IstanbulNightRenderTest {

    private fun resourceImage(name: String) =
        IstanbulNightRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    private fun stubAlarm(env: Environment) {
        val f = env.functions
        for (n in listOf("alarmTypeTarget", "alarmEnabled", "alarmRinging", "rings", "alarmManualSet",
                "alarmHour12ValueAngle", "alarmMinuteNumberAngle", "alarmHour24Number",
                "intervalHour24ValueAngle", "intervalMinuteValueAngle")) f[n] = { 0.0 }
    }

    @Test fun rendersIstanbulNightToPng() {
        val xml = IstanbulNightRenderTest::class.java.getResourceAsStream("/Istanbul.xml")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val watch = parseWatchXml(xml, ModeFilter.NIGHT)

        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()
        }
        stubAlarm(env)
        applyInits(watch, env)

        PhoneFrame.render(watch, env, PhoneFrame.loadChrome(xml), "istanbul-night")

        assertTrue(watch.parts.isNotEmpty(), "Istanbul night parts should be present")
    }
}
