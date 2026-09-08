package com.plasticsmoke.beryl.astro

import com.plasticsmoke.beryl.expr.Environment
import com.plasticsmoke.beryl.expr.createDefaultEnvironment
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Validates the Geneva back-side astronomy (sidereal time, equation of time, equinox-of-date,
 * lunar ascending node, year-366 indicator, sun/moon RA, altitude/azimuth) against golden values
 * captured from chronometer-web. The day/night-ring LST leaf is NOT validated here — the web port
 * stripped the back and that path returns NaN in the web (the transit echoes the rise/set time),
 * so it's verified by eye against the iPhone render instead.
 */
class BackAstroGoldenTest {

    private fun records(): List<Map<String, String>> {
        val text = BackAstroGoldenTest::class.java.getResourceAsStream("/astro-back-golden.json")!!
            .bufferedReader().use { it.readText() }
        return Regex("""\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL).findAll(text).map { m ->
            Regex(""""([A-Za-z0-9]+)"\s*:\s*("?[^",}]+"?)""").findAll(m.groupValues[1])
                .associate { it.groupValues[1] to it.groupValues[2].trim('"') }
        }.toList()
    }

    private fun call(env: Environment, name: String, vararg args: Double) = env.functions[name]!!(args)

    @Test fun matchesGoldenBackAstro() {
        val recs = records()
        assertEquals(5, recs.size)

        // Env scalar leaves to assert. lstValue is in seconds; the rest are radians.
        val scalarTol = mapOf(
            "lstValue" to 0.05, "eclipseKind" to 0.0, "eclipseSeparation" to 1e-5,
            "closestNewMoonDayNumber" to 0.0, "closestFirstQuarterDayNumber" to 0.0,
            "closestFullMoonDayNumber" to 0.0, "closestThirdQuarterDayNumber" to 0.0,
        ).withDefault { 1e-6 }
        val scalars = listOf(
            "lstValue", "EOTAngle", "J2000RAofVernalEquinoxOfDateAngle",
            "sunRA", "moonRA", "lunarAscendingNodeRA", "year366IndicatorAngle",
            "longitude", "tzOffsetAngle", "eclipseSeparation", "eclipseKind",
            "closestNewMoonDayNumber", "closestFirstQuarterDayNumber",
            "closestFullMoonDayNumber", "closestThirdQuarterDayNumber",
        )

        for (r in recs) {
            val lat = r["lat"]!!.toDouble()
            val lon = r["lon"]!!.toDouble()
            val millis = Instant.parse(r["iso"]!!).toEpochMilli()
            val di = dateIntervalFromEpochMillis(millis)
            val tag = r["label"]

            val env = createDefaultEnvironment()
            registerWatchFunctions(env, 0.0, lat, lon) { millis }

            for (name in scalars) {
                assertEquals(r[name]!!.toDouble(), call(env, name), scalarTol.getValue(name), "$name @ $tag")
            }
            for (q in 0..3) {
                assertEquals(r["quarter$q"]!!.toDouble(), call(env, "closestSunEclipticLongitudeQuarter366IndicatorAngle", q.toDouble()), 1e-5, "quarter$q @ $tag")
            }

            // Direct altitude/azimuth + LST (validates planetAltAz / localSiderealTime).
            assertEquals(r["sunAlt"]!!.toDouble(), sunAltitude(di, lat, lon), 1e-9, "sunAlt @ $tag")
            assertEquals(r["moonAlt"]!!.toDouble(), moonAltitude(di, lat, lon), 1e-9, "moonAlt @ $tag")
            assertEquals(r["sunAz"]!!.toDouble(), sunAzimuth(di, lat, lon), 1e-9, "sunAz @ $tag")
            assertEquals(r["moonAz"]!!.toDouble(), moonAzimuth(di, lat, lon), 1e-9, "moonAz @ $tag")
            assertEquals(r["lstRad"]!!.toDouble(), localSiderealTime(di, lon), 1e-9, "lstRad @ $tag")
            assertEquals(r["moonRelAngle"]!!.toDouble(), moonRelativeAngle(di, lat, lon), 1e-9, "moonRelAngle @ $tag")
            assertEquals(r["sunSkyAngle"]!!.toDouble(), sunSkyOrientationAngle(di, lat, lon), 1e-9, "sunSkyAngle @ $tag")
        }
    }
}
