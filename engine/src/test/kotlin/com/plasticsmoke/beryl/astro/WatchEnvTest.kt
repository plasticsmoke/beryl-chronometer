package com.plasticsmoke.beryl.astro

import com.plasticsmoke.beryl.expr.Environment
import com.plasticsmoke.beryl.expr.createDefaultEnvironment
import com.plasticsmoke.beryl.expr.evaluateExpression
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the watch-environment functions Milano needs, at fixed instants. Clock-hand angles are
 * checked at clean times and cross-checked against java.time decomposition; calendar functions
 * are cross-checked against java.time so nothing is asserted from a hand-computed constant.
 */
class WatchEnvTest {

    private fun envAt(iso: String, tzOffsetSec: Double = 0.0): Environment {
        val millis = Instant.parse(iso).toEpochMilli()
        val env = createDefaultEnvironment()
        registerWatchFunctions(env, tzOffsetSec) { millis }
        return env
    }

    private fun call(env: Environment, name: String): Double =
        env.functions[name]!!(DoubleArray(0))

    @Test fun cleanHourAngles() {
        val env = envAt("2026-06-11T03:00:00Z")
        assertEquals(PI / 2, call(env, "hour12ValueAngle"), 1e-9)  // 3 o'clock → quarter turn
        assertEquals(0.0, call(env, "minuteValueAngle"), 1e-9)
        assertEquals(0.0, call(env, "secondValueAngle"), 1e-9)
    }

    @Test fun noonWrapsHourAngleToZero() {
        val env = envAt("2026-06-11T12:00:00Z")
        assertEquals(0.0, call(env, "hour12ValueAngle"), 1e-9)  // 12 % 12 = 0
        assertEquals(12.0, call(env, "hour24Number"))
    }

    @Test fun messyTimeMatchesJavaTimeDecomposition() {
        val iso = "2026-06-11T09:15:30.500Z"
        val env = envAt(iso)
        val z = Instant.parse(iso).atZone(ZoneOffset.UTC)
        val s = z.second + z.nano / 1e9
        val m = z.minute + s / 60.0
        val h = (z.hour % 12) + m / 60.0
        assertEquals(h * 2 * PI / 12, call(env, "hour12ValueAngle"), 1e-9)
        assertEquals(m * 2 * PI / 60, call(env, "minuteValueAngle"), 1e-9)
        assertEquals(s * 2 * PI / 60, call(env, "secondValueAngle"), 1e-9)
        assertEquals(s, call(env, "secondValue"), 1e-9)
        assertEquals(9.0, call(env, "hour24Number"))
        assertEquals(15.0, call(env, "minuteNumber"))
    }

    @Test fun calendarFunctionsAreZeroIndexed() {
        val iso = "2026-06-11T14:00:00Z"
        val env = envAt(iso)
        val z = Instant.parse(iso).atZone(ZoneOffset.UTC)
        assertEquals((z.monthValue - 1).toDouble(), call(env, "monthNumber"))
        assertEquals((z.dayOfMonth - 1).toDouble(), call(env, "dayNumber"))
        assertEquals((z.dayOfWeek.value % 7).toDouble(), call(env, "weekdayNumber"))
        assertEquals(z.year.toDouble(), call(env, "yearNumber"))
        assertEquals(1.0, call(env, "eraNumber"))
    }

    @Test fun timezoneOffsetShiftsLocalDate() {
        // 01:00 UTC at -08:00 → previous calendar day, 17:00 local
        val iso = "2026-06-11T01:00:00Z"
        val tz = -28800.0
        val env = envAt(iso, tz)
        val z = Instant.parse(iso).plusSeconds(tz.toLong()).atZone(ZoneOffset.UTC)
        assertEquals((z.monthValue - 1).toDouble(), call(env, "monthNumber"))
        assertEquals((z.dayOfMonth - 1).toDouble(), call(env, "dayNumber"))
        assertEquals((z.dayOfWeek.value % 7).toDouble(), call(env, "weekdayNumber"))
        assertEquals(z.hour.toDouble(), call(env, "hour24Number"))
    }

    @Test fun milanoHandExpressionsEvaluate() {
        // The real angle expressions from Milano-I.xml must parse + evaluate to finite values.
        val env = envAt("2026-06-11T14:30:45Z")
        assertTrue(evaluateExpression("(monthNumber()+1)*2*pi/26 - pi/2", env).isFinite())
        assertTrue(evaluateExpression("(31-dayNumber())*2*pi/64 + pi/2", env).isFinite())
        assertTrue(evaluateExpression("(7-weekdayNumber())*2*pi/28 + 20*pi/28", env).isFinite())
        assertTrue(evaluateExpression("hour12ValueAngle()", env).isFinite())
        // Power-reserve length collapses to 0 with the engine's default (battery unsupported).
        assertEquals(0.0, evaluateExpression("batteryLevelSupported() ? 60 : 0", env))
        assertEquals(-PI / 6, evaluateExpression("batteryLevel() >= 0 ? batteryLevel()*pi/3-pi/6 : -pi/6", env), 1e-12)
    }
}
