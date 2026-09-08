package com.plasticsmoke.beryl.astro

import kotlin.math.PI

/**
 * Sidereal time, ported from chronometer-web `src/astronomy/es-sidereal.ts` (P03 model).
 * GST↔UT conversions that need the cache pool (rise/set) are deferred. Cache-free.
 */

private const val TWO_PI = PI * 2

private fun convertUTToGSTP03x(
    centuriesSinceEpochTDT: Double,
    deltaTSeconds: Double,
    utSinceMidnightRadians: Double,
): Double {
    val t = centuriesSinceEpochTDT
    val tu = t - deltaTSeconds / (24.0 * 3600 * 36525)  // Double: avoids Int overflow (>2^31)
    val t2 = t * t; val t3 = t2 * t; val t4 = t2 * t2; val t5 = t3 * t2

    var gmst = 24110.5493771 +
        8640184.79447825 * tu +
        307.4771013 * (t - tu) +
        0.092772110 * t2 -
        0.0000002926 * t3 -
        0.00000199708 * t4 -
        0.000000002454 * t5

    gmst *= PI / (12.0 * 3600)        // seconds of time → radians
    gmst += utSinceMidnightRadians
    gmst = esFmod(gmst, TWO_PI)
    if (gmst < 0) gmst += TWO_PI
    return gmst
}

/** Greenwich Sidereal Time (radians) for a UT date interval, using the P03 model. */
fun convertUTToGSTP03(dateInterval: Double): Double {
    val jcr = julianCenturiesSince2000EpochForDateInterval(dateInterval)
    val priorMid = priorUTMidnightForDateInterval(dateInterval)
    val utRadians = (dateInterval - priorMid) * PI / (12 * 3600)
    return convertUTToGSTP03x(jcr.julianCenturiesSince2000Epoch, jcr.deltaT, utRadians)
}

/** GST − UT offset (radians) for a date. */
fun gstDifferenceForDate(dateInterval: Double): Double {
    val jcr = julianCenturiesSince2000EpochForDateInterval(dateInterval)
    val priorMid = priorUTMidnightForDateInterval(dateInterval)
    val utRadians = (dateInterval - priorMid) * PI / (12 * 3600)
    val gst = convertUTToGSTP03x(jcr.julianCenturiesSince2000Epoch, jcr.deltaT, utRadians)
    return gst - utRadians
}

/** LST → GST (returns gst plus a day offset of -1, 0, or 1). */
fun convertLSTtoGST(lst: Double, observerLongitude: Double): Pair<Double, Int> {
    var gst = lst - observerLongitude
    var dayOffset = 0
    if (gst < 0) { gst += TWO_PI; dayOffset = -1 } else if (gst > TWO_PI) { gst -= TWO_PI; dayOffset = 1 }
    return gst to dayOffset
}

/** GST → LST. */
fun convertGSTtoLST(gst: Double, observerLongitude: Double): Double {
    var lst = gst + observerLongitude
    if (lst < 0) lst += TWO_PI else if (lst > TWO_PI) lst -= TWO_PI
    return lst
}

/** GST → UT (radians since midnight); ut2 = -1 when there's only one UT for this GST. */
private data class UtPair(val ut: Double, val ut2: Double)

private fun convertGSTtoUT(gst: Double, priorUTMidnight: Double): UtPair {
    val jcr = julianCenturiesSince2000EpochForDateInterval(priorUTMidnight)
    val t0 = convertUTToGSTP03x(jcr.julianCenturiesSince2000Epoch, jcr.deltaT, 0.0)
    var ut = gst - t0
    if (ut < 0) ut += TWO_PI else if (ut > TWO_PI) ut -= TWO_PI
    ut *= kECUTUnitsPerGSTUnit
    var ut2 = ut + kECUTUnitsPerGSTUnit * TWO_PI
    if (ut2 > TWO_PI) ut2 = -1.0
    return UtPair(ut, ut2)
}

/** Convert GST to the UT date interval closest to [closestToThisDate]. */
fun convertGSTtoUTclosest(gst: Double, closestToThisDate: Double): Double {
    var priorMid = priorUTMidnightForDateInterval(closestToThisDate)
    var (ut0, ut0_2) = convertGSTtoUT(gst, priorMid)
    var secsSinceMidnight = ut0 * (12 * 3600) / PI
    var utD = priorMid + secsSinceMidnight

    if (utD < closestToThisDate - 12 * 3600.0 * kECUTUnitsPerGSTUnit) {
        if (ut0_2 > 0) {
            ut0 = ut0_2
            secsSinceMidnight = ut0 * (12 * 3600) / PI
            utD = priorMid + secsSinceMidnight
        } else {
            priorMid += 24 * 3600.0
            val r = convertGSTtoUT(gst, priorMid); ut0 = r.ut
            secsSinceMidnight = ut0 * (12 * 3600) / PI
            utD = priorMid + secsSinceMidnight
        }
    } else if (utD > closestToThisDate + 12 * 3600.0 * kECUTUnitsPerGSTUnit) {
        priorMid -= 24 * 3600.0
        val r = convertGSTtoUT(gst, priorMid); ut0 = r.ut; ut0_2 = r.ut2
        if (ut0_2 > 0) ut0 = ut0_2
        secsSinceMidnight = ut0 * (12 * 3600) / PI
        utD = priorMid + secsSinceMidnight
    }
    return utD
}
