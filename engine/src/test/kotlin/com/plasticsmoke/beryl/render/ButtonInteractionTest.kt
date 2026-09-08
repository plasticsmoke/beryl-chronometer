package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.astro.ActionHooks
import com.plasticsmoke.beryl.astro.registerActionFunctions
import com.plasticsmoke.beryl.astro.registerWatchFunctions
import com.plasticsmoke.beryl.expr.createDefaultEnvironment
import com.plasticsmoke.beryl.watch.ModeFilter
import com.plasticsmoke.beryl.watch.parseWatchXml
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Interaction-layer stage 1: button hit-testing + action execution, piloted on Miami's back —
 * the planet selector. selector2 (the physical side button, enabled='always') cycles
 * body/bodySlot and persists via saveBody(); the 'sel but'/'seli but' hit rects are stem-out
 * gated (iOS default ECButtonEnabledStemOutOnly).
 */
class ButtonInteractionTest {

    private fun resourceText(name: String) =
        ButtonInteractionTest::class.java.getResourceAsStream(name)!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun backEnv(savedBodies: MutableList<Double> = mutableListOf()): Pair<com.plasticsmoke.beryl.watch.Watch, com.plasticsmoke.beryl.expr.Environment> {
        val xml = resourceText("/Miami.xml")
        val watch = parseWatchXml(xml, ModeFilter.BACK)
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        env.variables["body"] = 7.0  // Saturn, the render-test default
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:09:36Z").toEpochMilli()
        }
        registerActionFunctions(env, ActionHooks(saveBody = { savedBodies.add(it) }))
        applyInits(watch, env)
        return watch to env
    }

    private val chrome by lazy { PhoneFrame.loadChrome(resourceText("/Miami.xml")) }

    @Test fun selectorButtonCyclesBodyAndPersists() {
        val saved = mutableListOf<Double>()
        val (watch, env) = backEnv(saved)

        // selector2 rests extended: center (bx+mx, by+my) ≈ (135.7, 66.2), mirrored to the
        // left side on the back (flipXOnBack).
        val hit = findButtonAt(watch, env, chrome, -135.7, 66.2)
        assertEquals("selector2", hit?.name, "the side selector button should be under the tap")
        assertEquals("always", hit!!.enabled)
        assertTrue(!hit.repeats, "selector2 is ECPartDoesNotRepeat")

        assertTrue(executeButtonAction(hit, env))
        assertEquals(6.0, env.variables["body"], "Saturn(7) cycles down to Jupiter(6)")
        assertEquals(4.0, env.variables["bodySlot"], "selector slot 5 -> 4")
        assertEquals(listOf(6.0), saved, "saveBody hook receives the new body")

        // Cycle boundary: Sun(0) wraps to Saturn(7), slot 6 -> 5.
        env.variables["body"] = 0.0
        env.variables["bodySlot"] = 6.0
        executeButtonAction(hit, env)
        assertEquals(7.0, env.variables["body"])
        assertEquals(5.0, env.variables["bodySlot"])
    }

    @Test fun pressFeedbackRetractsSelector() {
        val (watch, env) = backEnv()
        // While held, motion='!thisButtonPressed()' evaluates 0 — the button depresses to
        // (bx, by) ≈ (128.5, 62.7). The hit rect must track it (iOS re-evaluates offsets too).
        env.pressedButton = "selector2"
        val hit = findButtonAt(watch, env, chrome, -128.5, 62.7)
        assertEquals("selector2", hit?.name)
    }

    @Test fun stemOutOnlyButtonsAreGatedByManualSet() {
        val (watch, env) = backEnv()
        // 'sel but' (the selector text row, rect 54×14 at (-27, selRadius-13=116)) has no
        // enabled attr -> iOS default stem-out only. Parked stem => not hittable.
        assertNull(findButtonAt(watch, env, chrome, 0.0, 123.0))

        env.functions["manualSet"] = { 1.0 }
        val hit = findButtonAt(watch, env, chrome, 0.0, 123.0)
        assertEquals("sel but", hit?.name)

        // Rect buttons expand to the 40-unit minimum: (0, 138) is outside the raw 14-unit-tall
        // rect (116..130) but inside the expanded one (103..143).
        assertEquals("sel but", findButtonAt(watch, env, chrome, 0.0, 138.0)?.name)
    }

    @Test fun advanceButtonsExecuteAsNoOpsWithoutError() {
        val (watch, env) = backEnv()
        env.functions["manualSet"] = { 1.0 }
        // 'rise but' -> advanceToRiseOfPlanetForDay(body): a stage-2 no-op today, but the
        // expression must evaluate cleanly (no Undefined function).
        val riseBut = findButtonAt(
            watch, env, chrome,
            -55.0 * Math.cos(Math.PI / 12), -55.0 * Math.sin(Math.PI / 12),
        )
        assertEquals("rise but", riseBut?.name)
        assertTrue(executeButtonAction(riseBut!!, env))
    }

    @Test fun repeatStrategyParsesAllTiers() {
        // Terra: explicit SlowlyOnly on the ring-rotate buttons, iOS default (absent attr =
        // AcceleratesOnce) elsewhere; Miami back: an explicit AcceleratesTwice year button.
        val terra = parseWatchXml(resourceText("/Terra.xml"), ModeFilter.FRONT)
        val buttons = HashMap<String, com.plasticsmoke.beryl.watch.ButtonPart>()
        fun collect(parts: List<com.plasticsmoke.beryl.watch.WatchPart>) {
            for (p in parts) when (p) {
                is com.plasticsmoke.beryl.watch.ButtonPart -> buttons[p.name] = p
                is com.plasticsmoke.beryl.watch.StaticPart -> collect(p.children)
                else -> {}
            }
        }
        collect(terra.parts)
        val cw = buttons.getValue("rotate cw")
        assertTrue(cw.repeats)
        assertEquals(com.plasticsmoke.beryl.watch.REPEAT_SLOWLY_ONLY, cw.repeatStrategy)

        val miami = parseWatchXml(resourceText("/Miami.xml"), ModeFilter.BACK)
        buttons.clear()
        collect(miami.parts)
        val year = buttons.getValue("adv yearb")
        assertTrue(year.repeats)
        assertEquals(com.plasticsmoke.beryl.watch.REPEAT_ACCELERATES_TWICE, year.repeatStrategy)
        val day = buttons.getValue("adv dayb")
        assertTrue(day.repeats, "absent attr = iOS default AcceleratesOnce")
        assertEquals(com.plasticsmoke.beryl.watch.REPEAT_ACCELERATES_ONCE, day.repeatStrategy)
    }
}
