package com.plasticsmoke.beryl.astro

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rise/set/transit, ported from chronometer-web `src/astronomy/es-riseset.ts`. The cache pool is
 * dropped (it only memoizes intra-iteration sub-results, so results are identical); times are
 * Apple-epoch seconds, angles radians.
 */

private const val TWO_PI = PI * 2

data class RiseSetResult(val riseSetTime: Double, val transitTime: Double)

private fun riseSetTime(
    riseNotSet: Boolean, rightAscension: Double, declination: Double,
    observerLatitude: Double, observerLongitude: Double,
    altAtRiseSet: Double, calculationDateInterval: Double,
): Double {
    val cosH = (sin(altAtRiseSet) - sin(observerLatitude) * sin(declination)) /
        (cos(observerLatitude) * cos(declination))
    if (cosH < -1.0) return ALWAYS_ABOVE_HORIZON
    if (cosH > 1.0) return ALWAYS_BELOW_HORIZON

    val h = acos(cosH)
    var lstRs = rightAscension + (if (riseNotSet) TWO_PI - h else h)
    if (lstRs > TWO_PI) lstRs -= TWO_PI
    val (gstRs, _) = convertLSTtoGST(lstRs, observerLongitude)
    return convertGSTtoUTclosest(gstRs, calculationDateInterval)
}

private fun transitTime(
    dateInterval: Double, wantHighTransit: Boolean, observerLongitude: Double, rightAscension: Double,
): Double {
    val gst = convertUTToGSTP03(dateInterval)
    var ra = rightAscension
    if (!wantHighTransit) ra += PI
    var hourAngle = esFmod(gst + observerLongitude - ra, TWO_PI)
    if (hourAngle > PI) hourAngle -= TWO_PI else if (hourAngle < -PI) hourAngle += TWO_PI
    return dateInterval - hourAngle * (12 * 3600) / PI
}

private fun linearFit(x1: Double, y1: Double, x2In: Double, y2In: Double): Double {
    val offset = x1
    val xx1 = 0.0
    val yy1 = y1 - offset
    val xx2 = x2In - offset
    val yy2 = y2In - offset
    val denom = xx2 - xx1 - yy2 + yy1
    if (denom == 0.0) return yy2 + offset
    val root = (yy1 * (xx2 - xx1) - xx1 * (yy2 - yy1)) / denom
    if (abs(root - yy2) > 12 * 3600) return yy2 + offset
    return offset + root
}

private fun extrapolateToYEqualX(x: DoubleArray, y: DoubleArray, numValues: Int): Double {
    if (numValues == 1) return y[0]
    if (numValues > 2) {
        val offset = x[numValues - 3]
        val x1 = 0.0; val y1 = y[numValues - 3] - offset
        val x2 = x[numValues - 2] - offset; val y2 = y[numValues - 2] - offset
        val x3 = x[numValues - 1] - offset; val y3 = y[numValues - 1] - offset
        if (x1 != x2 && x1 != x3 && x2 != x3) {
            val k1 = y1 / ((x1 - x2) * (x1 - x3))
            val k2 = y2 / ((x2 - x1) * (x2 - x3))
            val k3 = y3 / ((x3 - x1) * (x3 - x2))
            val c2 = k1 + k2 + k3
            val c1 = k1 * (x2 + x3) + k2 * (x1 + x3) + k3 * (x1 + x2)
            val c0 = k1 * x2 * x3 + k2 * x1 * x3 + k3 * x1 * x2
            if (c2 != 0.0) {
                val p = (-c1 - 1) / c2
                val q = c0 / c2
                val d = p * p / 4 - q
                if (d >= 0) {
                    val s = sqrt(d)
                    val root1 = -p / 2 + s
                    val root2 = -p / 2 - s
                    if (abs(root1 - y3) < abs(root2 - y3)) {
                        if (abs(root1 - y3) < 24 * 3600) return root1 + offset
                    } else {
                        if (abs(root2 - y3) < 24 * 3600) return root2 + offset
                    }
                }
            }
        }
    }
    return linearFit(x[numValues - 2], y[numValues - 2], x[numValues - 1], y[numValues - 1])
}

private fun planetRaDecl(planetNumber: Int, jc: Double, p: Int): RaDecl = when (planetNumber) {
    PLANET_SUN -> { val r = wbSunRAAndDecl(jc / 100); RaDecl(r.rightAscension, r.declination) }
    PLANET_MOON -> { val r = wbMoonRAAndDecl(jc, p); RaDecl(r.rightAscension, r.declination) }
    else -> { val pp = wbPlanetApparentPosition(planetNumber, jc / 100); RaDecl(pp.rightAscension, pp.declination) }
}

/** Iterative rise/set for Sun or Moon (full port incl. transit-retry + polar recovery). */
fun planetaryRiseSetTimeRefined(
    calculationDateInterval: Double, observerLatitude: Double, observerLongitude: Double,
    riseNotSet: Boolean, planetNumber: Int, overrideAltitudeDesired: Double,
): RiseSetResult {
    var tryDate = calculationDateInterval
    var lastValidResultDate = Double.NaN
    var lastValidTryDate = Double.NaN
    var convergedToInvalid = false
    val polarSpecial = abs(observerLatitude) > PI / 180 * 89

    var precision = if (planetNumber == PLANET_MOON) WB_LOW else WB_FULL
    if (polarSpecial) precision = WB_FULL

    val numIterations = 20
    val numPolarTries = 10
    val tryDates = DoubleArray(numIterations + numPolarTries + 1)
    val results = DoubleArray(numIterations + numPolarTries + 1)
    var fitTries = 0
    var lastDelta = 0.0
    var firstNan = Double.NaN
    var firstTransit = tryDate

    fun altitudeFor(jc: Double): Double =
        if (overrideAltitudeDesired.isNaN()) altitudeAtRiseSet(jc, planetNumber, true, precision) else overrideAltitudeDesired

    var i = 0
    while (i < numIterations) {
        // Upgrade Moon to full precision near the end and re-run (i-- is compensated by the
        // bottom i++, giving two more shots), then fall through to the body.
        if (planetNumber == PLANET_MOON && i == numIterations - 1 && precision != WB_FULL) {
            precision = WB_FULL; i--; fitTries = 0
        }

        var jcse = julianCenturiesSince2000EpochForDateInterval(tryDate).julianCenturiesSince2000Epoch
        var radecl = planetRaDecl(planetNumber, jcse, precision)
        var newDate = riseSetTime(
            riseNotSet, radecl.rightAscension, radecl.declination,
            observerLatitude, observerLongitude, altitudeFor(jcse), tryDate,
        )

        if (isNoRiseSet(newDate)) {
            if (!convergedToInvalid) {
                convergedToInvalid = true
                val wantHighTransit = newDate == ALWAYS_BELOW_HORIZON
                val transitT = planettransitTimeRefined(tryDate, observerLatitude, observerLongitude, wantHighTransit, planetNumber)
                firstTransit = transitT
                firstNan = newDate

                jcse = julianCenturiesSince2000EpochForDateInterval(transitT).julianCenturiesSince2000Epoch
                radecl = planetRaDecl(planetNumber, jcse, precision)
                newDate = riseSetTime(
                    riseNotSet, radecl.rightAscension, radecl.declination,
                    observerLatitude, observerLongitude, altitudeFor(jcse), transitT,
                )

                if (isNoRiseSet(newDate)) {
                    if (polarSpecial) {
                        val priorPolar = transitT - 13 * 3600
                        jcse = julianCenturiesSince2000EpochForDateInterval(priorPolar).julianCenturiesSince2000Epoch
                        radecl = planetRaDecl(planetNumber, jcse, precision)
                        val priorPolarEvent = riseSetTime(
                            riseNotSet, radecl.rightAscension, radecl.declination,
                            observerLatitude, observerLongitude, altitudeFor(jcse), priorPolar,
                        )
                        var binaryLow = Double.NaN; var binaryHigh = Double.NaN
                        var binaryLowEvent = Double.NaN; var binaryHighEvent = Double.NaN

                        if (isNoRiseSet(priorPolarEvent)) {
                            if (priorPolarEvent != newDate) {
                                binaryLow = priorPolar; binaryLowEvent = priorPolarEvent
                                binaryHigh = transitT; binaryHighEvent = newDate
                            }
                            val nextPolar = tryDate + 13 * 3600
                            jcse = julianCenturiesSince2000EpochForDateInterval(nextPolar).julianCenturiesSince2000Epoch
                            radecl = planetRaDecl(planetNumber, jcse, precision)
                            val nextPolarEvent = riseSetTime(
                                riseNotSet, radecl.rightAscension, radecl.declination,
                                observerLatitude, observerLongitude, altitudeFor(jcse), nextPolar,
                            )
                            if (isNoRiseSet(nextPolarEvent)) {
                                if (nextPolarEvent != newDate) {
                                    binaryLow = transitT; binaryLowEvent = newDate
                                    binaryHigh = nextPolar; binaryHighEvent = nextPolarEvent
                                } else if (binaryLow.isNaN()) {
                                    return RiseSetResult(newDate, transitT)
                                }
                            } else {
                                if (nextPolarEvent > tryDate + 24 * 3600) return RiseSetResult(newDate, transitT)
                                tryDate = nextPolar; newDate = nextPolarEvent
                            }
                        } else {
                            if (priorPolarEvent < tryDate - 24 * 3600) return RiseSetResult(newDate, transitT)
                            tryDate = priorPolar; newDate = priorPolarEvent
                        }

                        if (!binaryLow.isNaN()) {
                            var polarTries = numPolarTries
                            while (polarTries-- > 0) {
                                val split = (binaryLow + binaryHigh) / 2
                                jcse = julianCenturiesSince2000EpochForDateInterval(split).julianCenturiesSince2000Epoch
                                radecl = planetRaDecl(planetNumber, jcse, precision)
                                val splitEvent = riseSetTime(
                                    riseNotSet, radecl.rightAscension, radecl.declination,
                                    observerLatitude, observerLongitude, altitudeFor(jcse), split,
                                )
                                if (!isNoRiseSet(splitEvent)) { firstTransit = split; newDate = splitEvent; break }
                                if (splitEvent == binaryLowEvent) { binaryLow = split; binaryLowEvent = splitEvent }
                                else { binaryHigh = split; binaryHighEvent = splitEvent }
                            }
                            if (isNoRiseSet(newDate)) return RiseSetResult(newDate, firstTransit)
                        }
                    } else {
                        return RiseSetResult(newDate, transitT)
                    }
                }

                if (!isNoRiseSet(newDate)) {
                    lastValidTryDate = firstTransit; lastValidResultDate = newDate
                    tryDates[fitTries] = firstTransit; results[fitTries++] = newDate
                    tryDate = extrapolateToYEqualX(tryDates, results, fitTries)
                }
            } else {
                if (!lastValidTryDate.isNaN()) {
                    tryDate = (tryDate + lastValidTryDate) / 2
                } else {
                    return RiseSetResult(newDate, tryDate)
                }
            }
        } else {
            lastValidTryDate = tryDate; lastValidResultDate = newDate
            tryDates[fitTries] = tryDate; results[fitTries++] = newDate
            tryDate = extrapolateToYEqualX(tryDates, results, fitTries)
        }

        lastDelta = lastValidResultDate - lastValidTryDate
        if (abs(lastDelta) < 0.1) {
            if (planetNumber == PLANET_MOON && precision != WB_FULL) { precision = WB_FULL; i++; continue }
            return RiseSetResult(lastValidResultDate, lastValidResultDate)
        }
        i++
    }

    return when {
        lastValidResultDate.isNaN() -> RiseSetResult(lastValidResultDate, tryDate)
        abs(lastDelta) > 60 -> RiseSetResult(firstNan, firstTransit)
        else -> RiseSetResult(lastValidResultDate, lastValidResultDate)
    }
}

fun planettransitTimeRefined(
    calculationDateInterval: Double, observerLatitude: Double, observerLongitude: Double,
    wantHighTransit: Boolean, planetNumber: Int,
): Double {
    var tryDate = calculationDateInterval
    var precision = if (planetNumber == PLANET_MOON) WB_LOW else WB_FULL
    val numIterations = 7
    val tryDates = DoubleArray(numIterations)
    val results = DoubleArray(numIterations)
    var fitTries = 0

    var i = 0
    while (i < numIterations) {
        if (planetNumber == PLANET_MOON && i == numIterations - 1 && precision != WB_FULL) {
            precision = WB_FULL; i--; fitTries = 0
        }
        val jc = julianCenturiesSince2000EpochForDateInterval(tryDate).julianCenturiesSince2000Epoch
        val ra = planetRaDecl(planetNumber, jc, precision).rightAscension
        val newDate = transitTime(tryDate, wantHighTransit, observerLongitude, ra)

        if (abs(newDate - tryDate) < 0.1) {
            if (planetNumber == PLANET_MOON && precision != WB_FULL) precision = WB_FULL
            else return newDate
        }
        tryDates[fitTries] = tryDate; results[fitTries] = newDate; fitTries++
        tryDate = extrapolateToYEqualX(tryDates, results, fitTries)
        i++
    }
    return tryDate
}

// --- "For day" wrappers (search from prior UT noon) ---

fun sunriseForDay(dateInterval: Double, lat: Double, lon: Double): Double {
    val noon = priorUTMidnightForDateInterval(dateInterval) + 12 * 3600
    return planetaryRiseSetTimeRefined(noon, lat, lon, true, PLANET_SUN, Double.NaN).riseSetTime
}

fun sunsetForDay(dateInterval: Double, lat: Double, lon: Double): Double {
    val noon = priorUTMidnightForDateInterval(dateInterval) + 12 * 3600
    return planetaryRiseSetTimeRefined(noon, lat, lon, false, PLANET_SUN, Double.NaN).riseSetTime
}

fun suntransitForDay(dateInterval: Double, lat: Double, lon: Double): Double =
    planettransitTimeRefined(dateInterval, lat, lon, true, PLANET_SUN)

fun moonriseForDay(dateInterval: Double, lat: Double, lon: Double): Double {
    val noon = priorUTMidnightForDateInterval(dateInterval) + 12 * 3600
    return planetaryRiseSetTimeRefined(noon, lat, lon, true, PLANET_MOON, Double.NaN).riseSetTime
}

fun moonsetForDay(dateInterval: Double, lat: Double, lon: Double): Double {
    val noon = priorUTMidnightForDateInterval(dateInterval) + 12 * 3600
    return planetaryRiseSetTimeRefined(noon, lat, lon, false, PLANET_MOON, Double.NaN).riseSetTime
}
