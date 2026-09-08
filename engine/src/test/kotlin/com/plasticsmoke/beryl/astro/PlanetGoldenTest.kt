package com.plasticsmoke.beryl.astro

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Validates the WB planet positions (Mercury, Venus, Mars, Jupiter, Saturn) against golden values
 * dumped from chronometer-web (which derives from the same iOS ESWillmannBell.cpp). Checks both the
 * raw apparent RA/decl/distance and the full date→alt/az chain.
 */
class PlanetGoldenTest {

    private fun records(): List<Map<String, String>> {
        val text = PlanetGoldenTest::class.java.getResourceAsStream("/planet-golden.json")!!
            .bufferedReader().use { it.readText() }
        return Regex("""\{(.*?)\}""").findAll(text).map { m ->
            Regex(""""([A-Za-z]+)"\s*:\s*("?[^",}]+"?)""").findAll(m.groupValues[1])
                .associate { it.groupValues[1] to it.groupValues[2].trim('"') }
        }.toList()
    }

    @Test fun matchesGoldenPlanetPositions() {
        val recs = records()
        assertEquals(30, recs.size)
        for (r in recs) {
            val planet = r["planet"]!!.toInt()
            val iso = r["iso"]!!
            val lat = r["lat"]!!.toDouble(); val lon = r["lon"]!!.toDouble()
            val di = dateIntervalFromEpochMillis(Instant.parse(iso).toEpochMilli())
            val jc = julianCenturiesSince2000EpochForDateInterval(di).julianCenturiesSince2000Epoch
            val u = jc / 100
            val tag = "planet=$planet @ $iso"

            // date → U conversion matches the web
            assertEquals(r["U"]!!.toDouble(), u, 1e-12, "U @ $tag")

            // raw apparent position
            val p = wbPlanetApparentPosition(planet, u)
            assertEquals(r["ra"]!!.toDouble(), p.rightAscension, 1e-9, "ra @ $tag")
            assertEquals(r["decl"]!!.toDouble(), p.declination, 1e-9, "decl @ $tag")
            assertEquals(r["dist"]!!.toDouble(), p.geocentricDistanceAU, 1e-9, "dist @ $tag")

            // full chain: date → alt/az
            assertEquals(r["alt"]!!.toDouble(), planetAltAz(planet, di, lat, lon, false, true), 1e-9, "alt @ $tag")
            assertEquals(r["az"]!!.toDouble(), planetAltAz(planet, di, lat, lon, false, false), 1e-9, "az @ $tag")
        }
    }
}
