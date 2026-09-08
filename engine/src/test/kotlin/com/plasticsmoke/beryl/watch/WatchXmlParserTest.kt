package com.plasticsmoke.beryl.watch

import com.plasticsmoke.beryl.expr.FunctionCall
import com.plasticsmoke.beryl.expr.NumberLiteral
import com.plasticsmoke.beryl.expr.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the watch XML parser, mirroring the relevant cases from chronometer-web
 * `src/watch/__tests__/xml-parser.test.ts` and validating against the real Milano-I.xml.
 */
class WatchXmlParserTest {

    /** All parts flattened, descending into Static containers. */
    private fun flatten(parts: List<WatchPart>): List<WatchPart> =
        parts.flatMap { if (it is StaticPart) listOf(it) + flatten(it.children) else listOf(it) }

    private fun byName(parts: List<WatchPart>, name: String): WatchPart? =
        flatten(parts).firstOrNull { it.name == name }

    private fun milanoXml(): String =
        WatchXmlParserTest::class.java.getResourceAsStream("/Milano-I.xml")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    // --- Small examples ---

    @Test fun parsesMinimalWatch() {
        val xml = """<?xml version="1.0"?>
            <watch name='Test' beatsPerSecond='10'>
              <init expr='r=100' />
              <QDial name='d1' x='0' y='0' radius='r' modes='front' />
            </watch>"""
        val watch = parseWatchXml(xml, ModeFilter.FRONT)
        assertEquals("Test", watch.name)
        assertEquals(10, watch.beatsPerSecond)
        assertEquals(1, watch.initExprs.size)
        assertEquals(parse("r=100"), watch.initExprs[0])
        assertEquals(1, watch.parts.size)
        assertTrue(watch.parts[0] is QDialPart)
    }

    @Test fun defaultsToFrontWhenNoModes() {
        val xml = """<?xml version="1.0"?>
            <watch name='Test' beatsPerSecond='1'>
              <QDial name='d1' x='0' y='0' radius='10' />
            </watch>"""
        assertEquals(1, parseWatchXml(xml, ModeFilter.FRONT).parts.size)
        assertEquals(0, parseWatchXml(xml, ModeFilter.BACK).parts.size)
    }

    @Test fun throwsOnMissingWatchElement() {
        assertFailsWith<WatchParseException> { parseWatchXml("""<?xml version="1.0"?><root/>""", ModeFilter.FRONT) }
    }

    @Test fun modeFilteringAndNameRefNameFallback() {
        val xml = """<?xml version="1.0"?>
            <watch name='T' beatsPerSecond='1'>
              <QDial name='frontOnly' radius='1' modes='front' />
              <QDial name='backOnly' radius='1' modes='back' />
              <QDial name='both' radius='1' modes='front|back' />
              <QDial name='everywhere' radius='1' modes='all' />
              <QDial refName='viaRefName' radius='1' modes='front' />
            </watch>"""
        val front = parseWatchXml(xml, ModeFilter.FRONT)
        assertNotNull(byName(front.parts, "frontOnly"))
        assertNull(byName(front.parts, "backOnly"))
        assertNotNull(byName(front.parts, "both"))
        assertNotNull(byName(front.parts, "everywhere"))
        assertNotNull(byName(front.parts, "viaRefName"))  // name falls back to refName

        val back = parseWatchXml(xml, ModeFilter.BACK)
        assertNull(byName(back.parts, "frontOnly"))
        assertNotNull(byName(back.parts, "backOnly"))
        assertNotNull(byName(back.parts, "both"))
        assertNotNull(byName(back.parts, "everywhere"))
    }

    // --- Real Milano face ---

    @Test fun parsesMilanoTopLevel() {
        val w = parseWatchXml(milanoXml(), ModeFilter.FRONT)
        assertEquals("Milano I", w.name)
        assertEquals(5, w.beatsPerSecond)
        assertEquals(255.0, w.faceWidth)
        assertEquals("rgb(190,190,190)", w.bezelColor)
        assertEquals(7, w.initExprs.size)  // 7 <init> blocks
    }

    @Test fun parsesMilanoStructure() {
        val w = parseWatchXml(milanoXml(), ModeFilter.FRONT)
        // Top level: one Static('front') + 7 QHands (4 retrograde/power + hr/min/sec)
        assertEquals(8, w.parts.size)
        assertEquals(7, w.parts.filterIsInstance<QHandPart>().size)

        val front = w.parts.filterIsInstance<StaticPart>().single()
        assertEquals("front", front.name)
        assertEquals(9, front.children.size)

        val all = flatten(w.parts)
        assertEquals(7, all.filterIsInstance<QDialPart>().size)   // all inside the static
        assertEquals(1, all.filterIsInstance<QRectPart>().size)   // maskRect
        assertEquals(1, all.filterIsInstance<ImagePart>().size)   // berry
    }

    @Test fun parsesMilanoPartDetails() {
        val w = parseWatchXml(milanoXml(), ModeFilter.FRONT)

        val berry = byName(w.parts, "berry") as ImagePart
        assertEquals("../partsBin/berryWhite.png", berry.src)

        val sec = byName(w.parts, "sec") as QHandPart
        assertEquals("tri", sec.handType)
        assertEquals("secondKind", sec.kind)
        assertEquals(parse("secondValueAngle()"), sec.angle)

        val hr = byName(w.parts, "hr") as QHandPart
        assertEquals("hour12Kind", hr.kind)
        assertEquals(parse("hour12ValueAngle()"), hr.angle)

        // The retrograde month hand's angle is a real expression that parses to a BinaryOp.
        val mon = byName(w.parts, "mon hn") as QHandPart
        assertNotNull(mon.angle)

        // The 'time' dial is a text dial with the expected attributes.
        val timeDial = byName(w.parts, "time") as QDialPart
        assertEquals("upright", timeDial.orientation)
        assertEquals("12", timeDial.text)
        // Geometry is snapshotted at parse time against the init state (iOS boundsCheck): timR=144.
        assertEquals(NumberLiteral(144.0), timeDial.radius)

        // monthNumber() appears in the retrograde month hand angle (sanity on AST content).
        val monAngle = mon.angle!!
        assertTrue(astMentionsFunction(monAngle, "monthNumber"))
    }

    private fun astMentionsFunction(node: com.plasticsmoke.beryl.expr.ASTNode, fn: String): Boolean = when (node) {
        is FunctionCall -> node.name == fn || node.args.any { astMentionsFunction(it, fn) }
        is com.plasticsmoke.beryl.expr.BinaryOp -> astMentionsFunction(node.left, fn) || astMentionsFunction(node.right, fn)
        is com.plasticsmoke.beryl.expr.UnaryOp -> astMentionsFunction(node.operand, fn)
        is com.plasticsmoke.beryl.expr.Ternary -> astMentionsFunction(node.condition, fn) ||
            astMentionsFunction(node.consequent, fn) || astMentionsFunction(node.alternate, fn)
        is com.plasticsmoke.beryl.expr.ExpressionList -> node.expressions.any { astMentionsFunction(it, fn) }
        is com.plasticsmoke.beryl.expr.Assignment -> astMentionsFunction(node.value, fn)
        else -> false
    }
}
