package com.plasticsmoke.beryl.astro

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Validates the rise/set port (sun + moon, San Francisco) against golden values captured from the
 * chronometer-web TypeScript rise/set algorithm. Times are Apple-epoch seconds; a 0.05 s tolerance
 * is well within the solver's 0.1 s convergence threshold.
 */
class RiseSetAstroTest {

    private data class Golden(
        val iso: String, val dateInterval: Double, val lat: Double, val lon: Double,
        val sunrise: Double, val sunset: Double, val suntransit: Double,
        val moonrise: Double, val moonset: Double,
    )

    private fun loadGolden(): List<Golden> {
        val text = RiseSetAstroTest::class.java.getResourceAsStream("/astro-riseset-golden.json")!!
            .bufferedReader().use { it.readText() }
        fun field(b: String, k: String) = Regex(""""$k"\s*:\s*("?[^",}]+"?)""").find(b)!!.groupValues[1].trim('"')
        return Regex("""\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL).findAll(text).map { m ->
            val b = m.groupValues[1]
            Golden(
                field(b, "iso"), field(b, "dateInterval").toDouble(), field(b, "lat").toDouble(), field(b, "lon").toDouble(),
                field(b, "sunrise").toDouble(), field(b, "sunset").toDouble(), field(b, "suntransit").toDouble(),
                field(b, "moonrise").toDouble(), field(b, "moonset").toDouble(),
            )
        }.toList()
    }

    @Test fun matchesGoldenRiseSet() {
        val golden = loadGolden()
        assertEquals(4, golden.size)
        val tol = 0.05  // seconds
        for (g in golden) {
            assertEquals(g.sunrise, sunriseForDay(g.dateInterval, g.lat, g.lon), tol, "sunrise @ ${g.iso}")
            assertEquals(g.sunset, sunsetForDay(g.dateInterval, g.lat, g.lon), tol, "sunset @ ${g.iso}")
            assertEquals(g.suntransit, suntransitForDay(g.dateInterval, g.lat, g.lon), tol, "suntransit @ ${g.iso}")
            assertEquals(g.moonrise, moonriseForDay(g.dateInterval, g.lat, g.lon), tol, "moonrise @ ${g.iso}")
            assertEquals(g.moonset, moonsetForDay(g.dateInterval, g.lat, g.lon), tol, "moonset @ ${g.iso}")
        }
    }
}
