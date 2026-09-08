package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.astro.daysInMonth
import com.plasticsmoke.beryl.astro.timeIntervalFromUTCComponents
import com.plasticsmoke.beryl.astro.weekdayFromTimeInterval
import com.plasticsmoke.beryl.expr.Environment
import com.plasticsmoke.beryl.watch.CalendarHeaderPart
import com.plasticsmoke.beryl.watch.CalendarRowCoverPart
import com.plasticsmoke.beryl.watch.WheelPart
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The Babylon grid-calendar machinery: the month-grid wheels, the row covers/underlays that hide
 * unused leading/trailing cells (showing adjacent-month days), and the weekday header.
 *
 * Ported from iOS `ECQView.m` (the ECQHandSpoke calendar path + ECQCalendarRowCoverView) and the
 * calendar leaf methods in `ECGLWatch.m`; cross-checked against the Y-down structure in
 * chronometer-web `renderer.ts` (drawCalendarWheel/drawCalendarRowCover/drawCalendarHeader).
 *
 * The calendar is drawn directly onto the dial beneath the face image; the `cal win`/`month win`/
 * `year win` window cutouts (handled by WatchRenderer) reveal it. A calendar wheel has 4 quadrants
 * 90° apart; the wheel's rotation (rotationForCalendarWheel*) brings one quadrant to the top, into
 * the window. The other quadrants sit at 3/6/9 o'clock, off the window. Blank quadrants draw
 * nothing, so when two wheels overlap at the top the correct (opaque) one drawn last wins, and a
 * blank lets the wheel beneath show through.
 */

private val NO_ARGS = DoubleArray(0)
private const val CAL_BLACK = 0xFF000000.toInt()
private const val CAL_BLUE = 0xFF0000FF.toInt()

private fun call0(env: Environment, name: String): Double = env.functions[name]?.invoke(NO_ARGS) ?: 0.0

private data class CalQuadrant(val startColumn: Int, val blank: Boolean, val isOct1582: Boolean)

/** The 4 quadrant configs for a calendar wheel type. Matches iOS ECWatchDefinitionManager spokes. */
private fun calendarQuadrants(calType: String, weekdayStart: Int): List<CalQuadrant> = when (calType) {
    // First-of-month starting in columns 3,4,5,6.
    "calendarWheel3456" -> listOf(
        CalQuadrant(3, false, false), CalQuadrant(4, false, false),
        CalQuadrant(5, false, false), CalQuadrant(6, false, false),
    )
    // Columns 0,1,2, then a blank cutout.
    "calendarWheel012B" -> listOf(
        CalQuadrant(0, false, false), CalQuadrant(1, false, false),
        CalQuadrant(2, false, false), CalQuadrant(0, true, false),
    )
    // October 1582 (Gregorian switchover) + 3 blanks. Oct 1582 started on a Monday.
    "calendarWheelOct1582" -> listOf(
        CalQuadrant((8 - weekdayStart) % 7, false, true),
        CalQuadrant(0, true, false), CalQuadrant(0, true, false), CalQuadrant(0, true, false),
    )
    else -> emptyList()
}

internal fun drawCalendarWheel(ctx: DrawingContext, part: WheelPart, env: Environment) {
    val x = evalAttr(part.x, env)
    val y = -evalAttr(part.y, env)
    val radius = evalAttr(part.radius, env)
    if (radius <= 0) return
    val angle = evalAttr(part.angle, env)
    val fontSize = evalAttr(part.fontSize, env).let { if (it == 0.0) 8.0 else it }
    val fontName = part.fontName ?: "Arial"
    val bgColor = evalColorArgb(part.bgColor, env)
    // Weekday text uses the wheel's strokeColor (black on the front; teal on the night dial); the
    // weekend columns use calendarWeekendColor (blue on front; teal at night).
    val weekdayColor = if (part.strokeColor != null) evalColorArgb(part.strokeColor, env) else CAL_BLACK
    val weekendColor = if (part.calendarWeekendColor != null) evalColorArgb(part.calendarWeekendColor, env) else CAL_BLUE

    val wds = call0(env, "calendarWeekdayStart").toInt()
    val cellWidth = env.variables["calendarCellWidth"] ?: 13.3
    val cellHeight = env.variables["calendarCellHeight"] ?: 11.0
    val calHeight = env.variables["calendarHeight"] ?: 66.0
    val calWidth = env.variables["calendarWidth"] ?: 96.0
    val satCol = (6 - wds + 7) % 7
    val sunCol = (7 - wds) % 7

    ctx.save()
    ctx.translate(x, y)
    ctx.rotate(angle)
    ctx.setFont(fontName, fontSize)

    val quadrants = calendarQuadrants(part.calendar ?: "", wds)
    for ((qi, q) in quadrants.withIndex()) {
        if (q.blank) continue
        ctx.save()
        ctx.rotate(-qi * PI / 2)
        ctx.translate(0.0, -(radius - calHeight / 2))   // bring this quadrant to the top (twelve)

        // Opaque background — full rect, or an L-shape when row 0 starts partway in (the leading
        // cells fall outside, letting the cal-bak panel show through there).
        if (!isTransparentArgb(bgColor)) {
            if (q.startColumn > 0) {
                val firstCellX = -calWidth / 2 + q.startColumn * cellWidth
                val top = -calHeight / 2 - 1
                val row1Top = top + cellHeight + 2
                val fullRight = calWidth / 2
                val fullLeft = -calWidth / 2
                ctx.beginPath()
                ctx.moveTo(firstCellX, top)
                ctx.lineTo(fullRight, top)
                ctx.lineTo(fullRight, top + calHeight + 2)
                ctx.lineTo(fullLeft, top + calHeight + 2)
                ctx.lineTo(fullLeft, row1Top)
                ctx.lineTo(firstCellX, row1Top)
                ctx.closePath()
                ctx.fillPath(bgColor)
            } else {
                ctx.fillRect(-calWidth / 2, -calHeight / 2 - 1, calWidth, calHeight + 2, bgColor)
            }
        }

        // Day numbers 1..31 in a 7-column grid; weekend columns use the weekend color. October 1582
        // jumps from day 4 straight to 15 in the next cell (iOS keeps the grid contiguous, no gap).
        var slot = q.startColumn
        var dayNumber = 1
        while (dayNumber <= 31 && slot < 6 * 7) {
            val row = slot / 7
            val col = slot % 7
            val cx = -calWidth / 2 + col * cellWidth + cellWidth / 2 + 1
            val cy = -calHeight / 2 + row * cellHeight + cellHeight / 2
            val color = if (col == satCol || col == sunCol) weekendColor else weekdayColor
            ctx.fillText(dayNumber.toString(), cx, cy + ctx.textVisualCenterY(), color)
            dayNumber++
            slot++
            if (q.isOct1582 && dayNumber == 5) dayNumber = 15
        }
        ctx.restore()
    }
    ctx.restore()
}

internal fun drawCalendarRowCover(ctx: DrawingContext, part: CalendarRowCoverPart, env: Environment) {
    val x = evalAttr(part.x, env)
    val y = -evalAttr(part.y, env)
    val bgColor = evalColorArgb(part.bgColor, env)
    val fontColor = evalColorArgb(part.fontColor, env)
    val fontSize = evalAttr(part.fontSize, env).let { if (it == 0.0) 8.0 else it }
    val fontName = part.fontName ?: "Arial"
    val coverType = part.coverType ?: ""

    val calWidth = env.variables["calendarWidth"] ?: 96.0
    val calHeight = env.variables["calendarHeight"] ?: 66.0
    val cellWidth = env.variables["calendarCellWidth"] ?: 13.3
    val cellHeight = env.variables["calendarCellHeight"] ?: 11.0
    val calRadius = evalAttr(part.calendarRadius, env).let { if (it == 0.0) 117.0 else it }

    val xOffset = coverXOffset(env, coverType, cellWidth)
    val gridTop = -(calRadius - calHeight / 2)

    ctx.save()
    ctx.translate(x + xOffset, y)
    ctx.setFont(fontName, fontSize)

    // Clip to the grid window so a partially-slid cover can't spill outside.
    ctx.clipRect(-calWidth / 2 - xOffset - 1, gridTop - calHeight / 2 - 2, calWidth + 2, calHeight + 4)

    // Background patch behind the adjacent-month days.
    var coverX = 0.0; var coverY = 0.0; var coverW = 0.0; var coverH = 0.0
    when (coverType) {
        "row1Left" -> {
            val cy = gridTop - calHeight / 2 + cellHeight / 2
            coverX = -calWidth / 2; coverY = cy - cellHeight / 2; coverW = 4 * cellWidth; coverH = cellHeight
        }
        "row1Right" -> {
            val cy = gridTop - calHeight / 2 + cellHeight / 2
            coverX = -calWidth / 2; coverY = cy - cellHeight / 2; coverW = 5 * cellWidth; coverH = cellHeight
        }
        "row56Right" -> {
            val cy4 = gridTop - calHeight / 2 + 4 * cellHeight + cellHeight / 2
            coverX = -calWidth / 2; coverY = cy4 - cellHeight / 2; coverW = 7 * cellWidth; coverH = 2 * cellHeight
        }
        "row6Left" -> {
            val cy5 = gridTop - calHeight / 2 + 5 * cellHeight + cellHeight / 2
            coverX = -calWidth / 2; coverY = cy5 - cellHeight / 2; coverW = 7 * cellWidth; coverH = cellHeight
        }
    }
    if (!isTransparentArgb(bgColor)) ctx.fillRect(coverX, coverY, coverW, coverH, bgColor)

    // Adjacent-month day labels.
    when (coverType) {
        "row1Left", "row1Right" -> {
            val n = if (coverType == "row1Left") 4 else 5
            val first = if (coverType == "row1Left") 23 else 27
            val cy = gridTop - calHeight / 2 + cellHeight / 2
            for (col in 0 until n) {
                val day = first + col
                val cx = -calWidth / 2 + col * cellWidth + cellWidth / 2 + 1
                ctx.fillText(day.toString(), cx, cy + ctx.textVisualCenterY(), fontColor)
            }
        }
        "row56Right" -> {
            for (row in 0 until 2) for (col in 0 until 7) {
                val day = if (row == 0) col + 1 else col + 8
                val cx = -calWidth / 2 + col * cellWidth + cellWidth / 2 + 1
                val gridRow = 4 + row
                val cy = gridTop - calHeight / 2 + gridRow * cellHeight + cellHeight / 2
                ctx.fillText(day.toString(), cx, cy + ctx.textVisualCenterY(), fontColor)
            }
        }
        "row6Left" -> {
            for (col in 0 until 7) {
                val day = col + 1
                val cx = -calWidth / 2 + col * cellWidth + cellWidth / 2 + 1
                val cy = gridTop - calHeight / 2 + 5 * cellHeight + cellHeight / 2
                ctx.fillText(day.toString(), cx, cy + ctx.textVisualCenterY(), fontColor)
            }
        }
    }
    ctx.restore()
}

/**
 * Horizontal slide of a row cover/underlay so the right adjacent-month days show. Ported from iOS
 * ECGLWatch.m calendarRowCoverOffsetForType / calendarRowUnderlayOffsetForType. First-of-month
 * weekday + month lengths come from the hybrid Julian/Gregorian calendar (UTC noon avoids DST).
 */
private fun coverXOffset(env: Environment, coverType: String, cellWidth: Double): Double {
    val wds = call0(env, "calendarWeekdayStart").toInt()
    val month = call0(env, "monthNumber").toInt() + 1
    val year = call0(env, "yearNumber").toInt()
    val era = call0(env, "eraNumber").toInt()

    val firstDI = timeIntervalFromUTCComponents(era, year, month, 1, 12, 0, 0.0)
    val thisStartCol = (7 + weekdayFromTimeInterval(firstDI, 0.0) - wds) % 7
    val dim = daysInMonth(era, year, month)

    // Previous month (handle January → December and the 1 CE / 1 BCE boundary).
    var pEra = era; var pYear = year; var pMonth = month - 1
    if (pMonth < 1) {
        pMonth = 12
        if (era == 1 && year == 1) { pEra = 0; pYear = 1 } else if (era == 0) pYear = year + 1 else pYear = year - 1
    }
    val daysInPrev = daysInMonth(pEra, pYear, pMonth)

    // Next month.
    var nEra = era; var nYear = year; var nMonth = month + 1
    if (nMonth > 12) {
        nMonth = 1
        if (era == 0 && year == 1) { nEra = 1; nYear = 1 } else if (era == 0) nYear = year - 1 else nYear = year + 1
    }
    val nextFirstDI = timeIntervalFromUTCComponents(nEra, nYear, nMonth, 1, 12, 0, 0.0)
    val nextStartCol = (7 + weekdayFromTimeInterval(nextFirstDI, 0.0) - wds) % 7
    val nextStartRow = floor((dim + thisStartCol) / 7.0).toInt()

    var columnMotion = 7
    when (coverType) {
        "row1Left" -> { columnMotion = thisStartCol + 22 - daysInPrev; if (columnMotion < -4) columnMotion = -4 }
        "row1Right" -> { columnMotion = thisStartCol + 26 - daysInPrev; if (columnMotion < -5) columnMotion = -5 }
        "row56Right" -> columnMotion = if (nextStartRow == 4) nextStartCol else 7
        "row6Left" -> columnMotion = when (nextStartRow) { 5 -> nextStartCol; 4 -> nextStartCol - 7; else -> 7 }
    }
    return (columnMotion * cellWidth).roundToInt().toDouble()
}

internal fun drawCalendarHeader(ctx: DrawingContext, part: CalendarHeaderPart, env: Environment) {
    val wds = call0(env, "calendarWeekdayStart").toInt()
    if (part.weekdayStart != wds) return   // only the matching header renders; others are parked

    val x = evalAttr(part.x, env)
    val y = -evalAttr(part.y, env)
    val fontSize = evalAttr(part.fontSize, env).let { if (it == 0.0) 8.0 else it }
    val fontName = part.fontName ?: "Arial"
    val weekdayColor = evalColorArgb(part.weekdayColor, env)
    val weekendColor = evalColorArgb(part.weekendColor, env)

    val calWidth = env.variables["calendarWidth"] ?: 96.0
    val cellWidth = env.variables["calendarCellWidth"] ?: 13.3

    val dayNames = arrayOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")

    ctx.save()
    ctx.translate(x, y - fontSize)
    ctx.setFont(fontName, fontSize)
    for (col in 0 until 7) {
        val dayIndex = (col + part.weekdayStart) % 7
        val color = if (dayIndex == 0 || dayIndex == 6) weekendColor else weekdayColor
        val cx = -calWidth / 2 + col * cellWidth + cellWidth / 2
        ctx.fillText(dayNames[dayIndex], cx, ctx.textVisualCenterY(), color)
    }
    ctx.restore()
}
