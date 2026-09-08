package com.plasticsmoke.beryl.astro

import com.plasticsmoke.beryl.expr.Environment
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.round

/**
 * Registers the live time / calendar / angle functions that watch-face XML expressions call,
 * ported from the relevant slice of chronometer-web `src/shared/astro-env.ts`.
 *
 * Scope: the functions Milano needs plus their close angle/number siblings. Sun/moon/planet
 * astronomy is deferred until a face requires it.
 */

/** Supplies the current display time as epoch milliseconds (UTC). */
fun interface TimeSource {
    fun nowMillis(): Long
}

/** Live clock components for a single instant. h is 0–12 fractional, m/s are fractional. */
private class LiveTime(val h: Double, val m: Double, val s: Double, val h24: Int)

/**
 * Register time/calendar/angle functions into [env].
 *
 * @param tzOffsetSeconds timezone offset east-positive (e.g. PST = -28800). Treated as a fixed
 *   offset for now; DST-aware Olson resolution is a later concern.
 * @param observerLatRad / @param observerLonRad observer location (radians, east-positive lon).
 * @param now display-time source, sampled fresh on every function call (so hands move live).
 */
fun registerWatchFunctions(
    env: Environment,
    tzOffsetSeconds: Double,
    observerLatRad: Double = 0.0,
    observerLonRad: Double = 0.0,
    now: TimeSource,
) {
    val f = env.functions
    env.tzOffsetSec = tzOffsetSeconds
    env.observerLatRad = observerLatRad
    env.observerLonRad = observerLonRad

    val tzOffsetMillis = (tzOffsetSeconds * 1000.0).toLong()

    fun liveTime(): LiveTime {
        val localMillis = now.nowMillis() + tzOffsetMillis
        // within-day milliseconds, always non-negative
        val ms = ((localMillis % 86_400_000L) + 86_400_000L) % 86_400_000L
        val millis = (ms % 1000L).toInt()
        val totalSec = ms / 1000L
        val sec = (totalSec % 60L).toInt()
        val min = ((totalSec / 60L) % 60L).toInt()
        val h24 = (totalSec / 3600L).toInt()
        val s = sec + millis / 1000.0
        val m = min + s / 60.0
        val h = (h24 % 12) + m / 60.0
        return LiveTime(h, m, s, h24)
    }

    fun dateInterval(): Double = dateIntervalFromEpochMillis(now.nowMillis())
    fun localComponents(): EsDateComponents = localComponentsFromTimeInterval(dateInterval(), tzOffsetSeconds)

    // --- Clock hands (live) ---
    f["hour12ValueAngle"] = { liveTime().h * 2 * PI / 12 }
    f["minuteValueAngle"] = { liveTime().m * 2 * PI / 60 }
    f["secondValueAngle"] = { liveTime().s * 2 * PI / 60 }
    f["secondNumberAngle"] = { floor(liveTime().s) * 2 * PI / 60 }
    f["secondValue"] = { liveTime().s }
    // Watch time as a seconds interval (Tombstone's slowly-turning third wheel: angle ∝ currentTime).
    f["currentTime"] = { dateInterval() }
    f["hour24Number"] = { liveTime().h24.toDouble() }
    f["minuteNumber"] = { floor(liveTime().m) }
    f["secondNumber"] = { floor(liveTime().s) }
    // 12-hour number 1..12 (0 → 12), for AtlantisIV's digit wheels. ECVirtualMachineOps hour12Number12.
    f["hour12Number12"] = { val h = liveTime().h24 % 12; (if (h == 0) 12 else h).toDouble() }
    f["hour24Value"] = { val t = liveTime(); t.h24 + t.m / 60.0 }
    f["hour24ValueAngle"] = { val t = liveTime(); (t.h24 + t.m / 60.0) * 2 * PI / 24 }
    f["tzOffsetAngle"] = { tzOffsetSeconds * PI / (3600 * 12) }

    // --- Calendar (live, hybrid Julian/Gregorian) ---
    f["dayNumber"] = { (localComponents().day - 1).toDouble() }          // 0-indexed for wheel math
    f["dayNumberAngle"] = { (localComponents().day - 1) * 2 * PI / 31 }
    f["monthNumber"] = { (localComponents().month - 1).toDouble() }      // 0-indexed
    f["monthNumberAngle"] = { (localComponents().month - 1) * 2 * PI / 12 }
    f["weekdayNumber"] = { weekdayFromTimeInterval(dateInterval(), tzOffsetSeconds).toDouble() }
    f["weekdayNumberAngle"] = { weekdayFromTimeInterval(dateInterval(), tzOffsetSeconds) * 2.0 * PI / 7 }
    f["yearNumber"] = { localComponents().year.toDouble() }
    f["eraNumber"] = { localComponents().era.toDouble() }

    // --- Grid calendar (Babylon) ---
    // The week-start setting (0=Sunday, 1=Monday, 6=Saturday). iOS's ECCalendarWeekdayStart defaults
    // to Sunday (its US default); the iPhone the render is compared against uses Sunday. Ported from
    // ECGLWatch.m calendar methods + ECWatchTime weekdayNumberAsCalendarColumnUsingEnv.
    val calendarWeekdayStart = 0
    f["calendarWeekdayStart"] = { calendarWeekdayStart.toDouble() }

    // Which grid column the current day falls in (0..6), accounting for week start.
    fun weekdayAsCalendarColumn(): Int {
        val wd = weekdayFromTimeInterval(dateInterval(), tzOffsetSeconds)  // 0=Sunday
        return (7 + wd - calendarWeekdayStart) % 7
    }
    // Which column the 1st of the month falls in. (Not valid in Oct 1582 after the transition.)
    fun columnOfFirstOfMonth(): Int {
        val weekday = weekdayAsCalendarColumn()
        val dayOfMonth = localComponents().day - 1   // 1st of month is 0
        return (weekday + 7 - (dayOfMonth % 7)) % 7
    }

    f["calendarColumn"] = { weekdayAsCalendarColumn().toDouble() }
    f["calendarRow"] = {
        val cs = localComponents()
        var dayNumber = cs.day - 1
        var firstCol = columnOfFirstOfMonth()
        if (cs.year == 1582 && cs.month - 1 == 9 && cs.era == 1 && dayNumber > 4) {  // October 1582 CE
            dayNumber -= 10
            firstCol = (8 - calendarWeekdayStart) % 7   // October 1582 started on a Monday
        }
        ((dayNumber + firstCol) / 7).toDouble()
    }

    // Wheel rotations: each calendar wheel has 4 quadrants 90° apart; the rotation selects which
    // quadrant lands at the top (in the calendar window). A wheel returns 0 (parked) when its
    // designed week start doesn't match the runtime one. it[0] = the wheel's designed weekdayStart.
    f["rotationForCalendarWheel3456"] = {
        if (it[0].toInt() != calendarWeekdayStart) 0.0
        else { val wd1 = columnOfFirstOfMonth(); if (wd1 < 4) 0.0 else (wd1 - 3) * PI / 2 }
    }
    f["rotationForCalendarWheel012B"] = {
        if (it[0].toInt() != calendarWeekdayStart) 0.0
        else { val wd1 = columnOfFirstOfMonth(); if (wd1 > 2) 3 * PI / 2 else wd1 * PI / 2 }
    }
    f["rotationForCalendarWheelOct1582"] = {
        if (it[0].toInt() != calendarWeekdayStart) 0.0
        else { val cs = localComponents(); if (cs.year == 1582 && cs.month - 1 == 9) 0.0 else PI / 2 }
    }

    // ====================================================================
    // Astronomy (sun/moon positions, phase, rise/set), ported from astro-env.ts
    // ====================================================================

    fun isSameLocalDay(di1: Double, di2: Double): Boolean {
        if (di1.isNaN() || di2.isNaN() || isNoRiseSet(di1)) return false
        val c1 = localComponentsFromTimeInterval(di1, tzOffsetSeconds)
        val c2 = localComponentsFromTimeInterval(di2, tzOffsetSeconds)
        return c1.era == c2.era && c1.year == c2.year && c1.month == c2.month && c1.day == c2.day
    }

    // Search rise/set from local noon (fwd), then previous local noon (bwd); accept only an event
    // on the user's calendar day. Returns NaN when there's no event today.
    fun riseSetForDay(riseNotSet: Boolean, planet: Int): Double {
        val d = dateInterval()
        val cs = localComponentsFromTimeInterval(d, tzOffsetSeconds)
        val noonDI = timeIntervalFromLocalComponents(tzOffsetSeconds, cs.era, cs.year, cs.month, cs.day, 12, 0, 0.0)
        val fwd = planetaryRiseSetTimeRefined(noonDI, observerLatRad, observerLonRad, riseNotSet, planet, Double.NaN).riseSetTime
        if (!isNoRiseSet(fwd) && !fwd.isNaN() && isSameLocalDay(fwd, d)) return fwd
        val bwd = planetaryRiseSetTimeRefined(noonDI - 24 * 3600, observerLatRad, observerLonRad, riseNotSet, planet, Double.NaN).riseSetTime
        if (!isNoRiseSet(bwd) && !bwd.isNaN() && isSameLocalDay(bwd, d)) return bwd
        return Double.NaN
    }

    // Precise local time-of-day decomposition of a rise/set instant (not via the calendar, whose
    // sub-day fields carry float imprecision).
    fun riseSetAngles(di: Double): Triple<Double, Double, Double> {
        val localSec = di + tzOffsetSeconds
        val secOfDay = ((localSec % 86400.0) + 86400.0) % 86400.0
        val hour = floor(secOfDay / 3600).toInt()
        val minInt = floor((secOfDay % 3600.0) / 60.0).toInt()
        val secInt = floor(secOfDay % 60.0).toInt()
        val hour12 = (hour % 12) + minInt / 60.0 + secInt / 3600.0
        val minute = minInt + secInt / 60.0
        return Triple(hour12, minute, hour.toDouble())
    }

    fun registerRiseSet(name: String, riseNotSet: Boolean, planet: Int) {
        f["${name}Valid"] = { if (riseSetForDay(riseNotSet, planet).isNaN()) 0.0 else 1.0 }
        f["${name}Hour12ValueAngle"] = { val r = riseSetForDay(riseNotSet, planet); if (r.isNaN()) 0.0 else riseSetAngles(r).first * 2 * PI / 12 }
        f["${name}MinuteValueAngle"] = { val r = riseSetForDay(riseNotSet, planet); if (r.isNaN()) 0.0 else riseSetAngles(r).second * 2 * PI / 60 }
        f["${name}Hour24Number"] = { val r = riseSetForDay(riseNotSet, planet); if (r.isNaN()) 0.0 else riseSetAngles(r).third }
    }
    registerRiseSet("sunriseForDay", true, PLANET_SUN)
    registerRiseSet("sunsetForDay", false, PLANET_SUN)
    registerRiseSet("moonriseForDay", true, PLANET_MOON)
    registerRiseSet("moonsetForDay", false, PLANET_MOON)

    // Moon phase / age
    f["moonAgeAngle"] = { moonAge(dateInterval()).age }
    f["realMoonAgeAngle"] = { moonAge(dateInterval()).age / (2 * PI) * 29.530588 }
    f["moonRelativePositionAngle"] = { moonRelativePositionAngle(dateInterval(), observerLatRad, observerLonRad) }
    f["moonRelativeAngle"] = { moonRelativeAngle(dateInterval(), observerLatRad, observerLonRad) }
    // Closest moon-phase day-of-month (0-indexed, for Chandra's phase wheels).
    fun closestPhaseDay(targetAngle: Double): Double {
        val t = closestQuarterPhaseTime(targetAngle, dateInterval())
        return localComponentsFromTimeInterval(t, tzOffsetSeconds).day.toDouble() - 1
    }
    f["closestNewMoonDayNumber"] = { closestPhaseDay(0.0) }
    f["closestFirstQuarterDayNumber"] = { closestPhaseDay(PI / 2) }
    f["closestFullMoonDayNumber"] = { closestPhaseDay(PI) }
    f["closestThirdQuarterDayNumber"] = { closestPhaseDay(3 * PI / 2) }
    f["moonAltitude"] = { moonAltitude(dateInterval(), observerLatRad, observerLonRad) }
    f["moonAzimuth"] = { moonAzimuth(dateInterval(), observerLatRad, observerLonRad) }
    f["sunAltitude"] = { sunAltitude(dateInterval(), observerLatRad, observerLonRad) }
    f["sunAzimuth"] = { sunAzimuth(dateInterval(), observerLatRad, observerLonRad) }

    // Seasons (sun ecliptic longitude)
    f["season"] = {
        val north = observerLatRad >= 0
        val sunLong = sunEclipticLongitudeForDate(dateInterval())
        when {
            sunLong > PI * 3 / 2 -> if (north) 3.0 else 1.0
            sunLong > PI -> if (north) 2.0 else 0.0
            sunLong > PI / 2 -> if (north) 1.0 else 3.0
            else -> if (north) 0.0 else 2.0
        }
    }
    f["offsetOfWinterSolsticeFromDec31Midnight"] = {
        val d = dateInterval()
        val todays = sunEclipticLongitudeForDate(d)
        val cs = utcComponentsFromTimeInterval(d)
        val thisDay2001 = timeIntervalFromUTCComponents(1, 2001, cs.month, cs.day, cs.hour, cs.minute, cs.seconds)
        val y2001 = sunEclipticLongitudeForDate(thisDay2001)
        val nsOffset = if (observerLatRad >= 0) 0.0 else PI
        (y2001 - todays) - 10.25 / 365.25 * 2 * PI + nsOffset
    }

    // Calendar/era helpers
    f["GregorianEra"] = { if (dateInterval() > kECJulianGregorianSwitchoverTimeInterval) 1.0 else 0.0 }
    f["yearNumberCEMonotonic"] = { val cs = localComponents(); (if (cs.era == 0) 1 - cs.year else cs.year).toDouble() }
    f["monthLen"] = { val cs = localComponents(); daysInMonth(cs.era, cs.year, cs.month).toDouble() }
    f["leapYearIndicatorAngle"] = {
        val cs = localComponents(); val y = cs.year
        PI + when {
            cs.era != 0 && y >= 1582 -> when {
                y % 400 == 0 -> 3 * PI / 4
                y % 100 == 0 -> 5 * PI / 4
                y % 4 == 0 -> PI / 4
                else -> ((y % 4) * 2 + 17) * PI / 12
            }
            cs.era != 0 -> if (y % 4 == 0) PI / 4 else ((y % 4) * 2 + 17) * PI / 12
            else -> { val ay = y - 1; if (ay % 4 == 0) PI / 4 else ((ay % 4) * 2 + 17) * PI / 12 }
        }
    }
    // Leap-year ±indicator hand (Atlantis): distinct from leapYearIndicatorAngle (the C/1/4/L dial).
    // ECVirtualMachineOps.m leapYearIndicatorAngle1 — π/8 = non-leap, with the 100/400 exceptions.
    f["leapYearIndicatorAngle1"] = {
        val cs = localComponents(); var y = cs.year
        when {
            cs.era != 0 && y >= 1582 -> if (y % 4 != 0) PI / 8 else if (y % 400 == 0) -3 * PI / 8 else if (y % 100 == 0) 3 * PI / 8 else -PI / 8
            cs.era != 0 -> if (y % 4 != 0) PI / 8 else -PI / 8
            else -> { y -= 1; if (y % 4 != 0) PI / 8 else -PI / 8 }
        }
    }
    // Digit extraction for Atlantis' position/year digit hands. digitValue(v, digitMultiplier,
    // roundingPrecision) = floor(round(v/prec)*prec / mult); caller fmods by the wheel's digit count.
    f["digitValue"] = { a -> floor(round(a[0] / a[2]) * a[2] / a[1]) }
    // Observer location in degrees (Atlantis lat/long display). ECVirtualMachineOps latitude/longitudeDegrees.
    f["latitudeDegrees"] = { observerLatRad * 180 / PI }
    f["longitudeDegrees"] = { observerLonRad * 180 / PI }

    // DST detection needs Olson tz data (deferred); fixed-offset → 0.
    f["DSTNumber"] = { 0.0 }
    f["tzOffset"] = { tzOffsetSeconds }

    // Interactive-state leaves used by button `motion` expressions — at-rest defaults for the
    // static render (buttons sit at their base position, mostly hidden under the band/case art).
    f["manualSet"] = { 0.0 }          // stem not pulled out
    f["alarmManualSet"] = { 0.0 }
    f["timeIsCorrect"] = { 1.0 }      // NTP-synced → reset button stays parked
    // iOS ChronometerAppDelegate: buttonPressed == partBeingEvaluated — 1 only inside the held
    // button's own expressions (selector2's motion='!thisButtonPressed()' depresses it live).
    f["thisButtonPressed"] = {
        if (env.evaluatingButton != null && env.evaluatingButton == env.pressedButton) 1.0 else 0.0
    }
    f["appMode"] = { 0.0 }            // main mode (mode-enum vars are iOS-VM builtins; exprs
    f["singleWatchProduct"] = { 0.0 } // referencing undefined ones fall to 0 via evalAttr)
    f["inReverse"] = { 0.0 }
    f["runningDemo"] = { 0.0 }
    // Button-action side-effect leaves (no-op defaults; the app re-registers with real hooks).
    registerActionFunctions(env)

    // ====================================================================
    // Back-side astronomy (sidereal, EOT, equinox-of-date, day/night ring, nodes, eclipse)
    // ====================================================================
    env.variables["planetSun"] = PLANET_SUN.toDouble()
    env.variables["planetMoon"] = PLANET_MOON.toDouble()
    env.variables["planetMercury"] = PLANET_MERCURY.toDouble()
    env.variables["planetVenus"] = PLANET_VENUS.toDouble()
    env.variables["planetEarth"] = PLANET_EARTH.toDouble()
    env.variables["planetMars"] = PLANET_MARS.toDouble()
    env.variables["planetJupiter"] = PLANET_JUPITER.toDouble()
    env.variables["planetSaturn"] = PLANET_SATURN.toDouble()
    // 24-hour dial orientation toggle (0 = 24/midnight on top). The Android :app flips this.
    env.variables["dialFlip"] = 0.0
    // Current display time as an Apple-epoch date interval — used by renderers (e.g. the analemma).
    f["_dateInterval"] = { dateInterval() }

    fun jc(): Double = julianCenturiesSince2000EpochForDateInterval(dateInterval()).julianCenturiesSince2000Epoch

    f["minuteValue"] = { liveTime().m }
    f["longitude"] = { observerLonRad }
    f["latitude"] = { observerLatRad }
    f["goodAccuracy"] = { 1.0 }
    f["sunRA"] = { sunRAandDecl(dateInterval()).rightAscension }
    f["moonRA"] = { moonRAAndDecl(dateInterval()).rightAscension }
    f["lstValue"] = { localSiderealTime(dateInterval(), observerLonRad) * (12 * 3600) / PI }
    f["EOTAngle"] = { eotSeconds(dateInterval()) * PI / (12 * 3600) }
    f["J2000RAofVernalEquinoxOfDateAngle"] = { -generalPrecessionSinceJ2000(jc()) }
    f["lunarAscendingNodeRA"] = { lunarAscendingNodeRA(dateInterval()) }
    f["RAOfPlanet"] = { a -> raAndDeclOfPlanet(a[0].toInt(), dateInterval()).rightAscension }
    // Heliocentric ecliptic longitude (Firenze orrery — positions planets on their orbital rings).
    f["HLongitudeOfPlanet"] = { a -> heliocentricLongitudeOfPlanet(a[0].toInt(), jc() / 100) }
    // Azimuth where the ecliptic is highest (Firenze back horizon mask).
    f["azimuthOfHighestEclipticAltitude"] = { azimuthOfHighestEclipticAltitude(dateInterval(), observerLatRad, observerLonRad) }
    // Alt/az of any planet (Mercury..Saturn route through the WB planet theory; sun/moon as before).
    f["azimuthOfPlanet"] = { a -> planetAltAz(a[0].toInt(), dateInterval(), observerLatRad, observerLonRad, a[0].toInt() == PLANET_MOON, false) }
    f["altitudeOfPlanet"] = { a -> planetAltAz(a[0].toInt(), dateInterval(), observerLatRad, observerLonRad, a[0].toInt() == PLANET_MOON, true) }
    // Transit (culmination) of a planet → clock-time 24-hour angle (Miami's ring label hands).
    f["planettransit24HourIndicatorAngle"] = { a ->
        angle24HourForDate(planettransitTimeRefined(dateInterval(), observerLatRad, observerLonRad, true, a[0].toInt()), tzOffsetSeconds)
    }

    // Day/night ring + rise/set indicators (LST time base)
    f["planetrise24HourIndicatorAngleLST"] = { a -> computeDayNightLeafAngleLST(a[0].toInt(), 0, 0, dateInterval(), observerLatRad, observerLonRad) }
    f["planetset24HourIndicatorAngleLST"] = { a -> computeDayNightLeafAngleLST(a[0].toInt(), 1, 0, dateInterval(), observerLatRad, observerLonRad) }
    f["dayNightLeafAngleLST"] = { a -> computeDayNightLeafAngleLST(a[0].toInt(), a[1].toInt(), a[2].toInt(), dateInterval(), observerLatRad, observerLonRad) }

    // Day/night ring + rise/set indicators (clock-time base — used by 24-hour watches like Vienna)
    f["planetrise24HourIndicatorAngle"] = { a -> computeDayNightLeafAngle(a[0].toInt(), 0, 0, dateInterval(), observerLatRad, observerLonRad, tzOffsetSeconds) }
    f["planetset24HourIndicatorAngle"] = { a -> computeDayNightLeafAngle(a[0].toInt(), 1, 0, dateInterval(), observerLatRad, observerLonRad, tzOffsetSeconds) }
    f["dayNightLeafAngle"] = { a -> computeDayNightLeafAngle(a[0].toInt(), a[1].toInt(), a[2].toInt(), dateInterval(), observerLatRad, observerLonRad, tzOffsetSeconds) }
    // Moon-specific (no-arg) clock-time rise/set indicators — Uraniborg's moonrise/moonset hands.
    f["moonrise24HourIndicatorAngle"] = { computeDayNightLeafAngle(PLANET_MOON, 0, 0, dateInterval(), observerLatRad, observerLonRad, tzOffsetSeconds) }
    f["moonset24HourIndicatorAngle"] = { computeDayNightLeafAngle(PLANET_MOON, 1, 0, dateInterval(), observerLatRad, observerLonRad, tzOffsetSeconds) }
    // Sun-specific (no-arg) clock-time rise/set indicators — Mauna Kea dawn/dusk.
    f["sunrise24HourIndicatorAngle"] = { computeDayNightLeafAngle(PLANET_SUN, 0, 0, dateInterval(), observerLatRad, observerLonRad, tzOffsetSeconds) }
    f["sunset24HourIndicatorAngle"] = { computeDayNightLeafAngle(PLANET_SUN, 1, 0, dateInterval(), observerLatRad, observerLonRad, tzOffsetSeconds) }

    // Wadokei (Kyoto): traditional Japanese hours — daylight (sunrise→sunset) and night (sunset→
    // sunrise) are each divided into 6 equal "hours", so the hour length varies with the season.
    // Ported from ECVirtualMachineOps japanHourValueAngle / angleForJapanHour.
    fun sunRiseSetHour24(riseNotSet: Boolean): Double {
        val t = riseSetForDay(riseNotSet, PLANET_SUN)
        if (t.isNaN()) return Double.NaN
        val c = localComponentsFromTimeInterval(t, tzOffsetSeconds)
        return c.hour + c.minute / 60.0 + c.seconds / 3600.0
    }
    f["japanHourValueAngle"] = {
        val t = liveTime(); val nowH = t.h24 + t.m / 60.0
        val dayTime = planetIsUpForRiseSet(PLANET_SUN, dateInterval(), observerLatRad, observerLonRad)
        val sunrise = sunRiseSetHour24(true); val sunset = sunRiseSetHour24(false)
        var dayLen = sunset - sunrise; if (sunrise >= sunset) dayLen += 24
        if (dayTime) {
            var nh = nowH; if (nh < sunrise) nh += 24
            ((nh - sunrise) / dayLen + 1.5) * PI
        } else {
            var nightLen = 24 - dayLen; if (nightLen == 0.0) nightLen = 24.0
            var nh = nowH; if (nh < sunset) nh += 24
            ((nh - sunset) / nightLen + 0.5) * PI
        }
    }
    f["angleForJapanHour"] = { a ->
        val n = a[0]
        val sunrise = sunRiseSetHour24(true); val sunset = sunRiseSetHour24(false)
        val dayLen = if (sunrise < sunset) sunset - sunrise else sunset + 24 - sunrise
        val nightLen = 24 - dayLen
        when {
            n >= 9 -> (sunrise + (n - 9) / 6 * dayLen) * PI / 12 + PI    // sunrise → noon
            n >= 6 -> (sunrise - (9 - n) / 6 * nightLen) * PI / 12 + PI  // midnight → sunrise
            n >= 3 -> (sunset + (n - 3) / 6 * nightLen) * PI / 12 + PI   // sunset → midnight
            else -> (sunset - (3 - n) / 6 * dayLen) * PI / 12 + PI       // noon → sunset
        }
    }
    // Moon's "noon" = transit, midway between moonrise & moonset indicator angles (ECVirtualMachineOps moonNoonAngle).
    f["moonNoonAngle"] = {
        val rise = computeDayNightLeafAngle(PLANET_MOON, 0, 0, dateInterval(), observerLatRad, observerLonRad, tzOffsetSeconds)
        var set = computeDayNightLeafAngle(PLANET_MOON, 1, 0, dateInterval(), observerLatRad, observerLonRad, tzOffsetSeconds)
        if (rise == set) 0.0 else { if (rise > set) set += 2 * PI; (rise + set) / 2 }
    }
    // Polar summer (sun always up) / winter (sun never up) at the observer's latitude — Mauna Kea ring masks.
    // Circumpolar tests on the sun's current declination: alt_min>0 ⇔ |φ+δ|>π/2; alt_max<0 ⇔ |φ−δ|>π/2.
    f["polarSummer"] = { if (kotlin.math.abs(observerLatRad + sunRAandDecl(dateInterval()).declination) > PI / 2) 1.0 else 0.0 }
    f["polarWinter"] = { if (kotlin.math.abs(observerLatRad - sunRAandDecl(dateInterval()).declination) > PI / 2) 1.0 else 0.0 }
    // Vernal-equinox / sidereal rotation angle for the zodiac sky wheel (ECAstronomy STDifferenceForDate).
    f["vernalEquinoxAngle"] = {
        val di = dateInterval()
        val utRad = (di - priorUTMidnightForDateInterval(di)) * PI / (12 * 3600)
        convertUTToGSTP03(di) - utRad
    }

    // Rise/set/transit of a planet for the observer's current local day (Miami back subdials).
    fun localComps(t: Double) = utcComponentsFromTimeInterval(t + tzOffsetSeconds)
    fun forDay(kind: Int, planet: Int): Double = when (kind) {
        0 -> planetRiseSetForDay(planet, true, dateInterval(), observerLatRad, observerLonRad, tzOffsetSeconds)
        1 -> planetRiseSetForDay(planet, false, dateInterval(), observerLatRad, observerLonRad, tzOffsetSeconds)
        else -> planetTransitForDay(planet, dateInterval(), observerLatRad, observerLonRad, tzOffsetSeconds)
    }
    for ((prefix, kind) in listOf("rise" to 0, "set" to 1, "transit" to 2)) {
        f["${prefix}OfPlanetForDayTime"] = { a -> forDay(kind, a[0].toInt()) }
        f["${prefix}OfPlanetForDayValid"] = { a -> if (forDay(kind, a[0].toInt()).isNaN()) 0.0 else 1.0 }
        f["${prefix}OfPlanetForDayHour12ValueAngle"] = { a ->
            val t = forDay(kind, a[0].toInt()); if (t.isNaN()) 0.0 else localComps(t).let { ((it.hour % 12) + it.minute / 60.0 + it.seconds / 3600.0) * 2 * PI / 12 }
        }
        f["${prefix}OfPlanetForDayMinuteValueAngle"] = { a ->
            val t = forDay(kind, a[0].toInt()); if (t.isNaN()) 0.0 else localComps(t).let { (it.minute + it.seconds / 60.0) * 2 * PI / 60 }
        }
        f["${prefix}OfPlanetForDayHour24Number"] = { a ->
            val t = forDay(kind, a[0].toInt()); if (t.isNaN()) 0.0 else localComps(t).hour.toDouble()
        }
    }

    // year366 indicator (the 366-slot year fraction, skipping the Feb 29 slot in common years)
    fun year366IndicatorFraction(di: Double): Double {
        val cs = localComponentsFromTimeInterval(di, tzOffsetSeconds)
        val year = cs.year
        val isLeap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val startOfYear = timeIntervalFromLocalComponents(tzOffsetSeconds, cs.era, cs.year, 1, 1, 0, 0, 0.0)
        var daysSinceStart = (di - startOfYear) / 86400.0
        if (!isLeap && (cs.month > 2 || (cs.month == 2 && cs.day > 28))) daysSinceStart += 1
        return esFmod(daysSinceStart / 366.0, 1.0)
    }
    f["year366IndicatorAngle"] = { year366IndicatorFraction(dateInterval()) * 2 * PI }

    fun timeOfClosestSunEclipticLongitude(targetSunLong: Double, tryDate: Double): Double {
        val sunLong = sunEclipticLongitudeForDate(tryDate)
        val howFarAway = targetSunLong - sunLong
        val delta = when {
            howFarAway >= 0 -> if (howFarAway >= PI) howFarAway - 2 * PI else howFarAway
            howFarAway >= -PI -> howFarAway
            else -> howFarAway + 2 * PI
        }
        val secInTropicalYear = 3600.0 * 24 * 365.2422
        return tryDate + delta * secInTropicalYear / (2 * PI)
    }
    f["closestSunEclipticLongitudeQuarter366IndicatorAngle"] = { a ->
        val targetSunLong = a[0] * PI / 2
        var t = timeOfClosestSunEclipticLongitude(targetSunLong, dateInterval())
        t = timeOfClosestSunEclipticLongitude(targetSunLong, t)
        t = timeOfClosestSunEclipticLongitude(targetSunLong, t)
        t = timeOfClosestSunEclipticLongitude(targetSunLong, t)
        year366IndicatorFraction(t) * 2 * PI
    }

    // Eclipse (solar/lunar classification + abstract 0..3 separation).
    f["eclipseSeparation"] = { calculateEclipse(dateInterval(), observerLatRad, observerLonRad).abstractSeparation }
    f["eclipseKind"] = {
        // The kind wheel has a single "none" slot, so collapse NoneSolar(0)/NoneLunar(1) → 0.
        val v = calculateEclipse(dateInterval(), observerLatRad, observerLonRad).eclipseKind
        (if (v > 0) v - 1 else v).toDouble()
    }

    // --- Geocentric ecliptic coordinates / lunar geometry (ChandraII back) ---
    fun jcOf(di: Double) = julianCenturiesSince2000EpochForDateInterval(di).julianCenturiesSince2000Epoch

    // Ecliptic longitude/latitude of a body (radians). Ported from ESAstronomy planetEclipticLongitude/
    // Latitude; the Sun/Moon take their own series (the WB planet series is Mercury..Saturn only).
    f["ELongitudeOfPlanet"] = { a ->
        when (val p = a[0].toInt()) {
            PLANET_SUN -> sunEclipticLongitudeForDate(dateInterval())
            PLANET_MOON -> moonRAAndDecl(dateInterval()).moonEclipticLongitude
            else -> wbPlanetApparentPosition(p, jcOf(dateInterval()) / 100).apparentLongitude
        }
    }
    f["ELatitudeOfPlanet"] = { a ->
        when (val p = a[0].toInt()) {
            PLANET_SUN -> 0.0   // the Sun defines the ecliptic
            PLANET_MOON -> moonEclipticLatitude(dateInterval())
            else -> wbPlanetApparentPosition(p, jcOf(dateInterval()) / 100).apparentLatitude
        }
    }
    // Geocentric distance in AU (ChandraII compares the Moon's against apogee/perigee).
    f["distanceFromEarthOfPlanet"] = { a -> distanceOfPlanetInAU(a[0].toInt(), jcOf(dateInterval()), WB_FULL) }

    f["lunarAscendingNodeLongitude"] = { wbMoonAscendingNodeLongitude(jcOf(dateInterval())) }

    // Sun–Moon elongation: the eclipse "angular separation" near new moon, or π minus it near full.
    // Ported from ECVirtualMachineOps moonElongation (solar-leaning eclipse kinds → separation).
    f["moonElongation"] = {
        val e = calculateEclipse(dateInterval(), observerLatRad, observerLonRad)
        val solar = e.eclipseKind == ECLIPSE_NONE_SOLAR || e.eclipseKind in ECLIPSE_SOLAR_NOT_UP..ECLIPSE_TOTAL_SOLAR
        if (solar) e.angularSeparation else PI - e.angularSeparation
    }

    // Moon's Δ ecliptic longitude (moon − sun, 0..2π) at local midnight today + deltaDay days. This is
    // the moon-age value; ported from moonDeltaEclipticLongitudeAtDeltaDay (the DEL wedge ring).
    fun localMidnightToday(): Double {
        val cs = localComponents()
        return timeIntervalFromLocalComponents(tzOffsetSeconds, cs.era, cs.year, cs.month, cs.day, 0, 0, 0.0)
    }
    f["moonDeltaEclipticLongitudeAtDeltaDay"] = { a -> moonAge(localMidnightToday() + a[0] * 24 * 3600).age }

    // Day of year, 0-based (Jan 1 = 0). Ported from ECWatchTime dayOfYearNumberUsingEnv (rint of the
    // day count from local Jan 1 midnight).
    f["dayOfYearNumber"] = {
        val cs = localComponents()
        val jan1 = timeIntervalFromLocalComponents(tzOffsetSeconds, cs.era, cs.year, 1, 1, 0, 0, 0.0)
        Math.round((localMidnightToday() - jan1) / (24 * 3600)).toDouble()
    }

    // Time-unit constants (for update intervals; cheap to provide).
    f["years"] = { 365.25 * 86400 }
    f["weeks"] = { 7.0 * 86400 }
    f["days"] = { 86400.0 }
    f["hours"] = { 3600.0 }
    f["minutes"] = { 60.0 }
    f["seconds"] = { 1.0 }

    // --- Battery: overridden by the Android :app layer (BatteryManager). ---
    // Engine defaults: unsupported, so Milano's power-reserve dial/hand collapse to radius 0.
    f.putIfAbsent("batteryLevelSupported") { 0.0 }
    f.putIfAbsent("batteryLevel") { -1.0 }
}
