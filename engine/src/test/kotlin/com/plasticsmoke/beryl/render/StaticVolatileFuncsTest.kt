package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.watch.ModeFilter
import com.plasticsmoke.beryl.watch.parseWatchXml
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the property the app's act-time cache invalidation relies on (WatchView.runPartAction):
 * no face bakes a non-math function into its static content, so a non-pure button action
 * (advanceDay(), stemOut(), …) can never change what the statics draw — acts skip the
 * 30-80 ms rebake and repeat-while-held runs at the full iOS cadence (measured 100 ms steps).
 *
 * If a future face legitimately bakes volatile content, it just loses the fast path (its
 * staticVolatileFuncRefs goes non-empty and acts rebake as before) — update this test to
 * list it explicitly rather than weakening the property for everyone.
 */
class StaticVolatileFuncsTest {

    @Test
    fun noFaceBakesVolatileFunctionsIntoStatics() {
        // Geneva-I/Milano-I are unshipped web-port smoke fixtures (Milano-I bakes
        // batteryLevelSupported — a face like it would simply lose the no-rebake fast path).
        val fixtures = setOf("Geneva-I.xml", "Milano-I.xml")
        val xmls = File("src/test/resources")
            .listFiles { f -> f.extension == "xml" && f.name !in fixtures }!!
        assertTrue(xmls.size >= 25, "expected the staged face XMLs, found ${xmls.size}")
        val offenders = StringBuilder()
        for (file in xmls.sortedBy { it.name }) {
            for (mode in listOf(ModeFilter.FRONT, ModeFilter.NIGHT, ModeFilter.BACK)) {
                val watch = try {
                    parseWatchXml(file.readText(), mode)
                } catch (e: Exception) {
                    continue // a fixture without this mode; render tests cover parse health
                }
                val volatileFuncs = staticVolatileFuncRefs(watch)
                if (volatileFuncs.isNotEmpty()) {
                    offenders.append("${file.name} [$mode]: $volatileFuncs\n")
                }
            }
        }
        assertTrue(offenders.isEmpty(), "static content reads volatile functions:\n$offenders")
    }
}
