package com.plasticsmoke.beryl.astro

import com.plasticsmoke.beryl.expr.Environment
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI

/** A city occupying a Terra world-time slot, in its own Olson timezone. */
data class TerraCity(val name: String, val olsonId: String, val latDeg: Double, val lonDeg: Double)

/**
 * Terra world-time multi-environment support. Each "slot" is a city in its own timezone; the
 * per-slot time functions resolve that city's local time (DST-aware via java.time) so the front
 * 24-city ring and the back's four subdials each read their own zone. Ported from chronometer-web
 * `watch/watch-env.ts` (TERRA_RING_DEFAULTS + the `…N(slot)` leaves).
 *
 * Slot numbering follows the iOS canonical XML: ring slots 5..28 hold the 24 ring cities (the web
 * port's 1..24 shifted by +4); subdial slots 1..4 drive the back's four subdials. The relative UT
 * sector (UTRingSlot − firstRingSlot = 11) is the same in both numbering schemes, so the geometry
 * formulas carry over unchanged.
 */

private const val UTC_SECTOR_NUMBER = 11.0

/** The 24 ring cities — iOS slots 5..28. */
val TERRA_RING_CITIES: List<TerraCity> = listOf(
    TerraCity("Pago Pago", "Pacific/Pago_Pago", -14.27806, -170.70250),       // slot 5
    TerraCity("Honolulu", "Pacific/Honolulu", 21.30694, -157.85834),          // 6
    TerraCity("Anchorage", "America/Juneau", 61.21806, -149.90028),           // 7
    TerraCity("Los Angeles", "America/Los_Angeles", 34.05223, -118.24368),    // 8
    TerraCity("Denver", "America/Denver", 39.73915, -104.98470),              // 9
    TerraCity("Chicago", "America/Chicago", 41.85003, -87.65005),             // 10
    TerraCity("New York", "America/New_York", 40.71427, -74.00597),           // 11
    TerraCity("Santiago", "America/Santiago", -33.42628, -70.56655),          // 12
    TerraCity("Rio de Janeiro", "America/Sao_Paulo", -22.90278, -43.20750),   // 13
    TerraCity("Grytviken", "Atlantic/South_Georgia", -54.27667, -36.51167),   // 14
    TerraCity("Dakar", "Africa/Dakar", 14.74208, -17.43978),                  // 15
    TerraCity("London", "Europe/London", 51.50842, -0.12553),                 // 16 (UT sector)
    TerraCity("Paris", "Europe/Paris", 48.85341, 2.34880),                    // 17
    TerraCity("Cairo", "Africa/Cairo", 30.05000, 31.25000),                   // 18
    TerraCity("Moscow", "Europe/Moscow", 55.75222, 37.61555),                 // 19
    TerraCity("Dubai", "Asia/Dubai", 25.25222, 55.28000),                     // 20
    TerraCity("Delhi", "Asia/Kolkata", 28.66667, 77.21666),                   // 21
    TerraCity("Dhaka", "Asia/Dhaka", 23.72305, 90.40861),                     // 22
    TerraCity("Bangkok", "Asia/Bangkok", 13.75000, 100.51667),                // 23
    TerraCity("Hong Kong", "Asia/Hong_Kong", 22.28401, 114.15007),            // 24
    TerraCity("Tokyo", "Asia/Tokyo", 35.68953, 139.69168),                    // 25
    TerraCity("Sydney", "Australia/Sydney", -33.86785, 151.20732),            // 26
    TerraCity("Nouméa", "Pacific/Noumea", -22.26667, 166.45000),              // 27
    TerraCity("Auckland", "Pacific/Auckland", -36.86666, 174.76666),          // 28
)

/** The four back-subdial cities — iOS slots 1..4 (slot 1 = the large local dial). */
val TERRA_SUBDIAL_CITIES: List<TerraCity> = listOf(
    TerraCity("Los Angeles", "America/Los_Angeles", 34.05223, -118.24368),    // slot 1
    TerraCity("New York", "America/New_York", 40.71427, -74.00597),           // slot 2
    TerraCity("London", "Europe/London", 51.50842, -0.12553),                 // slot 3
    TerraCity("Tokyo", "Asia/Tokyo", 35.68953, 139.69168),                    // slot 4
)

// ── Robinson projection (continents map city dots), ported from ECMapProjection.m / web renderer ──
private class RobinsonCoefs(val c0: Double, val c1: Double, val c2: Double, val c3: Double)
private fun robV(c: RobinsonCoefs, z: Double) = c.c0 + z * (c.c1 + z * (c.c2 + z * c.c3))
private val ROB_X = listOf(
    RobinsonCoefs(1.0, -5.67239e-12, -7.15511e-05, 3.11028e-06), RobinsonCoefs(0.9986, -0.000482241, -2.4897e-05, -1.33094e-06),
    RobinsonCoefs(0.9954, -0.000831031, -4.4861e-05, -9.86588e-07), RobinsonCoefs(0.99, -0.00135363, -5.96598e-05, 3.67749e-06),
    RobinsonCoefs(0.9822, -0.00167442, -4.4975e-06, -5.72394e-06), RobinsonCoefs(0.973, -0.00214869, -9.03565e-05, 1.88767e-08),
    RobinsonCoefs(0.96, -0.00305084, -9.00732e-05, 1.64869e-06), RobinsonCoefs(0.9427, -0.00382792, -6.53428e-05, -2.61493e-06),
    RobinsonCoefs(0.9216, -0.00467747, -0.000104566, 4.8122e-06), RobinsonCoefs(0.8962, -0.00536222, -3.23834e-05, -5.43445e-06),
    RobinsonCoefs(0.8679, -0.00609364, -0.0001139, 3.32521e-06), RobinsonCoefs(0.835, -0.00698325, -6.40219e-05, 9.34582e-07),
    RobinsonCoefs(0.7986, -0.00755337, -5.00038e-05, 9.35532e-07), RobinsonCoefs(0.7597, -0.00798325, -3.59716e-05, -2.27604e-06),
    RobinsonCoefs(0.7186, -0.00851366, -7.0112e-05, -8.63072e-06), RobinsonCoefs(0.6732, -0.00986209, -0.000199572, 1.91978e-05),
    RobinsonCoefs(0.6213, -0.010418, 8.83948e-05, 6.24031e-06), RobinsonCoefs(0.5722, -0.00906601, 0.000181999, 6.24033e-06),
    RobinsonCoefs(0.5322, 0.0, 0.0, 0.0),
)
private val ROB_Y = listOf(
    RobinsonCoefs(0.0, 0.0124, 3.72529e-10, 1.15484e-09), RobinsonCoefs(0.062, 0.0124001, 1.76951e-08, -5.92321e-09),
    RobinsonCoefs(0.124, 0.0123998, -7.09668e-08, 2.25753e-08), RobinsonCoefs(0.186, 0.0124008, 2.66917e-07, -8.44523e-08),
    RobinsonCoefs(0.248, 0.0123971, -9.99682e-07, 3.15569e-07), RobinsonCoefs(0.31, 0.0124108, 3.73349e-06, -1.1779e-06),
    RobinsonCoefs(0.372, 0.0123598, -1.3935e-05, 4.39588e-06), RobinsonCoefs(0.434, 0.0125501, 5.20034e-05, -1.00051e-05),
    RobinsonCoefs(0.4968, 0.0123198, -9.80735e-05, 9.22397e-06), RobinsonCoefs(0.5571, 0.0120308, 4.02857e-05, -5.2901e-06),
    RobinsonCoefs(0.6176, 0.0120369, -3.90662e-05, 7.36117e-07), RobinsonCoefs(0.6769, 0.0117015, -2.80246e-05, -8.54283e-07),
    RobinsonCoefs(0.7346, 0.0113572, -4.08389e-05, -5.18524e-07), RobinsonCoefs(0.7903, 0.0109099, -4.86169e-05, -1.0718e-06),
    RobinsonCoefs(0.8435, 0.0103433, -6.46934e-05, 5.36384e-09), RobinsonCoefs(0.8936, 0.00969679, -6.46129e-05, -8.54894e-06),
    RobinsonCoefs(0.9394, 0.00840949, -0.000192847, -4.21023e-06), RobinsonCoefs(0.9761, 0.00616525, -0.000256001, -4.21021e-06),
    RobinsonCoefs(1.0, 0.0, 0.0, 0.0),
)
private const val ROB_FXC = 0.8487; private const val ROB_FYC = 1.3523
private const val ROB_C1 = 11.45915590261646417544; private const val ROB_RC1 = 0.08726646259971647884
private const val ROB_NODES = 18; private const val ROB_RFUDGE = 1.17

private fun forwardRobinson(latDeg: Double, lngDeg: Double): Pair<Double, Double> {
    val latRad = latDeg * PI / 180
    val dphi0 = kotlin.math.abs(latRad)
    var i = (dphi0 * ROB_C1).toInt()
    if (i >= ROB_NODES) i = ROB_NODES - 1
    val dphi = (180 / PI) * (dphi0 - ROB_RC1 * i)
    val x = ROB_RFUDGE * robV(ROB_X[i], dphi) * ROB_FXC * lngDeg
    var y = ROB_RFUDGE * robV(ROB_Y[i], dphi) * ROB_FYC * (180 / PI)
    if (latDeg < 0) y = -y
    return x to y
}

/**
 * Robinson-projected (x, y) face-space positions of the 24 ring cities on the continents map (image
 * 180×91.25 face units, centered). Drawn as dots by specialDotsMap.
 */
fun terraCityDotPositions(): List<Pair<Double, Double>> {
    val xScale = 180.0 / 360.0; val yScale = 91.25 / 180.0
    return TERRA_RING_CITIES.map { city ->
        val (x, y) = forwardRobinson(city.latDeg, city.lonDeg)
        (x * xScale) to (-y * yScale)
    }
}

/** City for an iOS Terra slot, or null if out of range. */
fun terraCityForSlot(slot: Int): TerraCity? = when (slot) {
    in 1..4 -> TERRA_SUBDIAL_CITIES[slot - 1]
    in 5..28 -> TERRA_RING_CITIES[slot - 5]
    else -> null
}

/** Local time components in [city]'s zone at the given instant (weekday Sun=0..Sat=6). */
private class SlotTime(val h24: Int, val min: Int, val secFrac: Double, val day: Int, val month0: Int, val weekday: Int)

/**
 * Register Terra's world-time `…N(slot)` and ring-geometry functions into [env]. Call after
 * registerWatchFunctions (which provides the base single-environment time/astronomy leaves).
 */
fun registerTerraFunctions(env: Environment, now: TimeSource) {
    val f = env.functions

    fun slotTime(city: TerraCity): SlotTime {
        val z = Instant.ofEpochMilli(now.nowMillis()).atZone(ZoneId.of(city.olsonId))
        val secFrac = z.second + z.nano / 1_000_000_000.0
        return SlotTime(z.hour, z.minute, secFrac, z.dayOfMonth, z.monthValue - 1, z.dayOfWeek.value % 7)
    }
    fun tzOffsetSec(city: TerraCity): Double =
        ZoneId.of(city.olsonId).rules.getOffset(Instant.ofEpochMilli(now.nowMillis())).totalSeconds.toDouble()

    // --- ring geometry ---
    f["sectorAngle"] = { a -> (a[0] - a[1]) * PI / 12 }
    f["UTCSectorOffset"] = { UTC_SECTOR_NUMBER - 0.5 }
    f["cityIndicatorOffset"] = { 0.0 }
    f["city24HrDialOffset"] = { a ->
        val top = a[0].toInt(); val first = a[1].toInt()
        val city = terraCityForSlot(top)
        if (city == null) 0.0
        else tzOffsetSec(city) * PI / (12 * 3600) + (first - top + UTC_SECTOR_NUMBER - 0.5) * PI / 12
    }
    f["tzOffsetAngleN"] = { a ->
        val city = terraCityForSlot(a[0].toInt())
        if (city == null) 0.0 else tzOffsetSec(city) * PI / (12 * 3600)
    }

    // DST channel range (hours): the city's standard..DST UTC offsets. Equal lo/hi ⇒ no DST.
    fun janJulOffsetHours(city: TerraCity): Pair<Double, Double> {
        val z = ZoneId.of(city.olsonId)
        val nowZ = Instant.ofEpochMilli(now.nowMillis()).atZone(z)
        val janOff = z.rules.getOffset(nowZ.withDayOfYear(1).toInstant()).totalSeconds / 3600.0
        val julOff = z.rules.getOffset(nowZ.withMonth(7).withDayOfMonth(1).toInstant()).totalSeconds / 3600.0
        return minOf(janOff, julOff) to maxOf(janOff, julOff)
    }
    f["dstLowHoursN"] = { a -> terraCityForSlot(a[0].toInt())?.let { janJulOffsetHours(it).first } ?: Double.NaN }
    f["dstHighHoursN"] = { a -> terraCityForSlot(a[0].toInt())?.let { janJulOffsetHours(it).second } ?: Double.NaN }

    // moreDay/lessDay: is the slot city's calendar date ahead of / behind the top city's?
    fun cmpDay(slot: Int, top: Int, more: Boolean): Double {
        val sc = terraCityForSlot(slot) ?: return 0.0
        val tc = terraCityForSlot(top) ?: return 0.0
        val s = slotTime(sc); val t = slotTime(tc)
        if (s.month0 != t.month0) {
            // December↔January wrap.
            if (s.month0 == 0 && t.month0 == 11) return if (more) 1.0 else 0.0
            if (s.month0 == 11 && t.month0 == 0) return if (more) 0.0 else 1.0
            return if ((s.month0 > t.month0) == more) 1.0 else 0.0
        }
        return if ((s.day > t.day) == more && s.day != t.day) 1.0 else 0.0
    }
    f["moreDay"] = { a -> cmpDay(a[0].toInt(), a[1].toInt(), true) }
    f["lessDay"] = { a -> cmpDay(a[0].toInt(), a[1].toInt(), false) }

    // --- per-slot time leaves ---
    fun withCity(a: DoubleArray, fn: (SlotTime) -> Double): Double {
        val city = terraCityForSlot(a[0].toInt()) ?: return 0.0
        return fn(slotTime(city))
    }
    f["hour12ValueAngleN"] = { a -> withCity(a) { t -> ((t.h24 % 12) + (t.min + t.secFrac / 60) / 60) * 2 * PI / 12 } }
    f["minuteValueAngleN"] = { a -> withCity(a) { t -> (t.min + t.secFrac / 60) * 2 * PI / 60 } }
    f["secondValueAngleN"] = { a -> withCity(a) { t -> t.secFrac * 2 * PI / 60 } }
    f["hour24ValueAngleN"] = { a -> withCity(a) { t -> (t.h24 + (t.min + t.secFrac / 60) / 60) * 2 * PI / 24 } }
    f["hour24NumberN"] = { a -> withCity(a) { t -> t.h24.toDouble() } }
    f["dayNumberN"] = { a -> withCity(a) { t -> (t.day - 1).toDouble() } }
    f["monthNumberAngleN"] = { a -> withCity(a) { t -> t.month0 * 2 * PI / 12 } }
    f["weekdayNumberN"] = { a -> withCity(a) { t -> t.weekday.toDouble() } }
    f["weekdayNumberAngleN"] = { a -> withCity(a) { t -> t.weekday * 2 * PI / 7 } }

    // Day/night ring leaf for a slot's city (back subdial sun rings). Mirrors dayNightLeafAngle but
    // uses the slot city's lat/lon and its own tz offset.
    f["dayNightLeafAngleForSlot"] = { a ->
        val city = terraCityForSlot(a[3].toInt())
        if (city == null) 0.0 else {
            val d = PI / 180
            computeDayNightLeafAngle(
                a[0].toInt(), a[1].toInt(), a[2].toInt(),
                dateIntervalFromEpochMillis(now.nowMillis()), city.latDeg * d, city.lonDeg * d, tzOffsetSec(city),
            )
        }
    }

    // Persistent-value + per-slot calendar mutators are interactive (button actions); stub them so
    // static renders don't fault. The :app layer re-registers the real ones over these
    // (ActionHooks for the persistence pair, registerTerraTimeFunctions for the advances).
    f["fetchPersistentValue"] = { 0.0 }
    f["storePersistentValue"] = { 0.0 }
    f["advanceDayN"] = { 0.0 }
    f["advanceMonthN"] = { 0.0 }
}

/**
 * The real per-slot calendar advances, overriding both the stubs above and the device-zone
 * versions from [registerTimeFunctions] — call AFTER both. iOS advanceDayN/advanceMonthN
 * (ECVirtualMachineOps.m:4273, ECWatchTime.m:1488-1502) step the watch's SHARED main time by
 * one calendar day/month computed in the slot city's timezone: Terra's date windows show the
 * top city's calendar, so 'adv day' must land on the next midnight-boundary of THAT city
 * (DST-aware in its zone), not the device's.
 */
fun registerTerraTimeFunctions(env: Environment, time: WatchTimeState) {
    val f = env.functions
    fun zoneOf(a: DoubleArray) = terraCityForSlot(a[0].toInt())?.let { ZoneId.of(it.olsonId) }
    fun dir() = if (time.runningBackward) -1 else 1
    f["advanceDayN"] = { a ->
        zoneOf(a)?.let { time.advanceDays(dir(), it) }
        0.0
    }
    f["advanceMonthN"] = { a ->
        zoneOf(a)?.let { time.advanceMonths(dir(), it) }
        0.0
    }
}
