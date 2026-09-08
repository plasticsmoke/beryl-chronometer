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
 * Renders Olympia (canonical iOS) — the CHRONOGRAPH with RATTRAPANTE (split-seconds): a tachymeter
 * bezel, four subdials (running seconds, sw subsecond, sw minutes, sw hours), date/weekday windows,
 * stopwatch + split (lap) hands, and a chronograph back (Split + Stop subdials). The stopwatch
 * subsystem is iOS-only (like Istanbul/Thebes), so it's stubbed to the reset/zero default — all
 * stopwatch hands at 12. The running time (center hr/min + left-subdial seconds) reads live.
 */
class OlympiaRenderTest {

    private fun resourceText(name: String) =
        OlympiaRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        OlympiaRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    private fun img(name: String, scale: Double) = LoadedImage(AwtImage(resourceImage(name)), scale)

    /** Stub the iOS-only stopwatch/rattrapante leaves to the reset state (all hands at zero). */
    private fun stubStopwatch(env: Environment) {
        val f = env.functions
        for (fn in listOf(
            "stopwatchSecondValue", "stopwatchMinuteValue", "stopwatchHour24Value", "stopwatchDayValue",
            "stopwatchLapSecondValue", "stopwatchLapMinuteValue", "stopwatchLapHour24Value", "stopwatchLapDayValue",
            "stopwatchRattrapanteValid",
        )) f[fn] = { 0.0 }
    }

    private fun render(mode: ModeFilter, bg: java.awt.Color, suffix: String, images: Map<String, LoadedImage>) {
        val xml = resourceText("/Olympia.xml")
        val watch = parseWatchXml(xml, mode)
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()  // 10:08:36 PDT, Fri Jun 12 2026
        }
        stubStopwatch(env)
        applyInits(watch, env)
        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "olympia$suffix")
        assertTrue(watch.parts.isNotEmpty(), "Olympia$suffix parts should be present")
    }

    private val logo = mapOf("../partsBin/logos/white.png" to img("/logo-white-4x.png", 0.25))

    @Test fun rendersOlympiaFront() = render(ModeFilter.FRONT, java.awt.Color.WHITE, "", logo)
    @Test fun rendersOlympiaNight() = render(ModeFilter.NIGHT, java.awt.Color.BLACK, "-night", emptyMap())
    @Test fun rendersOlympiaBack() = render(ModeFilter.BACK, java.awt.Color.WHITE, "-back", logo)
}
