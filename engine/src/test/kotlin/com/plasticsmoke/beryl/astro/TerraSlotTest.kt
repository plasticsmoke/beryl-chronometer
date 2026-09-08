package com.plasticsmoke.beryl.astro

import com.plasticsmoke.beryl.expr.createDefaultEnvironment
import java.time.Instant
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase-1 check for Terra's multi-environment world-time engine: each slot resolves its city's
 * local wall-clock time (DST-aware via java.time) at a fixed instant. Test instant is
 * 2026-06-12T17:08:36Z — Northern summer, so US/EU zones are on DST.
 */
class TerraSlotTest {

    private val nowMs = Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()

    private fun env() = createDefaultEnvironment().also { registerTerraFunctions(it) { nowMs } }

    private fun call(name: String, vararg args: Double) = env().functions[name]!!.invoke(args)

    @Test fun ringCityHoursAreDstCorrect() {
        // 17:08:36 UTC, June (DST active in US/EU):
        // London (slot 16, BST = UTC+1) → 18; New York (slot 11, EDT = UTC−4) → 13;
        // Los Angeles (slot 8, PDT = UTC−7) → 10; Tokyo (slot 25, JST = UTC+9, no DST) → 02 (next day).
        assertEquals(18.0, call("hour24NumberN", 16.0), 1e-9, "London BST")
        assertEquals(13.0, call("hour24NumberN", 11.0), 1e-9, "New York EDT")
        assertEquals(10.0, call("hour24NumberN", 8.0), 1e-9, "Los Angeles PDT")
        assertEquals(2.0, call("hour24NumberN", 25.0), 1e-9, "Tokyo JST")
    }

    @Test fun minuteAndSecondMatchAcrossZones() {
        // Minute/second hands read identically in every whole-hour zone (08:36 → fractional 8.6 min).
        val expected = (8 + 36.0 / 60) * 2 * PI / 60
        assertEquals(expected, call("minuteValueAngleN", 16.0), 1e-6, "London")
        assertEquals(call("minuteValueAngleN", 16.0), call("minuteValueAngleN", 25.0), 1e-9, "Tokyo == London")
        assertEquals(36.0 * 2 * PI / 60, call("secondValueAngleN", 8.0), 1e-6, "seconds = 36")
    }

    @Test fun tzOffsetAnglesReflectDst() {
        // London BST = +1h → +1/12·π ; Los Angeles PDT = −7h → −7/12·π.
        assertEquals(1.0 * PI / 12, call("tzOffsetAngleN", 16.0), 1e-9, "London +1")
        assertEquals(-7.0 * PI / 12, call("tzOffsetAngleN", 8.0), 1e-9, "LA −7")
    }

    @Test fun moreLessDayAcrossDateLine() {
        // Top = Los Angeles (slot 8). At 10:08 LA-time on Jun 12, Tokyo (slot 25) is already Jun 13.
        assertEquals(1.0, call("moreDay", 25.0, 8.0), "Tokyo is a day ahead of LA")
        assertEquals(0.0, call("lessDay", 25.0, 8.0), "Tokyo not behind LA")
        // Same date → neither.
        assertEquals(0.0, call("moreDay", 11.0, 8.0), "NY same date as LA")
        assertEquals(0.0, call("lessDay", 11.0, 8.0), "NY same date as LA")
    }

    @Test fun sectorAngleIsSlotDifference() {
        assertEquals(3.0 * PI / 12, call("sectorAngle", 11.0, 8.0), 1e-9)
        assertEquals(0.0, call("sectorAngle", 8.0, 8.0), 1e-9)
    }

    @Test fun subdialSlotsResolve() {
        // Slots 1..4 are the back subdials (LA, NY, London, Tokyo).
        assertEquals(10.0, call("hour24NumberN", 1.0), 1e-9, "subdial 1 = LA")
        assertEquals(2.0, call("hour24NumberN", 4.0), 1e-9, "subdial 4 = Tokyo")
        assertTrue(terraCityForSlot(1)!!.name == "Los Angeles")
        assertTrue(terraCityForSlot(28)!!.name == "Auckland")
    }
}
