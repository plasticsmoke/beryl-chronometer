package com.plasticsmoke.beryl.watch

import com.plasticsmoke.beryl.expr.ASTNode
import com.plasticsmoke.beryl.expr.Environment
import com.plasticsmoke.beryl.expr.NumberLiteral
import com.plasticsmoke.beryl.expr.createDefaultEnvironment
import com.plasticsmoke.beryl.expr.evaluate
import com.plasticsmoke.beryl.expr.parse
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses watch-face XML into the [Watch] model, ported from chronometer-web
 * `src/watch/xml-parser.ts`. Parts are filtered by mode (front/back/night) at parse time.
 *
 * Uses JDK DOM (javax.xml), available on both the JVM and Android.
 */

enum class ModeFilter(val key: String) {
    FRONT("front"),
    BACK("back"),
    NIGHT("night"),
}

class WatchParseException(message: String) : RuntimeException(message)

/**
 * Running variable state accumulated as <init> blocks are evaluated in document order. Geometry
 * attributes (x/y/radius/…) are snapshotted against this state at parse time — matching iOS, where
 * `boundsCheck` evaluates those eagerly so a later mode's <init> can't retroactively move an earlier
 * mode's parts. Live attributes (angle, etc.) reference functions undefined here, so they throw and
 * stay lazy, to be evaluated per-frame against the final init state. Parsing is single-threaded.
 */
private var parseEnv: Environment? = null

fun parseWatchXml(xmlText: String, mode: ModeFilter): Watch = try {
    parseEnv = createDefaultEnvironment()
    parseWatchXmlInner(xmlText, mode)
} finally {
    parseEnv = null
}

private fun parseWatchXmlInner(xmlText: String, mode: ModeFilter): Watch {
    val doc = run {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            // Harden against XXE — face XML has no external entities/DTDs.
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        factory.newDocumentBuilder().parse(InputSource(StringReader(xmlText)))
    }

    val watchEl = findElement(doc.documentElement, "watch")
        ?: throw WatchParseException("No <watch> element found in XML")

    inheritWindowModes(watchEl)

    val initExprs = ArrayList<ASTNode>()
    val parts = ArrayList<WatchPart>()

    val watch = Watch(
        name = attr(watchEl, "name") ?: "unknown",
        beatsPerSecond = attrInt(watchEl, "beatsPerSecond", 0),
        faceWidth = attrDouble(watchEl, "faceWidth", 290.0),
        bezelColor = attr(watchEl, "bezelColor") ?: "",
        bezelNoonMark = attr(watchEl, "bezelNoonMark") == "true",
        worldTimeRing = attr(watchEl, "worldTimeRing") == "1",
        worldTimeSubdials = attr(watchEl, "worldTimeSubdials") == "1",
        planetSelector = attr(watchEl, "planetSelector") == "1",
        wadokei = attr(watchEl, "wadokei") == "1",
        numEnvironments = attrInt(watchEl, "numEnvironments", 1),
        maxSeparateLoc = attrInt(watchEl, "maxSeparateLoc", 1),
        calendarWeekStart = attr(watchEl, "calendarWeekStart") == "1",
        urlAbbrev = attr(watchEl, "urlAbbrev") ?: "",
        initExprs = initExprs,
        parts = parts,
    )

    for (child in childElements(watchEl)) {
        processElement(child, mode, initExprs, parts)
    }

    return watch
}

/**
 * iOS windows carry NO mode mask of their own: a window "applies to the next element to be
 * parsed" (ECWatchDefinitionManager parserDidWindowStart — no modes read) and renders exactly
 * when that TARGET renders; buttons are passed over (parserDidButtonStart). Our per-mode parse
 * filtered windows by their own `modes` attr with the front-only default, silently dropping
 * every night/back window the canonical XML leaves unmarked — the recurring "no-modes gotcha"
 * (term covers, AtlantisIV's blank night, the 6-o'clock f/r case plaques) was this divergence.
 * Windows without an explicit modes attr inherit their target's; explicit attrs stay honored
 * (the per-face patches added over time remain correct).
 */
private fun inheritWindowModes(el: Element, enclosingModes: String? = null) {
    val children = childElements(el)
    for ((i, child) in children.withIndex()) {
        when (child.tagName.lowercase()) {
            "static" -> inheritWindowModes(child, attr(child, "modes") ?: enclosingModes)
            "window" -> if (!child.hasAttribute("modes")) {
                var j = i + 1
                while (j < children.size) {
                    val t = children[j].tagName.lowercase()
                    if (t != "window" && t != "button") break
                    j++
                }
                // Inherit the target's modes; a trailing window (last child of a static)
                // rides with the enclosing static's modes instead.
                val modes = children.getOrNull(j)?.let { attr(it, "modes") } ?: enclosingModes
                modes?.let { child.setAttribute("modes", it) }
            }
        }
    }
}

private fun processElement(
    el: Element,
    mode: ModeFilter,
    initExprs: MutableList<ASTNode>,
    parts: MutableList<WatchPart>,
) {
    when (el.tagName.lowercase()) {
        "init" -> {
            // Always collect init blocks (they define variables needed by all modes).
            val exprStr = attr(el, "expr")
            if (exprStr != null) {
                runCatching {
                    val ast = parse(exprStr)
                    initExprs.add(ast)
                    // Apply into the running parse state so later geometry snapshots see it.
                    parseEnv?.let { runCatching { evaluate(ast, it) } }
                }
            }
        }

        "atlas" -> { /* texture-atlas hint, not a renderable part */ }

        "static" -> processStatic(el, mode, initExprs, parts)

        "qdial" -> if (matchesMode(el, mode)) parts.add(parseQDial(el))
        "qhand", "hand" -> if (matchesMode(el, mode)) parts.add(parseQHand(el))
        "image" -> if (matchesMode(el, mode)) parts.add(parseImage(el))
        "window" -> if (matchesMode(el, mode)) parts.add(parseWindow(el))
        "qrect" -> if (matchesMode(el, mode)) parts.add(parseQRect(el))
        "terminator" -> if (matchesMode(el, mode)) parts.add(parseTerminator(el))
        "swheel", "qwheel", "twheel" -> if (matchesMode(el, mode)) {
            val variant = when (el.tagName.lowercase()) { "qwheel" -> "QWheel"; "twheel" -> "TWheel"; else -> "SWheel" }
            parts.add(parseWheel(el, variant))
        }
        "qwedge" -> if (matchesMode(el, mode)) parts.add(parseQWedge(el))
        "qtext" -> if (matchesMode(el, mode)) parts.add(parseQText(el))
        "qdaynightring" -> if (matchesMode(el, mode)) parts.add(parseQDayNightRing(el))
        "analemma" -> if (matchesMode(el, mode)) parts.add(parseAnalemma(el))
        "eotdial" -> if (matchesMode(el, mode)) parts.add(parseEotDial(el))
        "calendarrowcover" -> if (matchesMode(el, mode)) parts.add(parseCalendarRowCover(el))
        "calendarheader" -> if (matchesMode(el, mode)) parts.add(parseCalendarHeader(el))
        // Buttons render as static chrome (stem/side buttons/reset) at their rest position; the
        // band/case art drawn after them in document order covers all but the protruding edge.
        // src-less buttons are pure hit rects (w/h attrs) — invisible, but tappable.
        "button" -> if (matchesMode(el, mode)) parts.add(parseButton(el, mode))

        // Any unknown tag is skipped, matching the reference.
        else -> { /* skip */ }
    }
}

private fun processStatic(
    el: Element,
    mode: ModeFilter,
    initExprs: MutableList<ASTNode>,
    parts: MutableList<WatchPart>,
) {
    // Static groups are mode-filtered as a unit.
    if (!matchesMode(el, mode)) return

    val staticPart = StaticPart(
        name = attr(el, "name") ?: "",
        x = attrExpr(el, "x"),
        y = attrExpr(el, "y"),
        modes = attr(el, "modes"),
    )
    for (child in childElements(el)) {
        processElement(child, mode, initExprs, staticPart.children)
    }
    parts.add(staticPart)
}

// --- Individual part parsers ---

private fun parseQDial(el: Element) = QDialPart(
    name = partName(el),
    x = attrExpr(el, "x"), y = attrExpr(el, "y"), modes = attr(el, "modes"),
    radius = attrExpr(el, "radius"),
    radius2 = attrExpr(el, "radius2"),
    clipRadius = attrExpr(el, "clipRadius"),
    orientation = attr(el, "orientation"),
    demiTweak = attrExpr(el, "demiTweak"),
    text = attr(el, "text"),
    fontSize = attrExpr(el, "fontSize"),
    fontName = attr(el, "fontName"),
    bgColor = attrExpr(el, "bgColor"),
    strokeColor = attrExpr(el, "strokeColor"),
    fillColor1 = attrExpr(el, "fillColor1"),
    fillColor2 = attrExpr(el, "fillColor2"),
    marks = attr(el, "marks"),
    markWidth = attrExpr(el, "markWidth"),
    nMarks = attrExpr(el, "nMarks"),
    mSize = attrExpr(el, "mSize"),
    angle = attrExpr(el, "angle"),
    angle0 = attrExpr(el, "angle0"),
    angle1 = attrExpr(el, "angle1"),
    angle2 = attrExpr(el, "angle2"),
    update = attrExpr(el, "update"),
    updateOffset = attrExpr(el, "updateOffset"),
    kind = attr(el, "kind"),
    z = attrExpr(el, "z"),
    thick = attrExpr(el, "thick"),
    animSpeed = attrExpr(el, "animSpeed"),
)

private fun parseQHand(el: Element) = QHandPart(
    name = partName(el),
    x = attrExpr(el, "x"), y = attrExpr(el, "y"), modes = attr(el, "modes"),
    angle = attrExprNoSnapshot(el, "angle"),
    length = attrExpr(el, "length"),
    length2 = attrExpr(el, "length2"),
    width = attrExpr(el, "width"),
    tail = attrExpr(el, "tail"),
    handType = attr(el, "type"),
    strokeColor = attrExpr(el, "strokeColor"),
    fillColor = attrExpr(el, "fillColor"),
    lineWidth = attrExpr(el, "lineWidth"),
    kind = attr(el, "kind"),
    grabPrio = attr(el, "grabPrio")?.trim()?.toIntOrNull() ?: 0,
    action = attrExprNoSnapshot(el, "action"),
    update = attrExpr(el, "update"),
    updateOffset = attrExpr(el, "updateOffset"),
    z = attrExpr(el, "z"),
    thick = attrExpr(el, "thick"),
    animSpeed = attrExpr(el, "animSpeed"),
    dragAnimationType = attr(el, "dragAnimationType"),
    oLength = attrExpr(el, "oLength"),
    oWidth = attrExpr(el, "oWidth"),
    oTail = attrExpr(el, "oTail"),
    oLineWidth = attrExpr(el, "oLineWidth"),
    oStrokeColor = attrExpr(el, "oStrokeColor"),
    oFillColor = attrExpr(el, "oFillColor"),
    oCenter = attrExpr(el, "oCenter"),
    oRadius = attrExpr(el, "oRadius"),
    oRadiusX = attrExpr(el, "oRadiusX"),
    tFillColor = attrExpr(el, "tFillColor"),
    tStrokeColor = attrExpr(el, "tStrokeColor"),
    tLineWidth = attrExpr(el, "tLineWidth"),
    src = attr(el, "src"),
    xAnchor = attrExpr(el, "xAnchor"),
    yAnchor = attrExpr(el, "yAnchor"),
    offsetRadius = attrExpr(el, "offsetRadius"),
    offsetAngle = attrExprNoSnapshot(el, "offsetAngle"),
    nRays = attrExpr(el, "nRays"),
    text = attr(el, "text"),
    fontSize = attrExpr(el, "fontSize"),
    fontName = attr(el, "fontName"),
    xMotion = attrExprNoSnapshot(el, "xMotion"),
    yMotion = attrExprNoSnapshot(el, "yMotion"),
    alpha = attrExprNoSnapshot(el, "alpha"),
    orientation = attr(el, "orientation"),
    special = attr(el, "special"),
    specialParam = attrExpr(el, "specialParam"),
    envSlot = attrExpr(el, "envSlot"),
    tipRadius = attrExpr(el, "tipRadius"),
    rimOuterRadius = attrExpr(el, "rimOuterRadius"),
    rimInnerRadius = attrExpr(el, "rimInnerRadius"),
    hubRadius = attrExpr(el, "hubRadius"),
    leafRadius = attrExpr(el, "leafRadius"),
    nSpokes = attrExpr(el, "nSpokes"),
    nTeeth = attrExpr(el, "nTeeth"),
    nLeaves = attrExpr(el, "nLeaves"),
    overlay = attr(el, "overlay"),
)

private fun parseImage(el: Element) = ImagePart(
    name = partName(el),
    x = attrExpr(el, "x"), y = attrExpr(el, "y"), modes = attr(el, "modes"),
    src = attr(el, "src"),
    alpha = attrExpr(el, "alpha"),
    scale = attrExpr(el, "scale"),
    special = attr(el, "special"),
    specialParam = attrExpr(el, "specialParam"),
    envSlot = attrExpr(el, "envSlot"),
)

private fun parseQRect(el: Element) = QRectPart(
    name = partName(el),
    x = attrExpr(el, "x"), y = attrExpr(el, "y"), modes = attr(el, "modes"),
    w = attrExpr(el, "w"),
    h = attrExpr(el, "h"),
    bgColor = attrExpr(el, "bgColor"),
    panes = attrExpr(el, "panes"),
)

private fun parseWheel(el: Element, variant: String) = WheelPart(
    name = partName(el),
    x = attrExpr(el, "x"), y = attrExpr(el, "y"), modes = attr(el, "modes"),
    wheelVariant = variant,
    angle = attrExprNoSnapshot(el, "angle"),
    angle1 = attrExpr(el, "angle1"),
    angle2 = attrExpr(el, "angle2"),
    radius = attrExpr(el, "radius"),
    orientation = attr(el, "orientation"),
    text = attr(el, "text"),
    fontSize = attrExpr(el, "fontSize"),
    fontName = attr(el, "fontName"),
    strokeColor = attrExpr(el, "strokeColor"),
    bgColor = attrExpr(el, "bgColor"),
    bgColor2 = attrExpr(el, "bgColor2"),
    update = attrExpr(el, "update"),
    animSpeed = attrExpr(el, "animSpeed"),
    marks = attr(el, "marks"),
    refName = attr(el, "refName"),
    tradius = attrExpr(el, "tradius"),
    tick = attr(el, "tick"),
    kind = attr(el, "kind"),
    action = attrExprNoSnapshot(el, "action"),
    halfAndHalf = attrExpr(el, "halfAndHalf"),
    ticks = attrExpr(el, "ticks"),
    tickWidth = attrExpr(el, "tickWidth"),
    calendar = attr(el, "calendar"),
    calendarStartDay = attrExpr(el, "calendarStartDay"),
    calendarWeekendColor = attrExpr(el, "calendarWeekendColor"),
    z = attrExpr(el, "z"),
)

private fun parseCalendarRowCover(el: Element) = CalendarRowCoverPart(
    name = partName(el),
    x = attrExpr(el, "x"), y = attrExpr(el, "y"), modes = attr(el, "modes"),
    coverType = attr(el, "coverType"),
    fontName = attr(el, "fontName"),
    fontSize = attrExpr(el, "fontSize"),
    fontColor = attrExpr(el, "fontColor"),
    bgColor = attrExpr(el, "bgColor"),
    calendarRadius = attrExpr(el, "calendarRadius"),
    z = attrExpr(el, "z"),
)

private fun parseCalendarHeader(el: Element) = CalendarHeaderPart(
    name = partName(el),
    x = attrExpr(el, "x"), y = attrExpr(el, "y"), modes = attr(el, "modes"),
    weekdayStart = attr(el, "weekdayStart")?.toIntOrNull() ?: 0,
    weekdayColor = attrExpr(el, "weekdayColor"),
    weekendColor = attrExpr(el, "weekendColor"),
    fontName = attr(el, "fontName"),
    fontSize = attrExpr(el, "fontSize"),
)

private fun parseQWedge(el: Element) = QWedgePart(
    name = partName(el),
    x = attrExpr(el, "x"), y = attrExpr(el, "y"), modes = attr(el, "modes"),
    outerRadius = attrExpr(el, "outerRadius"),
    innerRadius = attrExpr(el, "innerRadius"),
    angleSpan = attrExpr(el, "angleSpan"),
    angle = attrExprNoSnapshot(el, "angle"),
    strokeColor = attrExpr(el, "strokeColor"),
    fillColor = attrExpr(el, "fillColor"),
    borderWidth = attrExpr(el, "borderWidth"),
    update = attrExpr(el, "update"),
    offsetRadius = attrExpr(el, "offsetRadius"),
    offsetAngle = attrExprNoSnapshot(el, "offsetAngle"),
    animSpeed = attrExpr(el, "animSpeed"),
)

private fun parseTerminator(el: Element) = TerminatorPart(
    name = partName(el),
    x = attrExpr(el, "x"), y = attrExpr(el, "y"), modes = attr(el, "modes"),
    radius = attrExpr(el, "radius"),
    leavesPerQuadrant = attrExpr(el, "leavesPerQuadrant"),
    incremental = attrExpr(el, "incremental"),
    leafBorderColor = attrExpr(el, "leafBorderColor"),
    leafFillColor = attrExpr(el, "leafFillColor"),
    leafAnchorRadius = attrExpr(el, "leafAnchorRadius"),
    update = attrExpr(el, "update"),
    updateOffset = attrExpr(el, "updateOffset"),
    phaseAngle = attrExprNoSnapshot(el, "phaseAngle"),
    rotation = attrExprNoSnapshot(el, "rotation"),
)

private fun parseButton(el: Element, mode: ModeFilter): ButtonPart {
    // iOS mirrors buttons horizontally when the watch shows its BACK (ECGLPart flipXOnBack;
    // parser default flipOnBack=1): the whole QUAD mirrors — position AND texture — so the crown
    // stays on the same physical side and off-center button art (e.g. the reset tab's ink) stays
    // tucked under the case edge exactly as on the front. The renderer applies scale(-1,1).
    return ButtonPart(
        name = partName(el),
        x = attrExpr(el, "x"), y = attrExpr(el, "y"), modes = attr(el, "modes"),
        src = attr(el, "src"),
        scale = attrExpr(el, "scale"),
        motion = attrExprNoSnapshot(el, "motion"),
        xMotion = attrExpr(el, "xMotion"),
        yMotion = attrExpr(el, "yMotion"),
        rotation = attrExpr(el, "rotation"),
        opacity = attrExpr(el, "opacity"),
        flippedOnBack = mode == ModeFilter.BACK && (attr(el, "flipOnBack") ?: "1") != "0",
        action = attrExprNoSnapshot(el, "action"),
        w = attrExpr(el, "w"),
        h = attrExpr(el, "h"),
        enabled = attr(el, "enabled"),
        grabPrio = attr(el, "grabPrio")?.trim()?.toIntOrNull() ?: 0,
        immediate = attr(el, "immediate") == "1",
        // iOS default (no attr) = ECPartRepeatsAndAcceleratesOnce, i.e. repeating.
        repeats = attr(el, "repeatStrategy") != "ECPartDoesNotRepeat",
        repeatStrategy = when (attr(el, "repeatStrategy")) {
            "ECPartRepeatsSlowlyOnly" -> REPEAT_SLOWLY_ONLY
            "ECPartRepeatsAndAcceleratesTwice" -> REPEAT_ACCELERATES_TWICE
            else -> REPEAT_ACCELERATES_ONCE
        },
        expanded = attr(el, "expanded") == "1",
    )
}

private fun parseQText(el: Element) = QTextPart(
    name = partName(el),
    x = attrExpr(el, "x"), y = attrExpr(el, "y"), modes = attr(el, "modes"),
    text = attr(el, "text"),
    fontSize = attrExpr(el, "fontSize"),
    fontName = attr(el, "fontName"),
    strokeColor = attrExpr(el, "strokeColor"),
    radius = attrExpr(el, "radius"),
    startAngle = attrExpr(el, "startAngle"),
    orientation = attr(el, "orientation"),
)

private fun parseAnalemma(el: Element) = AnalemmaPart(
    name = partName(el),
    x = attrExpr(el, "x"), y = attrExpr(el, "y"), modes = attr(el, "modes"),
    radius = attrExpr(el, "radius"),
    sunRadius = attrExpr(el, "sunRadius"),
    sunFillColor = attrExpr(el, "sunFillColor"),
    sunStrokeColor = attrExpr(el, "sunStrokeColor"),
    channelColor = attrExpr(el, "channelColor"),
    channelWidth = attrExpr(el, "channelWidth"),
    bgSrc = attr(el, "bgSrc"),
    bgRotates = attrExpr(el, "bgRotates"),
    update = attrExpr(el, "update"),
)

private fun parseEotDial(el: Element) = EotDialPart(
    name = partName(el),
    x = attrExpr(el, "x"), y = attrExpr(el, "y"), modes = attr(el, "modes"),
    radius = attrExpr(el, "radius"),
    arcSpan = attrExpr(el, "arcSpan"),
    strokeColor = attrExpr(el, "strokeColor"),
    fontSize = attrExpr(el, "fontSize"),
    titleFontSize = attrExpr(el, "titleFontSize"),
    labelText = attr(el, "labelText"),
    titleYOffset = attrExpr(el, "titleYOffset"),
)

private fun parseQDayNightRing(el: Element) = QDayNightRingPart(
    name = partName(el),
    x = attrExpr(el, "x"), y = attrExpr(el, "y"), modes = attr(el, "modes"),
    outerRadius = attrExpr(el, "outerRadius"),
    innerRadius = attrExpr(el, "innerRadius"),
    numWedges = attrExpr(el, "numWedges"),
    planetNumber = attrExpr(el, "planetNumber"),
    strokeColor = attrExpr(el, "strokeColor"),
    fillColor = attrExpr(el, "fillColor"),
    timeBase = attr(el, "timeBase"),
    masterOffset = attrExpr(el, "masterOffset"),
    update = attrExpr(el, "update"),
    envSlot = attrExpr(el, "envSlot"),
)

private fun parseWindow(el: Element) = WindowPart(
    name = partName(el),
    x = attrExpr(el, "x"), y = attrExpr(el, "y"), modes = attr(el, "modes"),
    w = attrExpr(el, "w"),
    h = attrExpr(el, "h"),
    windowType = attr(el, "type"),
    border = attrExpr(el, "border"),
    strokeColor = attrExpr(el, "strokeColor"),
    shadowOpacity = attrExpr(el, "shadowOpacity"),
    shadowSigma = attrExpr(el, "shadowSigma"),
    shadowOffset = attrExpr(el, "shadowOffset"),
    shadowOffsetX = attrExpr(el, "shadowOffsetX"),
)

// --- Helpers ---

/** Attribute value, trimmed; null when the attribute is absent. */
private fun attr(el: Element, name: String): String? =
    if (el.hasAttribute(name)) el.getAttribute(name).trim() else null

/**
 * Attribute compiled to an AST; null when absent/empty/unparseable. If the expression evaluates to
 * a finite constant against the current parse-time init state (i.e. pure geometry referencing only
 * known vars/math), it's snapshotted to a [NumberLiteral] — matching iOS's eager `boundsCheck`.
 * Expressions touching live functions (astronomy/clock) throw here and stay lazy for per-frame eval.
 */
private fun attrExpr(el: Element, name: String): ASTNode? {
    val value = attr(el, name)
    if (value.isNullOrEmpty()) return null
    return rawExpr(value)
}

/**
 * Parse an attribute WITHOUT the constant-snapshot evaluation.
 *
 * Required for `action` expressions (evaluating one at parse time would fire its assignments
 * into the parse env and collapse a pure-assignment action to a NumberLiteral) and for every
 * LIVE attribute — angle/offsetAngle/motion/xMotion/yMotion/alpha. iOS compiles those to
 * instruction streams and never parse-evaluates them (ECWatchDefinitionManager compiles angle
 * streams at :775/:907/:1178/…; only boundsCheck geometry attrs are eager). Folding a live attr
 * whose variables happen to be init constants freezes it forever: Tombstone's keyless winding
 * gears (`stat==1 ? tic=tic+2*pi/180 : tic`) and McAlester's stem `motion='stat'` were baked to
 * their init pose and could never move.
 */
private fun attrExprNoSnapshot(el: Element, name: String): ASTNode? {
    val value = attr(el, name)
    if (value.isNullOrEmpty()) return null
    return runCatching { parse(value) }.getOrNull()
}

/** [attrExpr] for an already-extracted (possibly rewritten) expression string. */
private fun rawExpr(value: String): ASTNode? {
    val ast = runCatching { parse(value) }.getOrNull() ?: return null
    val env = parseEnv ?: return ast
    val snapshot = runCatching { evaluate(ast, env) }.getOrNull()
    return if (snapshot != null && snapshot.isFinite()) NumberLiteral(snapshot) else ast
}

private fun attrInt(el: Element, name: String, default: Int): Int =
    attr(el, name)?.toIntOrNull() ?: default

private fun attrDouble(el: Element, name: String, default: Double): Double =
    attr(el, name)?.toDoubleOrNull() ?: default

/** Part name from `name`, falling back to `refName`. */
private fun partName(el: Element): String =
    attr(el, "name") ?: attr(el, "refName") ?: ""

/**
 * Mode matching:
 * - no `modes` attribute → matches 'front' only (the default)
 * - `modes` == 'all' → matches everything
 * - otherwise a '|'-separated list; matches if it contains the desired mode
 */
private fun matchesMode(el: Element, desired: ModeFilter): Boolean {
    val modesAttr = attr(el, "modes") ?: return desired == ModeFilter.FRONT
    val lower = modesAttr.lowercase()
    if (lower == "all") return true
    return lower.split('|').any { it.trim() == desired.key }
}

/** Element children only (skips text/comment nodes). */
private fun childElements(el: Element): List<Element> {
    val out = ArrayList<Element>()
    val nodes = el.childNodes
    for (i in 0 until nodes.length) {
        val n = nodes.item(i)
        if (n.nodeType == Node.ELEMENT_NODE) out.add(n as Element)
    }
    return out
}

/** Depth-first search for an element whose lowercased tag name equals [tagName]. */
private fun findElement(root: Element?, tagName: String): Element? {
    if (root == null) return null
    if (root.tagName.lowercase() == tagName) return root
    for (child in childElements(root)) {
        findElement(child, tagName)?.let { return it }
    }
    return null
}
