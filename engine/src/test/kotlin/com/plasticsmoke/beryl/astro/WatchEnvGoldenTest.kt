package com.plasticsmoke.beryl.astro

import com.plasticsmoke.beryl.expr.Environment
import com.plasticsmoke.beryl.expr.createDefaultEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Validates the wired watch-environment astronomy functions (rise/set day-selection + hour-angle,
 * season, moon age, calendar helpers) against golden values captured by calling the chronometer-web
 * `registerAstroFunctions` directly. This checks the wiring end-to-end, not just the math core.
 */
class WatchEnvGoldenTest {

    private fun records(): List<Map<String, String>> {
        val text = WatchEnvGoldenTest::class.java.getResourceAsStream("/astro-watchenv-golden.json")!!
            .bufferedReader().use { it.readText() }
        return Regex("""\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL).findAll(text).map { m ->
            Regex(""""([A-Za-z0-9]+)"\s*:\s*("?[^",}]+"?)""").findAll(m.groupValues[1])
                .associate { it.groupValues[1] to it.groupValues[2].trim('"') }
        }.toList()
    }

    private fun call(env: Environment, name: String) = env.functions[name]!!(DoubleArray(0))

    @Test fun matchesGoldenWatchEnv() {
        val recs = records()
        assertEquals(4, recs.size)
        // Per-field absolute tolerances: discrete values exact; rise/set angles depend on times
        // validated to 0.05 s (≈7e-6 rad); the solstice depends on the sun-longitude function.
        val tol = mapOf(
            "season" to 0.0, "GregorianEra" to 0.0, "yearNumberCEMonotonic" to 0.0,
            "monthLen" to 0.0, "DSTNumber" to 0.0, "tzOffset" to 0.0,
            "sunriseForDayValid" to 0.0, "sunsetForDayValid" to 0.0,
            "moonriseForDayValid" to 0.0, "moonsetForDayValid" to 0.0,
            "moonriseForDayHour24Number" to 0.0,
            "moonAgeAngle" to 1e-9, "realMoonAgeAngle" to 1e-7, "leapYearIndicatorAngle" to 1e-12,
        ).withDefault { 1e-4 }

        for (r in recs) {
            val env = createDefaultEnvironment()
            registerWatchFunctions(env, 0.0, r["lat"]!!.toDouble(), r["lon"]!!.toDouble()) { (r["dateInterval"]!!.toDouble() * 1000 + 978307200000.0).toLong() }
            for ((name, expected) in r) {
                if (name == "iso" || name == "dateInterval" || name == "lat" || name == "lon") continue
                assertEquals(expected.toDouble(), call(env, name), tol.getValue(name), "$name @ ${r["iso"]}")
            }
        }
    }
}
