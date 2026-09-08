package com.plasticsmoke.beryl.astro

import java.io.DataInputStream

/**
 * Willmann-Bell planet series tables (Mercury, Venus, Mars inner; Jupiter, Saturn outer; Sun).
 * Ported from iOS esastro `ESWBPlanetsTable.h` (cross-checked value-for-value against the web's
 * planet-tables.ts — zero mismatches). Loaded once from the `/planet-tables.bin` resource
 * (big-endian float64, arrays concatenated in the order below) to avoid a 60k-element Kotlin literal.
 *
 * Inner-planet rows are [vi, ai, bi]; Sun rows are [li, ali, bli, ri]; outer-planet data rows are
 * [aLong(7), aLat(7), aRad(7)]; outer JD-range rows are [startJD, endJD].
 */
object PlanetTables {
    // (name, numEntries, doublesPerEntry) — MUST match tools/convert_planet_tables.py ARRAYS order.
    private val layout = listOf(
        Triple("sun", 50, 4),
        Triple("mercuryLong", 25, 3), Triple("mercuryLat", 18, 3), Triple("mercuryRad", 14, 3),
        Triple("venusLong", 20, 3), Triple("venusLat", 6, 3), Triple("venusRad", 5, 3),
        Triple("marsLong", 60, 3), Triple("marsLat", 7, 3), Triple("marsRad", 29, 3),
        Triple("jupiterData", 1360, 21), Triple("jupiterJD", 1360, 2),
        Triple("saturnData", 1360, 21), Triple("saturnJD", 1360, 2),
    )

    private val tables: Map<String, Array<DoubleArray>> = load()

    private fun load(): Map<String, Array<DoubleArray>> {
        val stream = PlanetTables::class.java.getResourceAsStream("/planet-tables.bin")
            ?: error("planet-tables.bin resource not found")
        val result = LinkedHashMap<String, Array<DoubleArray>>()
        DataInputStream(stream.buffered()).use { din ->
            for ((name, n, per) in layout) {
                result[name] = Array(n) { DoubleArray(per) { din.readDouble() } }
            }
        }
        return result
    }

    val sun get() = tables.getValue("sun")
    val mercuryLong get() = tables.getValue("mercuryLong")
    val mercuryLat get() = tables.getValue("mercuryLat")
    val mercuryRad get() = tables.getValue("mercuryRad")
    val venusLong get() = tables.getValue("venusLong")
    val venusLat get() = tables.getValue("venusLat")
    val venusRad get() = tables.getValue("venusRad")
    val marsLong get() = tables.getValue("marsLong")
    val marsLat get() = tables.getValue("marsLat")
    val marsRad get() = tables.getValue("marsRad")
    val jupiterData get() = tables.getValue("jupiterData")
    val jupiterJD get() = tables.getValue("jupiterJD")
    val saturnData get() = tables.getValue("saturnData")
    val saturnJD get() = tables.getValue("saturnJD")
}
