package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.astro.WatchTimeState
import com.plasticsmoke.beryl.astro.registerActionFunctions
import com.plasticsmoke.beryl.astro.registerTimeFunctions
import com.plasticsmoke.beryl.astro.registerWatchFunctions
import com.plasticsmoke.beryl.expr.createDefaultEnvironment
import com.plasticsmoke.beryl.watch.ButtonPart
import com.plasticsmoke.beryl.watch.ModeFilter
import com.plasticsmoke.beryl.watch.QHandPart
import com.plasticsmoke.beryl.watch.parseWatchXml
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Interaction stage 2, the unified part finder: hands with a time `kind` become grabbable when
 * the stem is out and compete with buttons by grabPrio (iOS ECGLPartFinder). The hand's hit
 * region is its unrotated bounds tested against the touch point rotated back by the current
 * angle (iOS transformPointWithinView).
 */
class HandGrabTest {

    // A minute hand pointing at 3 o'clock (angle=pi/2) over a rise/set-style corner button.
    private val xml = """
        <watch name='T' beatsPerSecond='1' faceWidth='320'>
          <Qhand  name='min'  x='0'  y='0'   kind='minuteKind' type='rect' length='100' width='2'
                  tail='0' update='.2' angle='pi/2' grabPrio='1' strokeColor='white' fillColor='white' />
          <button name='but'  x='60' y='-40' w='40' h='40' action='reset()' enabled='always' />
        </watch>
    """.trimIndent()

    private fun build(stemOut: Boolean): Triple<com.plasticsmoke.beryl.watch.Watch, com.plasticsmoke.beryl.expr.Environment, WatchTimeState> {
        val watch = parseWatchXml(xml, ModeFilter.FRONT)
        val env = createDefaultEnvironment()
        val time = WatchTimeState(systemNow = { Instant.parse("2026-07-04T17:10:30Z").toEpochMilli() })
        registerWatchFunctions(env, -7 * 3600.0, 0.66, -2.14) { time.displayedNow() }
        registerActionFunctions(env)
        registerTimeFunctions(env, time)
        if (stemOut) time.stop()
        return Triple(watch, env, time)
    }

    @Test fun handsAreOnlyGrabbableWithTheStemOut() {
        val (watch, env, _) = build(stemOut = false)
        // Stem in: the hand is inert; the point over its shaft (and inside the button)
        // falls through to the button.
        val hit = findPartAt(watch, env, emptyMap(), 80.0, -10.0)
        assertTrue(hit is ButtonPart, "stem in -> the button wins, got ${hit?.name}")
    }

    @Test fun grabPrioBeatsButtonsAndRotationTracksTheHand() {
        val (watch, env, _) = build(stemOut = true)
        // Stem out: the same point lies on the hand's shaft (it points at 3 o'clock), and the
        // hand's grabPrio=1 outranks the button's 0.
        val hit = findPartAt(watch, env, emptyMap(), 80.0, -10.0)
        assertTrue(hit is QHandPart, "stem out -> the hand wins, got ${hit?.name}")
        assertEquals("min", hit!!.name)

        // The hit region rotated WITH the hand: straight up (where the hand isn't) misses it.
        assertNull(findPartAt(watch, env, emptyMap(), 0.0, 80.0))

        // findButtonAt keeps its buttons-only contract: the hand covering the point yields null.
        assertNull(findButtonAt(watch, env, emptyMap(), 80.0, -10.0))
    }

    @Test fun buttonStillWinsWhereTheHandIsNot() {
        val (watch, env, _) = build(stemOut = true)
        // Below the shaft's 40-unit expanded band (|local x| > 20) but inside the button.
        assertEquals("but", findPartAt(watch, env, emptyMap(), 90.0, -35.0)?.name)
    }
}
