package com.plasticsmoke.beryl.astro

import kotlin.math.PI

/**
 * Day/night ring leaf-angle computation (LST time base), ported from chronometer-web
 * astro-env.ts `computeDayNightLeafAngleLST` (+ helpers). Drives the QdayNightRing wedges and the
 * planetrise/set 24-hour indicator hands on the Geneva back. Cache-free.
 */

private const val FUDGE_FACTOR_SECONDS = 5.0
private const val LOOKAHEAD = 3600.0 * 13.2

/** Local sidereal angle (radians, [0,2π)) for a UT date interval. */
private fun angle24HourLSTForDate(dateInterval: Double, observerLon: Double): Double =
    esFmod(localSiderealTime(dateInterval, observerLon), 2 * PI)

/** Local clock-time 24-hour angle (radians) for a UT date interval, given the tz offset in seconds. */
internal fun angle24HourForDate(dateInterval: Double, tzOffsetSeconds: Double): Double {
    val localSeconds = dateInterval + 978307200.0 + tzOffsetSeconds  // Apple epoch → Unix, then localize
    val secondsInDay = ((localSeconds % 86400.0) + 86400.0) % 86400.0
    return secondsInDay / 3600.0 * PI / 12.0
}

/** True when the planet is above its rise/set altitude at [calcDate]. */
internal fun planetIsUpForRiseSet(planetNumber: Int, calcDate: Double, lat: Double, lon: Double): Boolean {
    val correctForParallax = planetNumber == PLANET_MOON
    val alt = planetAltAz(planetNumber, calcDate, lat, lon, correctForParallax, true)
    val jc = julianCenturiesSince2000EpochForDateInterval(calcDate).julianCenturiesSince2000Epoch
    val altAtRS = altitudeAtRiseSet(jc, planetNumber, false, WB_FULL)
    return alt > altAtRS
}

private data class RiseSetEvent(val eventTime: Double, val transitTime: Double)

/** Next/previous rise or set with a transit-validity retry (mirrors nextPrevRiseSetInternal). */
private fun nextPrevRiseSetInternal(
    calcDate: Double, lat: Double, lon: Double,
    riseNotSet: Boolean, planetNumber: Int, isNext: Boolean,
    fudgeSeconds: Double, lookahead: Double,
): RiseSetEvent {
    var fudge = fudgeSeconds
    var look = lookahead
    if (!isNext) { fudge = -fudge; look = -look }

    val fudgeDate = calcDate + fudge
    val r1 = planetaryRiseSetTimeRefined(fudgeDate, lat, lon, riseNotSet, planetNumber, Double.NaN)
    val transitOk = if (isNext) r1.transitTime >= fudgeDate else r1.transitTime < fudgeDate
    if (transitOk) return RiseSetEvent(r1.riseSetTime, r1.transitTime)

    val r2 = planetaryRiseSetTimeRefined(fudgeDate + look, lat, lon, riseNotSet, planetNumber, Double.NaN)
    return RiseSetEvent(r2.riseSetTime, r2.transitTime)
}

/**
 * Leaf-center angle (radians) for wedge [leafNumber] of [numLeaves] on the LST day/night ring.
 * When [numLeaves]==0, leafNumber 0 returns the rise indicator angle and 1 the set indicator angle.
 */
fun computeDayNightLeafAngleLST(
    planetNumber: Int, leafNumber: Int, numLeaves: Int,
    calcDate: Double, lat: Double, lon: Double,
): Double {
    val planetIsUp = planetIsUpForRiseSet(planetNumber, calcDate, lat, lon)

    val riseResult = nextPrevRiseSetInternal(calcDate, lat, lon, true, planetNumber, !planetIsUp, -FUDGE_FACTOR_SECONDS, LOOKAHEAD)
    val setResult = nextPrevRiseSetInternal(calcDate, lat, lon, false, planetNumber, planetIsUp, -FUDGE_FACTOR_SECONDS, LOOKAHEAD)

    val riseTime = riseResult.eventTime
    val setTime = setResult.eventTime

    var rTransitAngle = angle24HourLSTForDate(riseResult.transitTime, lon)
    var sTransitAngle = angle24HourLSTForDate(setResult.transitTime, lon)
    if (isAlwaysAbove(riseTime)) rTransitAngle = esFmod(rTransitAngle + PI, 2 * PI)
    if (isAlwaysAbove(setTime)) sTransitAngle = esFmod(sTransitAngle + PI, 2 * PI)

    var riseTimeAngle = if (isNoRiseSet(riseTime)) Double.NaN else angle24HourLSTForDate(riseTime, lon)
    var setTimeAngle = if (isNoRiseSet(setTime)) Double.NaN else angle24HourLSTForDate(setTime, lon)

    if (numLeaves == 0) {
        return when (leafNumber) {
            0 -> if (riseTimeAngle.isNaN()) rTransitAngle else riseTimeAngle
            1 -> if (setTimeAngle.isNaN()) sTransitAngle else setTimeAngle
            else -> 0.0
        }
    }

    val leafWidth = 2 * PI / numLeaves

    if (riseTimeAngle.isNaN()) {
        if (setTimeAngle.isNaN()) {
            var sTA = sTransitAngle
            if (sTA > rTransitAngle + PI) sTA -= 2 * PI
            else if (sTA < rTransitAngle - PI) sTA += 2 * PI
            val avgTransit = (rTransitAngle + sTA) / 2
            if (isAlwaysAbove(riseTime)) {
                riseTimeAngle = avgTransit - PI
                setTimeAngle = avgTransit + PI
            } else {
                riseTimeAngle = avgTransit - leafWidth / 2 - 0.00001
                setTimeAngle = avgTransit + leafWidth / 2 + 0.00001
            }
        } else {
            riseTimeAngle = if (isAlwaysAbove(riseTime)) setTimeAngle - 2 * PI else setTimeAngle - leafWidth
        }
    } else if (setTimeAngle.isNaN()) {
        setTimeAngle = if (isAlwaysAbove(setTime)) riseTimeAngle + 2 * PI else riseTimeAngle + leafWidth
    }

    riseTimeAngle = esFmod(riseTimeAngle, 2 * PI)
    setTimeAngle = esFmod(setTimeAngle, 2 * PI)
    if (setTimeAngle <= riseTimeAngle + 0.0001) setTimeAngle += 2 * PI

    setTimeAngle -= leafWidth / 2
    riseTimeAngle += leafWidth / 2
    if (setTimeAngle < riseTimeAngle) {
        riseTimeAngle = (riseTimeAngle + setTimeAngle) / 2
        setTimeAngle = riseTimeAngle
    }

    var leafCenterAngle = riseTimeAngle + (setTimeAngle - riseTimeAngle) / (numLeaves - 1) * leafNumber
    if (leafCenterAngle > 2 * PI) leafCenterAngle -= 2 * PI
    return leafCenterAngle
}

/**
 * Leaf-center angle (radians) for wedge [leafNumber] of [numLeaves] on the CLOCK-TIME day/night ring
 * (the default, non-LST time base used by 24-hour watches like Vienna). Identical in structure to
 * [computeDayNightLeafAngleLST] but positions events by local clock time rather than sidereal time.
 * Ported from chronometer-web `computeDayNightLeafAngle`.
 */
fun computeDayNightLeafAngle(
    planetNumberIn: Int, leafNumber: Int, numLeavesIn: Int,
    calcDate: Double, lat: Double, lon: Double, tzOffsetSeconds: Double,
): Double {
    val nightTime = planetNumberIn == PLANET_MIDNIGHT_SUN
    val planetNumber = if (nightTime) PLANET_SUN else planetNumberIn
    var numLeaves = numLeavesIn

    val planetIsUp = planetIsUpForRiseSet(planetNumber, calcDate, lat, lon)

    val riseResult = nextPrevRiseSetInternal(calcDate, lat, lon, true, planetNumber, !planetIsUp, -FUDGE_FACTOR_SECONDS, LOOKAHEAD)
    val setResult = nextPrevRiseSetInternal(calcDate, lat, lon, false, planetNumber, planetIsUp, -FUDGE_FACTOR_SECONDS, LOOKAHEAD)

    val riseTime = riseResult.eventTime
    val setTime = setResult.eventTime

    var rTransitAngle = angle24HourForDate(riseResult.transitTime, tzOffsetSeconds)
    var sTransitAngle = angle24HourForDate(setResult.transitTime, tzOffsetSeconds)
    if (isAlwaysAbove(riseTime)) rTransitAngle = esFmod(rTransitAngle + PI, 2 * PI)
    if (isAlwaysAbove(setTime)) sTransitAngle = esFmod(sTransitAngle + PI, 2 * PI)

    var riseTimeAngle = if (isNoRiseSet(riseTime)) Double.NaN else angle24HourForDate(riseTime, tzOffsetSeconds)
    var setTimeAngle = if (isNoRiseSet(setTime)) Double.NaN else angle24HourForDate(setTime, tzOffsetSeconds)

    if (numLeaves == 0) {
        return when (leafNumber) {
            0 -> if (riseTimeAngle.isNaN()) rTransitAngle else riseTimeAngle
            1 -> if (setTimeAngle.isNaN()) sTransitAngle else setTimeAngle
            else -> 0.0
        }
    }

    if (numLeaves < 0) numLeaves = -numLeaves
    val leafWidth = 2 * PI / numLeaves

    if (riseTimeAngle.isNaN()) {
        if (setTimeAngle.isNaN()) {
            var sTA = sTransitAngle
            if (sTA > rTransitAngle + PI) sTA -= 2 * PI
            else if (sTA < rTransitAngle - PI) sTA += 2 * PI
            val avgTransit = (rTransitAngle + sTA) / 2
            if (isAlwaysAbove(riseTime)) {
                riseTimeAngle = avgTransit - PI
                setTimeAngle = avgTransit + PI
            } else {
                riseTimeAngle = avgTransit - leafWidth / 2 - 0.00001
                setTimeAngle = avgTransit + leafWidth / 2 + 0.00001
            }
        } else {
            riseTimeAngle = if (isAlwaysAbove(riseTime)) setTimeAngle - 2 * PI else setTimeAngle - leafWidth
        }
    } else if (setTimeAngle.isNaN()) {
        setTimeAngle = if (isAlwaysAbove(setTime)) riseTimeAngle + 2 * PI else riseTimeAngle + leafWidth
    }

    riseTimeAngle = esFmod(riseTimeAngle, 2 * PI)
    setTimeAngle = esFmod(setTimeAngle, 2 * PI)
    if (setTimeAngle <= riseTimeAngle + 0.0001) setTimeAngle += 2 * PI

    if (nightTime) {
        setTimeAngle += leafWidth / 2
        riseTimeAngle -= leafWidth / 2
    } else {
        setTimeAngle -= leafWidth / 2
        riseTimeAngle += leafWidth / 2
    }

    if (setTimeAngle < riseTimeAngle) {
        riseTimeAngle = (riseTimeAngle + setTimeAngle) / 2
        setTimeAngle = riseTimeAngle
    }

    var leafCenterAngle = if (nightTime) {
        setTimeAngle + (2 * PI - setTimeAngle + riseTimeAngle) / (numLeaves - 1) * leafNumber
    } else {
        riseTimeAngle + (setTimeAngle - riseTimeAngle) / (numLeaves - 1) * leafNumber
    }
    if (leafCenterAngle > 2 * PI) leafCenterAngle -= 2 * PI
    return leafCenterAngle
}

// ============================================================================
// Rise / set / transit "for the current local day" (iOS planetRiseSetForDay /
// planettransitForDay) — used by Miami's back. Returns NaN when the event does
// not fall on the observer's local calendar day.
// ============================================================================

/** Whether two UT date intervals fall on the same LOCAL calendar day (tz offset in seconds). */
private fun isSameLocalDay(di1: Double, di2: Double, tzOffsetSeconds: Double): Boolean {
    val c1 = utcComponentsFromTimeInterval(di1 + tzOffsetSeconds)
    val c2 = utcComponentsFromTimeInterval(di2 + tzOffsetSeconds)
    return c1.era == c2.era && c1.year == c2.year && c1.month == c2.month && c1.day == c2.day
}

/** Next/prev high transit (parallels [nextPrevRiseSetInternal] but for the culmination). */
private fun nextPrevTransitInternal(
    calcDate: Double, lat: Double, lon: Double, planetNumber: Int, isNext: Boolean,
    fudgeSeconds: Double, lookahead: Double,
): Double {
    var fudge = fudgeSeconds; var look = lookahead
    if (!isNext) { fudge = -fudge; look = -look }
    val fudgeDate = calcDate + fudge
    val t1 = planettransitTimeRefined(fudgeDate, lat, lon, true, planetNumber)
    val ok = if (isNext) t1 >= fudgeDate else t1 < fudgeDate
    if (ok) return t1
    return planettransitTimeRefined(fudgeDate + look, lat, lon, true, planetNumber)
}

/** The planet's rise (or set) on the observer's current local day, else NaN (iOS planetRiseSetForDay). */
fun planetRiseSetForDay(planetNumber: Int, riseNotSet: Boolean, calcDate: Double, lat: Double, lon: Double, tzOffsetSeconds: Double): Double {
    val fwd = nextPrevRiseSetInternal(calcDate, lat, lon, riseNotSet, planetNumber, true, -FUDGE_FACTOR_SECONDS, LOOKAHEAD)
    var returnDate = fwd.eventTime
    if (!isSameLocalDay(fwd.transitTime, calcDate, tzOffsetSeconds)) {
        val bwd = nextPrevRiseSetInternal(calcDate, lat, lon, riseNotSet, planetNumber, false, -FUDGE_FACTOR_SECONDS, LOOKAHEAD)
        returnDate = bwd.eventTime
        if (!returnDate.isNaN() && !isSameLocalDay(returnDate, calcDate, tzOffsetSeconds)) returnDate = Double.NaN
    }
    return if (isNoRiseSet(returnDate)) Double.NaN else returnDate
}

/** The planet's high transit on the observer's current local day, else NaN (iOS planettransitForDay). */
fun planetTransitForDay(planetNumber: Int, calcDate: Double, lat: Double, lon: Double, tzOffsetSeconds: Double): Double {
    var returnDate = nextPrevTransitInternal(calcDate, lat, lon, planetNumber, true, -FUDGE_FACTOR_SECONDS, LOOKAHEAD)
    if (!isSameLocalDay(returnDate, calcDate, tzOffsetSeconds)) {
        returnDate = nextPrevTransitInternal(calcDate, lat, lon, planetNumber, false, -FUDGE_FACTOR_SECONDS, LOOKAHEAD)
        if (!returnDate.isNaN() && !isSameLocalDay(returnDate, calcDate, tzOffsetSeconds)) returnDate = Double.NaN
    }
    return returnDate
}
