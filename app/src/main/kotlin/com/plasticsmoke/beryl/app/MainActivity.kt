package com.plasticsmoke.beryl.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shell mirroring the iPhone's chrome: the watch fullscreen with swipe-to-switch, plus two
 * quarter-circle button fans in the bottom corners —
 *   left:  grid picker · lights-out (night) · flip to back
 *   right: settings · info (the face's help page) · list picker
 * The info panel is the original per-face Help HTML in a WebView. Last face persists.
 */
class MainActivity : Activity() {

    private lateinit var watchView: WatchView
    private lateinit var pickerOverlay: GridView
    private lateinit var location: LocationStore
    private lateinit var repo: FaceRepository
    private lateinit var faces: List<FaceInfo>

    private var infoOverlay: WebView? = null
    private var lightsOn = false
    private var flipped = false
    private lateinit var lightsButton: TextView
    private lateinit var flipButton: TextView
    private val fanButtons = ArrayList<TextView>()
    private var miniFaces: MiniFaces? = null
    private lateinit var gridAdapter: FaceGridAdapter
    private lateinit var prefs: android.content.SharedPreferences

    // --- status indicator lights (iOS Background.xml corner lights + tap-revealed texts) ---
    private lateinit var timeDot: View
    private lateinit var locDot: View
    private lateinit var alarmDot: View
    private lateinit var timeText: TextView
    private lateinit var locText: TextView
    private lateinit var alarmText: TextView
    private lateinit var varStore: FaceVarStore
    private val indicatorTicker = object : Runnable {
        override fun run() {
            updateIndicators()
            watchView.postDelayed(this, 2000)
        }
    }
    private val hideTexts = Runnable {
        for (t in listOf(timeText, locText, alarmText)) t.animate().alpha(0f).setDuration(330).start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        faces = FaceCatalog.load(assets)
        location = LocationStore(this) {
            // Position moved: envs bake lat/lon in at load, so rebuild the current face.
            repo.clear()
            watchView.showFace(watchView.currentFaceIndex())
        }
        repo = FaceRepository(assets, location, FaceVarStore(this))
        prefs = getPreferences(MODE_PRIVATE)

        val root = FrameLayout(this)

        watchView = WatchView(
            this, faces, repo,
            initialFace = prefs.getInt(PREF_FACE, 0),
            onFaceShown = { idx ->
                prefs.edit().putInt(PREF_FACE, idx).apply()
                // iOS mode is PER-WATCH and persisted ("<name>-ModeNum" in NSUserDefaults,
                // ECGLWatch.m:108/:921): a watch left on its back or in night STAYS that way
                // across switches and relaunches; switching adopts the target's own mode.
                val mode = prefs.getInt(PREF_MODE_PREFIX + faces[idx].name, 0)
                runOnUiThread {
                    flipped = mode == 2
                    lightsOn = mode == 1
                    applyMode()
                }
            },
            // Peeks/warmups render each face on its own persisted side (no front flash
            // when a back- or night-mode face slides away).
            modeFor = { name -> prefs.getInt(PREF_MODE_PREFIX + name, 0) },
        )
        root.addView(
            watchView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        // Corner fans: translucent quarter discs + three glyph buttons each.
        root.addView(QuarterDisc(this, left = true), fanBgParams(Gravity.START))
        root.addView(QuarterDisc(this, left = false), fanBgParams(Gravity.END))

        addFanButton(root, left = true, slot = 0, glyph = "▦") { togglePicker(true) }
        lightsButton = addFanButton(root, left = true, slot = 1, glyph = "☾") {
            lightsOn = !lightsOn
            applyMode()
        }
        flipButton = addFanButton(root, left = true, slot = 2, glyph = "⇄") {
            flipped = !flipped
            // The flip button plays the 3-D turn (iOS backFlip → allowAnimation:true);
            // lights toggles and face switches keep snapping (nightFlip/dayFlip don't animate).
            applyMode(animateFlip = true)
        }
        addFanButton(root, left = false, slot = 0, glyph = "⚙") { showSettings() }
        addFanButton(root, left = false, slot = 1, glyph = "ⓘ") { showInfo() }
        addFanButton(root, left = false, slot = 2, glyph = "☰") { showListPicker() }

        // iOS status indicator lights (Background.xml): time-sync dot upper-left, location dot
        // upper-right, alarm dot beside the time dot when any alarm is armed. An empty-area tap
        // reveals the corner status TEXTS (ECTS/ECLocationManager statusText) for 5 s, like the
        // iPhone's toggleECStatusNameDisplay.
        varStore = FaceVarStore(this)
        fun dot(): View = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
        }
        fun cornerText(gravityH: Int): TextView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = if (gravityH == Gravity.START) Gravity.START else Gravity.END
            alpha = 0f
        }
        timeDot = dot(); locDot = dot()
        // iOS Background.xml 'alarm state' wheel: a yellow NOTE glyph (♬ for 1 alarm, ♫ for
        // 2, ♪ for 3+) on a dark disc, always visible while an alarm is armed; tapping it is
        // the 'gotoAlarmer' button — switchToNextActiveAlarmWatch().
        alarmDot = TextView(this).apply {
            setTextColor(0xFFC0C000.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF242520.toInt())
            }
            setOnClickListener { watchView.showNextAlarmFace() }
        }
        timeText = cornerText(Gravity.START); alarmText = cornerText(Gravity.START)
        locText = cornerText(Gravity.END)
        alarmText.setTextColor(0xFFC0C000.toInt())   // iOS alarm-state label yellow
        val dotD = dp(10)
        val noteD = dp(21)   // iOS gotoAlarmer hit rect is 21×21 pt
        val topM = dp(56)
        root.addView(timeDot, FrameLayout.LayoutParams(dotD, dotD, Gravity.TOP or Gravity.START).apply { leftMargin = dp(14); topMargin = topM })
        root.addView(alarmDot, FrameLayout.LayoutParams(noteD, noteD, Gravity.TOP or Gravity.START).apply { leftMargin = dp(30); topMargin = topM - dp(5) })
        root.addView(locDot, FrameLayout.LayoutParams(dotD, dotD, Gravity.TOP or Gravity.END).apply { rightMargin = dp(14); topMargin = topM })
        root.addView(timeText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { leftMargin = dp(14); topMargin = topM + dp(14) })
        root.addView(alarmText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { leftMargin = dp(14); topMargin = topM + dp(48) })
        root.addView(locText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { rightMargin = dp(14); topMargin = topM + dp(14) })
        watchView.onEmptyTap = {
            updateIndicators()
            for (t in listOf(timeText, locText, alarmText)) t.animate().alpha(1f).setDuration(330).start()
            watchView.removeCallbacks(hideTexts)
            watchView.postDelayed(hideTexts, 5000)
        }

        // Fullscreen face-picker grid, hidden until the grid button is tapped.
        gridAdapter = FaceGridAdapter(faces)
        pickerOverlay = GridView(this).apply {
            numColumns = 3
            setBackgroundColor(0xE6000000.toInt())
            visibility = View.GONE
            adapter = gridAdapter
            setOnItemClickListener { _, _, position, _ ->
                togglePicker(false)
                watchView.showFace(position)
            }
        }
        root.addView(
            pickerOverlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        setContentView(root)

        if (!location.hasPermission()) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                REQ_LOCATION,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        location.refresh()
        watchView.resume()
        watchView.removeCallbacks(indicatorTicker)
        watchView.post(indicatorTicker)
        if (pickerOverlay.visibility == View.VISIBLE) miniFaces?.start()
    }

    override fun onPause() {
        miniFaces?.stop()
        watchView.pause()   // stop rendering + silence the bell while backgrounded
        watchView.removeCallbacks(indicatorTicker)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) location.refresh()
    }

    /**
     * Refresh the corner indicator lights + texts (iOS timeIndicatorColor /
     * locationIndicatorColor / ECTS + ECLocationManager statusText / alarm-state label).
     * Android device time is OS-NTP-synced, so the time light reads ECTSGood (green,
     * "Time synchronized"). Location: live good fix = green "Location established"; live poor
     * fix = yellow "Location uncertain"; no live fix = magenta "Location manual" (our
     * persisted/fallback position is iOS's manual/overridden state); providers off =
     * "Location Services OFF". Alarm dot (iOS alarmstate glyph) appears yellow when any
     * face's alarm is armed; the text reads no/1/N alarms.
     */
    private fun updateIndicators() {
        (timeDot.background as android.graphics.drawable.GradientDrawable).setColor(0xFF00B000.toInt())
        timeText.text = "Time\nsynchronized"

        val servicesOn = location.hasPermission() && location.servicesEnabled()
        val (locColor, locStr) = when {
            !servicesOn -> 0xFFC0C000.toInt() to "Location\nServices OFF"
            location.hasLiveFix && location.goodAccuracy -> 0xFF00B000.toInt() to "Location\nestablished"
            location.hasLiveFix -> 0xFFC0C000.toInt() to "Location\nuncertain"
            else -> 0xFFC000C0.toInt() to "Location\nmanual"
        }
        (locDot.background as android.graphics.drawable.GradientDrawable).setColor(locColor)
        locText.text = locStr

        val armed = faces.count { varStore.load(it.name)["__alarmEnabled"] == 1.0 }
        alarmDot.visibility = if (armed > 0) View.VISIBLE else View.GONE
        // iOS Background.xml alarm-state wheel glyphs: 1 → ♬, 2 → ♫, 3+ → ♪.
        (alarmDot as TextView).text = when {
            armed <= 1 -> "♬"
            armed == 2 -> "♫"
            else -> "♪"
        }
        alarmText.text = when (armed) {
            0 -> "no\nalarms"
            1 -> "1\nalarm"
            else -> "$armed\nalarms"
        }
    }

    // --- mode (front / night / back) ---

    private fun applyMode(animateFlip: Boolean = false) {
        val mode = if (flipped) 2 else if (lightsOn) 1 else 0
        watchView.setModeIndex(mode, animateFlip)
        // Per-watch mode persistence (iOS "<name>-ModeNum").
        prefs.edit().putInt(PREF_MODE_PREFIX + faces[watchView.currentFaceIndex()].name, mode).apply()
        // The iPhone hides the lights button on the back (there is no back night mode) and the
        // flip button in night mode (Background.xml slides the back button out when
        // appMode()==night — night→back is a transition iOS forbids).
        lightsButton.visibility = if (flipped) View.GONE else View.VISIBLE
        flipButton.visibility = if (lightsOn && !flipped) View.GONE else View.VISIBLE
        // Night mode dims the corner chrome (iOS swaps every corner button to dim n*-png art —
        // Background.xml nday/ngrid/ninfo/… — preserving dark adaptation).
        val base = if (lightsOn) NIGHT_TINT else BUTTON_TINT
        for (b in fanButtons) b.setTextColor(base)
        lightsButton.setTextColor(if (lightsOn) NIGHT_ACTIVE_TINT else BUTTON_TINT)
        flipButton.setTextColor(if (flipped) ACTIVE_TINT else base)
    }

    // --- info (per-face help HTML) ---

    private fun showInfo() {
        val face = faces[watchView.currentFaceIndex()]
        val dir = helpDir(face)
        val web = infoOverlay ?: WebView(this).also { w ->
            w.setBackgroundColor(Color.WHITE)
            infoOverlay = w
            (watchView.parent as FrameLayout).addView(
                w,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        web.visibility = View.VISIBLE
        web.loadUrl("file:///android_asset/help/$dir/$dir.html".replace(" ", "%20"))
    }

    /** Help dirs use the iOS bundle names — same as face.name except Mauna Kea's space. */
    private fun helpDir(face: FaceInfo) = if (face.name == "MaunaKea") "Mauna Kea" else face.name

    // --- settings ---

    private fun showSettings() {
        val tz = TimeZone.getDefault()
        val offsetH = tz.getOffset(System.currentTimeMillis()) / 3600000.0
        val msg = """
            Observer: %.2f°, %.2f°
            Timezone: %s (UTC%+.1f)
            Location permission: %s

            Astronomy (sun/moon/planet positions, rise/set) is computed for the observer position above.
        """.trimIndent().format(
            location.latDeg, location.lonDeg, tz.id, offsetH,
            if (location.hasPermission()) "granted" else "not granted",
        )
        AlertDialog.Builder(this)
            .setTitle("Beryl Chronometer")
            .setMessage(msg)
            .setPositiveButton("Refresh location") { _, _ -> location.refresh() }
            .setNeutralButton("App help") { _, _ -> showHelpPage("Help Contents.html") }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showHelpPage(page: String) {
        showInfo()   // ensures the WebView exists and is visible
        infoOverlay?.loadUrl("file:///android_asset/help/$page".replace(" ", "%20"))
    }

    // --- pickers ---

    private fun showListPicker() {
        AlertDialog.Builder(this)
            .setTitle("Watches")
            .setItems(faces.map { it.display }.toTypedArray()) { _, which ->
                watchView.showFace(which)
            }
            .show()
    }

    private fun togglePicker(show: Boolean) {
        pickerOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            pickerOverlay.smoothScrollToPosition(watchView.currentFaceIndex())
            // Live minis: all faces render once a second while the grid is open (iPhone-style).
            val mf = miniFaces ?: run {
                val cellW = (resources.displayMetrics.widthPixels / 3 * 0.92).toInt()
                val cellH = cellW * 3 / 2
                MiniFaces(
                    assets, location, faces, cellW, cellH, FaceVarStore(this),
                    // Each mini renders its face's persisted side (iOS grid = live watches).
                    modeFor = { name -> prefs.getInt(PREF_MODE_PREFIX + name, 0) },
                ) {
                    runOnUiThread {
                        if (pickerOverlay.visibility == View.VISIBLE) gridAdapter.notifyDataSetChanged()
                    }
                }.also { miniFaces = it }
            }
            mf.start()
        } else {
            miniFaces?.stop()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val web = infoOverlay
        when {
            web != null && web.visibility == View.VISIBLE ->
                if (web.canGoBack()) web.goBack() else web.visibility = View.GONE
            pickerOverlay.visibility == View.VISIBLE -> togglePicker(false)
            else -> super.onBackPressed()
        }
    }

    // --- corner-fan plumbing ---

    /** Translucent quarter disc anchored to a bottom corner, behind the fan buttons. */
    private class QuarterDisc(context: Context, val left: Boolean) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38000000 }
        override fun onDraw(canvas: Canvas) {
            val r = width.toFloat()
            canvas.drawCircle(if (left) 0f else r, height.toFloat(), r, paint)
        }
    }

    private fun fanBgParams(horizontal: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(dp(FAN_RADIUS), dp(FAN_RADIUS), Gravity.BOTTOM or horizontal)

    /** A glyph button on the fan arc: slot 0 = along the bottom edge … 2 = up the side edge. */
    private fun addFanButton(root: FrameLayout, left: Boolean, slot: Int, glyph: String, onTap: () -> Unit): TextView {
        val angleDeg = 10.0 + 35.0 * slot   // 10°, 45°, 80° from the bottom edge
        val r = dp(BUTTON_RING)
        val dx = (r * cos(Math.toRadians(angleDeg))).toInt()
        val dy = (r * sin(Math.toRadians(angleDeg))).toInt()
        val size = dp(44)
        val btn = TextView(this).apply {
            text = glyph
            textSize = 21f
            setTextColor(BUTTON_TINT)
            gravity = Gravity.CENTER
            setOnClickListener { onTap() }
        }
        val lp = FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or (if (left) Gravity.START else Gravity.END))
        val edge = (dx - size / 2).coerceAtLeast(0)
        if (left) lp.leftMargin = edge else lp.rightMargin = edge
        lp.bottomMargin = (dy - size / 2).coerceAtLeast(0)
        root.addView(btn, lp)
        fanButtons.add(btn)
        return btn
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Thumbnail + name per face; thumbnails decoded lazily and kept (25 × ~170×300 is small). */
    private inner class FaceGridAdapter(val faces: List<FaceInfo>) : BaseAdapter() {
        private val thumbs = arrayOfNulls<android.graphics.Bitmap>(faces.size)

        override fun getCount() = faces.size
        override fun getItem(position: Int) = faces[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(10), dp(6), dp(10))
                addView(
                    ImageView(this@MainActivity).apply { adjustViewBounds = true },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(150)),
                )
                addView(
                    TextView(this@MainActivity).apply {
                        setTextColor(Color.WHITE)
                        textSize = 13f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        setPadding(0, dp(6), 0, 0)
                    },
                )
            }
            val face = faces[position]
            // Live mini when available (rendered each second while the grid is open),
            // else the static docs-preview thumbnail as the loading placeholder.
            val live = miniFaces?.display?.getOrNull(position)
            val thumb = live ?: thumbs[position] ?: try {
                assets.open(face.thumb).use { BitmapFactory.decodeStream(it) }
                    .also { thumbs[position] = it }
            } catch (_: Exception) {
                null
            }
            (cell.getChildAt(0) as ImageView).setImageBitmap(thumb)
            (cell.getChildAt(1) as TextView).text = face.display
            return cell
        }
    }

    private companion object {
        const val PREF_FACE = "faceIndex"
        const val PREF_MODE_PREFIX = "mode-"   // per-watch mode (iOS "<name>-ModeNum")
        const val REQ_LOCATION = 1
        const val FAN_RADIUS = 130
        const val BUTTON_RING = 88
        const val BUTTON_TINT = 0xC8FFFFFF.toInt()
        const val ACTIVE_TINT = 0xFF00C0AC.toInt()       // the faces' night teal
        const val NIGHT_TINT = 0x50FFFFFF                // dim chrome in night mode (iOS n*-png art)
        const val NIGHT_ACTIVE_TINT = 0xC800C0AC.toInt() // lights button lit, but night-dim
    }
}
