package com.plasticsmoke.beryl.watch

import com.plasticsmoke.beryl.expr.ASTNode

/**
 * In-memory model of a parsed watch face, ported from chronometer-web `src/watch/types.ts`.
 *
 * Numeric attribute values are stored as pre-parsed [ASTNode]s (not evaluated), so the renderer
 * can re-evaluate dynamic attributes per frame. String attributes (text, fontName, orientation,
 * modes, handType) are stored verbatim.
 *
 * Scope: the part types Milano exercises (QDial, QHand, Image, QRect, Static, Window). The
 * remaining part types from types.ts (Wheel, QText, Button, Terminator, QWedge, QDayNightRing,
 * Calendar*, Analemma, EotDial) are added as their faces are ported; the parser skips unknown
 * tags silently, matching the reference.
 */

class Watch(
    val name: String,
    val beatsPerSecond: Int,
    /** Diameter of the face in XML coordinate units. */
    val faceWidth: Double,
    /** CSS color for the bezel ring; empty string = no bezel. */
    val bezelColor: String,
    val bezelNoonMark: Boolean,
    val worldTimeRing: Boolean,
    val worldTimeSubdials: Boolean,
    val planetSelector: Boolean,
    val wadokei: Boolean,
    val numEnvironments: Int,
    val maxSeparateLoc: Int,
    val calendarWeekStart: Boolean,
    val urlAbbrev: String,
    /** All `<init expr="…">` blocks, in document order. */
    val initExprs: List<ASTNode>,
    /** Parts included for the selected mode, in document order. */
    val parts: List<WatchPart>,
)

/** Common fields shared by every part. */
sealed interface WatchPart {
    val name: String
    val x: ASTNode?
    val y: ASTNode?
    val modes: String?
}

data class QDialPart(
    override val name: String,
    override val x: ASTNode? = null,
    override val y: ASTNode? = null,
    override val modes: String? = null,
    val radius: ASTNode? = null,
    val radius2: ASTNode? = null,
    val clipRadius: ASTNode? = null,
    val orientation: String? = null,
    val demiTweak: ASTNode? = null,
    val text: String? = null,
    val fontSize: ASTNode? = null,
    val fontName: String? = null,
    val bgColor: ASTNode? = null,
    val strokeColor: ASTNode? = null,
    val fillColor1: ASTNode? = null,
    val fillColor2: ASTNode? = null,
    val marks: String? = null,
    val markWidth: ASTNode? = null,
    val nMarks: ASTNode? = null,
    val mSize: ASTNode? = null,
    val angle: ASTNode? = null,
    val angle0: ASTNode? = null,
    val angle1: ASTNode? = null,
    val angle2: ASTNode? = null,
    val update: ASTNode? = null,
    val updateOffset: ASTNode? = null,
    val kind: String? = null,
    val z: ASTNode? = null,
    val thick: ASTNode? = null,
    val animSpeed: ASTNode? = null,
) : WatchPart

data class QHandPart(
    override val name: String,
    override val x: ASTNode? = null,
    override val y: ASTNode? = null,
    override val modes: String? = null,
    val angle: ASTNode? = null,
    val length: ASTNode? = null,
    val length2: ASTNode? = null,
    val width: ASTNode? = null,
    val tail: ASTNode? = null,
    /** From XML `type`: 'rect' | 'tri' (default triangle when absent). */
    val handType: String? = null,
    val strokeColor: ASTNode? = null,
    val fillColor: ASTNode? = null,
    val lineWidth: ASTNode? = null,
    val kind: String? = null,
    /** Grab-priority tie-break when several interactive parts enclose a touch (iOS grabPrio). */
    val grabPrio: Int = 0,
    /** Drag action (Terra's worldtime-ring hand: executed once per 15° notch of a ring drag). */
    val action: ASTNode? = null,
    val update: ASTNode? = null,
    val updateOffset: ASTNode? = null,
    val z: ASTNode? = null,
    val thick: ASTNode? = null,
    // NOTE the XML `animate` attr is NOT modeled: iOS whitelists it in the parser but never
    // reads it — all motion smoothing is the animSpeed-driven angle chase (ECGLAnimatingValue).
    val animSpeed: ASTNode? = null,
    val dragAnimationType: String? = null,
    val oLength: ASTNode? = null,
    val oWidth: ASTNode? = null,
    val oTail: ASTNode? = null,
    val oLineWidth: ASTNode? = null,
    val oStrokeColor: ASTNode? = null,
    val oFillColor: ASTNode? = null,
    val oCenter: ASTNode? = null,
    val oRadius: ASTNode? = null,
    val oRadiusX: ASTNode? = null,
    val tFillColor: ASTNode? = null,
    val tStrokeColor: ASTNode? = null,
    val tLineWidth: ASTNode? = null,
    val src: String? = null,
    val xAnchor: ASTNode? = null,
    val yAnchor: ASTNode? = null,
    val offsetRadius: ASTNode? = null,
    val offsetAngle: ASTNode? = null,
    val nRays: ASTNode? = null,
    val text: String? = null,
    val fontSize: ASTNode? = null,
    val fontName: String? = null,
    val xMotion: ASTNode? = null,
    val yMotion: ASTNode? = null,
    val alpha: ASTNode? = null,
    val orientation: String? = null,
    val special: String? = null,
    val specialParam: ASTNode? = null,
    val envSlot: ASTNode? = null,
    // type='gear' (Tombstone skeleton movement): procedural gear with teeth/rim/spokes/hub + a
    // central pinion (leaves). `overlay` is an image stamped over the gear (e.g. mainspring blender).
    val tipRadius: ASTNode? = null,
    val rimOuterRadius: ASTNode? = null,
    val rimInnerRadius: ASTNode? = null,
    val hubRadius: ASTNode? = null,
    val leafRadius: ASTNode? = null,
    val nSpokes: ASTNode? = null,
    val nTeeth: ASTNode? = null,
    val nLeaves: ASTNode? = null,
    val overlay: String? = null,
) : WatchPart {
    /** Assignment-bearing angle exprs are eval-count-sensitive (see angleTargetOf). */
    val angleHasAssignment: Boolean by lazy { angle != null && com.plasticsmoke.beryl.expr.hasAssignment(angle) }
}

data class ImagePart(
    override val name: String,
    override val x: ASTNode? = null,
    override val y: ASTNode? = null,
    override val modes: String? = null,
    val src: String? = null,
    val alpha: ASTNode? = null,
    val scale: ASTNode? = null,
    val special: String? = null,
    val specialParam: ASTNode? = null,
    val envSlot: ASTNode? = null,
) : WatchPart

data class QRectPart(
    override val name: String,
    override val x: ASTNode? = null,
    override val y: ASTNode? = null,
    override val modes: String? = null,
    val w: ASTNode? = null,
    val h: ASTNode? = null,
    val bgColor: ASTNode? = null,
    val panes: ASTNode? = null,
) : WatchPart

data class WindowPart(
    override val name: String,
    override val x: ASTNode? = null,
    override val y: ASTNode? = null,
    override val modes: String? = null,
    val w: ASTNode? = null,
    val h: ASTNode? = null,
    /** From XML `type`: 'porthole' | 'rect'. */
    val windowType: String? = null,
    val border: ASTNode? = null,
    val strokeColor: ASTNode? = null,
    val shadowOpacity: ASTNode? = null,
    val shadowSigma: ASTNode? = null,
    val shadowOffset: ASTNode? = null,
    val shadowOffsetX: ASTNode? = null,
) : WatchPart

/**
 * A `<button>`: interactive on iOS, but also a VISIBLE image part (the crown/stem, side buttons,
 * reset). iOS draws buttons in document order — usually before the band/case art, which covers them
 * except where they poke past the case silhouette; `motion·xMotion/yMotion` slides them out when
 * active (stem-out etc.), which is 0 at rest.
 *
 * Interaction (iOS ECGLPart/ECButtonController): the hit rect is the image quad centered at
 * (x + motion·xMotion, y + motion·yMotion), or the corner rect (x, y, w, h) for src-less buttons;
 * [action] runs through the expression VM on tap. [enabled] gates activity — the iOS default
 * (no attr) is stem-out-only.
 */
/** ECPartRepeatStrategy values (esastro/src/ECConstants.h:495). */
const val REPEAT_SLOWLY_ONLY = 1
const val REPEAT_ACCELERATES_ONCE = 2
const val REPEAT_ACCELERATES_TWICE = 3

data class ButtonPart(
    override val name: String,
    override val x: ASTNode? = null,
    override val y: ASTNode? = null,
    override val modes: String? = null,
    val src: String? = null,
    val scale: ASTNode? = null,
    val motion: ASTNode? = null,
    val xMotion: ASTNode? = null,
    val yMotion: ASTNode? = null,
    val rotation: ASTNode? = null,
    val opacity: ASTNode? = null,
    /** BACK parse of a flipOnBack button: the renderer mirrors the whole part (position + image). */
    val flippedOnBack: Boolean = false,
    /** Tap action expression — assignments + side-effect leaves, evaluated in the watch env. */
    val action: ASTNode? = null,
    /** Hit rect size for src-less buttons (x,y is then the min-x/min-y corner, XML Y-up). */
    val w: ASTNode? = null,
    val h: ASTNode? = null,
    /** 'always' | 'wrongTimeOnly' | null = iOS default ECButtonEnabledStemOutOnly. */
    val enabled: String? = null,
    /** Grab-priority tie-break when several interactive parts enclose a touch (iOS grabPrio). */
    val grabPrio: Int = 0,
    /** Act on touch-down instead of touch-up. */
    val immediate: Boolean = false,
    /** repeatStrategy != ECPartDoesNotRepeat: acts on touch-down and repeats while held. */
    val repeats: Boolean = false,
    /** ECPartRepeatStrategy: 1 = slowly only (constant interval), 2 = accelerates once
     *  (default when the attr is absent), 3 = accelerates twice (fastest tier). */
    val repeatStrategy: Int = REPEAT_ACCELERATES_ONCE,
    val expanded: Boolean = false,
) : WatchPart

data class StaticPart(
    override val name: String,
    override val x: ASTNode? = null,
    override val y: ASTNode? = null,
    override val modes: String? = null,
    val children: MutableList<WatchPart> = mutableListOf(),
) : WatchPart

data class WheelPart(
    override val name: String,
    override val x: ASTNode? = null,
    override val y: ASTNode? = null,
    override val modes: String? = null,
    /** 'SWheel' | 'QWheel' | 'TWheel'. */
    val wheelVariant: String = "SWheel",
    val angle: ASTNode? = null,
    val angle1: ASTNode? = null,
    val angle2: ASTNode? = null,
    val radius: ASTNode? = null,
    val orientation: String? = null,
    val text: String? = null,
    val fontSize: ASTNode? = null,
    val fontName: String? = null,
    val strokeColor: ASTNode? = null,
    val bgColor: ASTNode? = null,
    val bgColor2: ASTNode? = null,
    val update: ASTNode? = null,
    val animSpeed: ASTNode? = null,
    val marks: String? = null,
    val refName: String? = null,
    val tradius: ASTNode? = null,
    val tick: String? = null,
    val kind: String? = null,
    val halfAndHalf: ASTNode? = null,
    val ticks: ASTNode? = null,
    val tickWidth: ASTNode? = null,
    val calendar: String? = null,
    val calendarStartDay: ASTNode? = null,
    val calendarWeekendColor: ASTNode? = null,
    val z: ASTNode? = null,
    /** Drag action (Terra's ring-mover TWheel: executed once per 15° notch of a ring drag). */
    val action: ASTNode? = null,
) : WatchPart

data class QWedgePart(
    override val name: String,
    override val x: ASTNode? = null,
    override val y: ASTNode? = null,
    override val modes: String? = null,
    val outerRadius: ASTNode? = null,
    val innerRadius: ASTNode? = null,
    val angleSpan: ASTNode? = null,
    val angle: ASTNode? = null,
    val strokeColor: ASTNode? = null,
    val fillColor: ASTNode? = null,
    val borderWidth: ASTNode? = null,
    val update: ASTNode? = null,
    val offsetRadius: ASTNode? = null,
    val offsetAngle: ASTNode? = null,
    val animSpeed: ASTNode? = null,
) : WatchPart

data class TerminatorPart(
    override val name: String,
    override val x: ASTNode? = null,
    override val y: ASTNode? = null,
    override val modes: String? = null,
    val radius: ASTNode? = null,
    val leavesPerQuadrant: ASTNode? = null,
    val incremental: ASTNode? = null,
    val leafBorderColor: ASTNode? = null,
    val leafFillColor: ASTNode? = null,
    val leafAnchorRadius: ASTNode? = null,
    val update: ASTNode? = null,
    val updateOffset: ASTNode? = null,
    val phaseAngle: ASTNode? = null,
    val rotation: ASTNode? = null,
) : WatchPart

/** A single positioned text label (vs QDial which lays text around a circle). */
data class QTextPart(
    override val name: String,
    override val x: ASTNode? = null,
    override val y: ASTNode? = null,
    override val modes: String? = null,
    val text: String? = null,
    val fontSize: ASTNode? = null,
    val fontName: String? = null,
    val strokeColor: ASTNode? = null,
    val radius: ASTNode? = null,
    val startAngle: ASTNode? = null,
    val orientation: String? = null,
) : WatchPart

/** The Sun's analemma (figure-8) on a circular disc, rotated to the observer's sky orientation. */
data class AnalemmaPart(
    override val name: String,
    override val x: ASTNode? = null,
    override val y: ASTNode? = null,
    override val modes: String? = null,
    val radius: ASTNode? = null,
    val sunRadius: ASTNode? = null,
    val sunFillColor: ASTNode? = null,
    val sunStrokeColor: ASTNode? = null,
    val channelColor: ASTNode? = null,
    val channelWidth: ASTNode? = null,
    val bgSrc: String? = null,
    val bgRotates: ASTNode? = null,
    val update: ASTNode? = null,
) : WatchPart

/** Equation-of-time dial (arc + tick marks + ±15-minute labels). */
data class EotDialPart(
    override val name: String,
    override val x: ASTNode? = null,
    override val y: ASTNode? = null,
    override val modes: String? = null,
    val radius: ASTNode? = null,
    val arcSpan: ASTNode? = null,
    val strokeColor: ASTNode? = null,
    val fontSize: ASTNode? = null,
    val titleFontSize: ASTNode? = null,
    val labelText: String? = null,
    val titleYOffset: ASTNode? = null,
) : WatchPart

/**
 * A row-cover/underlay for the grid calendar (Babylon): a small painted patch that hides the unused
 * leading cells of row 0 (underlays, with previous-month days) or trailing cells of the last rows
 * (covers, with next-month days). Slides horizontally (xMotion) as the month changes. Ported from
 * iOS ECQCalendarRowCoverView.
 */
data class CalendarRowCoverPart(
    override val name: String,
    override val x: ASTNode? = null,
    override val y: ASTNode? = null,
    override val modes: String? = null,
    /** 'row1Left' | 'row1Right' (underlays) | 'row6Left' | 'row56Right' (covers). */
    val coverType: String? = null,
    val fontName: String? = null,
    val fontSize: ASTNode? = null,
    val fontColor: ASTNode? = null,
    val bgColor: ASTNode? = null,
    val calendarRadius: ASTNode? = null,
    val z: ASTNode? = null,
) : WatchPart

/**
 * The weekday-abbreviation header row (Su Mo Tu …) above the grid calendar. Three instances exist
 * (weekdayStart 0/1/6); only the one matching the runtime week start renders. Ported from iOS
 * CalendarHeader parsing in ECWatchDefinitionManager.m.
 */
data class CalendarHeaderPart(
    override val name: String,
    override val x: ASTNode? = null,
    override val y: ASTNode? = null,
    override val modes: String? = null,
    /** Which weekday this header column-0 starts on: 0=Sunday, 1=Monday, 6=Saturday. */
    val weekdayStart: Int = 0,
    val weekdayColor: ASTNode? = null,
    val weekendColor: ASTNode? = null,
    val fontName: String? = null,
    val fontSize: ASTNode? = null,
) : WatchPart

/** Annular ring of wedges colored by whether a planet is above/below the horizon over the day. */
data class QDayNightRingPart(
    override val name: String,
    override val x: ASTNode? = null,
    override val y: ASTNode? = null,
    override val modes: String? = null,
    val outerRadius: ASTNode? = null,
    val innerRadius: ASTNode? = null,
    val numWedges: ASTNode? = null,
    val planetNumber: ASTNode? = null,
    val strokeColor: ASTNode? = null,
    val fillColor: ASTNode? = null,
    val timeBase: String? = null,
    val masterOffset: ASTNode? = null,
    val update: ASTNode? = null,
    val envSlot: ASTNode? = null,
) : WatchPart
