package com.plasticsmoke.beryl.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import com.plasticsmoke.beryl.astro.HandDragSession
import com.plasticsmoke.beryl.render.dragAnchorOf
import com.plasticsmoke.beryl.render.evalAttr
import com.plasticsmoke.beryl.render.findPartAt
import com.plasticsmoke.beryl.render.renderFrame
import com.plasticsmoke.beryl.watch.ButtonPart
import com.plasticsmoke.beryl.watch.QHandPart
import com.plasticsmoke.beryl.watch.WheelPart
import kotlin.math.abs
import kotlin.math.min

/**
 * Renders the current watch face over the granite wallpaper on the iOS logical 320×480 screen,
 * scaled to fit the view. Dragging horizontally slides between faces like the iPhone: the
 * current face tracks the finger while the neighbor peeks in from the edge; release commits
 * (past ⅓ width or a fling) or springs back.
 *
 * Frame rendering happens on a dedicated render thread (coalesced jobs, lock-handed triple
 * buffering); face loading happens on the repository's loader thread, with both swipe
 * neighbors preloaded so the peek is usually ready before the finger moves an inch.
 */
class WatchView(
    context: Context,
    private val faces: List<FaceInfo>,
    private val repo: FaceRepository,
    initialFace: Int,
    private val onFaceShown: (Int) -> Unit = {},
    /** Persisted per-watch mode (0=front, 1=night, 2=back): peeks and warmups render each
     *  face on ITS OWN side — iOS watches stay resident on whatever side they were left. */
    private val modeFor: (String) -> Int = { 0 },
) : View(context) {

    // faceIndex is the LOGICAL current face — like iOS currentWatchIndex, it advances the instant
    // a swipe commits (before the slide finishes), so chained swipes register immediately.
    // currentIdx is the face the loaded [current] actually holds; it lags faceIndex for the few
    // ms a commit takes to load + render the new face's own frame.
    @Volatile private var faceIndex = initialFace.coerceIn(0, faces.size - 1)
    @Volatile private var currentIdx = -1
    @Volatile private var modeIndex = 0
    @Volatile private var current: LoadedFace? = null
    @Volatile private var wallpaper: Bitmap? = null

    /** Bumped AFTER every (current, currentIdx, modeIndex) mutation: a frame rendered
     *  against a stale combination is DISCARDED before promotion instead of flashing the
     *  wrong side (arriving at a back-mode face must never show one front-mode frame). */
    @Volatile private var stateSerial = 0

    private val renderThread = HandlerThread("watch-render").apply { start() }
    private val renderHandler = Handler(renderThread.looper)

    // TRIPLE-buffered frames with a locked handoff. Two buffers tear at continuous rates, and
    // even three race if the picker reads a stale displayIdx while onDraw promotes that buffer
    // (seen as partially-rendered Tombstone frames at 30 fps). Under bufLock: the render thread
    // picks a buffer that is neither pending nor displayed; onDraw only ever promotes
    // pendingIdx — which the picker excluded — so a buffer being written is never displayed.
    private val bufLock = Any()
    private var buffers = arrayOfNulls<Bitmap>(3)
    private var pendingIdx = -1   // guarded by bufLock: latest finished frame
    private var displayIdx = -1   // guarded by bufLock: what onDraw shows
    private var layerPool: LayerBitmapPool? = null
    // Wallpaper pre-scaled to the view once: a filtered full-screen rescale costs ~60-100 ms
    // per frame; a 1:1 blit of the cached copy is a few ms.
    private var scaledWallpaper: Bitmap? = null

    private var lastPostedTick = -1L
    private var frameCount = 0L
    private val blitPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
    }

    // --- slide-between-faces state (continuous-position model, ported from iOS) ---
    // The watches live on a horizontal strip: face k sits at x = slideX + (k - faceIndex)·width.
    // A drag moves slideX with the finger; a release either flicks to a neighbor (faceIndex
    // advances, slideX re-bases so the new face keeps its on-screen position, then animates to 0)
    // or springs back. The animation NEVER blocks input: a new touch cancels it and re-anchors,
    // so slides chain smoothly — exactly iOS's animatingPositionZoom=false on touch-move.
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    @Volatile private var dragging = false // UI thread writes; the alarm scanner peeks at it
    private var touchMoved = false         // did the finger actually drag during this touch?
    private var slideX = 0f                // UI thread: faceIndex's x offset in px (0 = centered)
    private var slideAnchorX = 0f          // finger x that maps to slideX == 0, for continuity
    private var slideAnimator: ValueAnimator? = null

    // --- flip-to-back 3-D turn (iOS ECGLWatch animatingFlip) ---
    // The 0.5 s turn about the watch's vertical axis: the shown side rotates 0→-90°, the
    // content swaps at halfway, and the target side rotates +90°→0. flipAngle is the current
    // rotation (0 = idle); flipBitmap holds the target side pre-rendered before the turn
    // starts (iOS prepareAllPartsForDraw + attachTexture), bridging the halfway swap until
    // the new side's own live frame lands.
    private var flipAnimator: ValueAnimator? = null
    private var flipAngle = 0f
    // Every mode order bumps this; a pending animated flip whose serial went stale must NOT
    // start (its pre-render hops threads, so a newer order can arrive in between).
    private var modeOrderSerial = 0
    private val flipCamera = android.graphics.Camera()
    private val flipMatrix = android.graphics.Matrix()
    private var flipBitmap: Bitmap? = null
    @Volatile private var flipBitmapMode = -1
    /** Pending flip order, handled INSIDE renderJob: on fast faces (Tombstone renders
     *  back-to-back) postRender's postAtFrontOfQueue(renderJob) front-inserts (when=0)
     *  ahead of any plain-posted job forever — a separately posted pre-render STARVES. */
    private class FlipRequest(val target: Int, val serial: Int, val loaded: LoadedFace)
    @Volatile private var flipRequest: FlipRequest? = null

    // Both neighbor peeks, pre-rendered on the render thread as soon as a face settles, so a
    // slide reveals the neighbor INSTANTLY — the iPhone keeps every watch resident and drawable,
    // never a loading gap. Each slot records the face index it holds and whether its frame is ready.
    private class Peek {
        var bitmap: Bitmap? = null
        @Volatile var idx = -1
        @Volatile var ready = false
    }
    private val peekNext = Peek()   // faceIndex + 1 (revealed when dragging left)
    private val peekPrev = Peek()   // faceIndex - 1 (revealed when dragging right)
    // The ARRIVAL face of an in-flight slideToFace ride. The hop peeks get reassigned every
    // hop, and the loader's small LRU can re-evict a warmed target mid-ride — but a bitmap
    // in hand can't be evicted, so the ride gates its start on this slot being rendered.
    private val ridePeek = Peek()
    private var rideSerial = 0
    // The committed face's already-rendered frame, bridging the ~ms between faceIndex advancing
    // on a commit and the new face's own live frame landing (else the swap would flash).
    private var centerBitmap: Bitmap? = null
    @Volatile private var centerIdx = -1
    // A snapshot of the OUTGOING face captured at commit and held for the slide: advancing to the
    // new current pushes the old face out of the main buffer, and its neighbor peek takes a few
    // hundred ms to re-render, so without this the departing face would vanish mid-slide.
    private var outgoingBitmap: Bitmap? = null
    @Volatile private var outgoingIdx = -1
    /** Face index the latest promoted main buffer holds (= currentIdx of the rendered face). */
    @Volatile private var frameFace = -1
    /** Mode the latest promoted main buffer was rendered in (flips need to know which side). */
    @Volatile private var frameMode = -1

    // The EC Status line (iOS warpLabel): ONE stationary centered line near the top. While
    // sliding it shows BOTH names in screen order with the iOS arrow format
    // ("<- Left                     Right ->"); on release it shows the landed face's name,
    // which fades after ECStatusPersistence (5 s) over ECControlFadeTime (0.33 s). A tap on
    // empty watch area toggles the current name (iOS toggleECStatusNameDisplay).
    private var statusText: String? = null
    private var statusAlpha = 0f
    private var statusFadeIn: ValueAnimator? = null
    private var statusFadeOut: ValueAnimator? = null
    // iOS warpLabel background: clear for names/slide labels, red for the warp/alarm banner.
    private var statusBgColor = 0
    private val statusTicker = Runnable { statusTick() }
    private val statusBarPaint = Paint()

    // iOS warpLabel: plain white systemFontOfSize:16, no shadow. Liberation Sans is the
    // app's Helvetica-metric stand-in for iOS system faces (Roboto's metrics differ).
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = try {
            android.graphics.Typeface.createFromAsset(context.assets, "fonts/LiberationSans-Regular.ttf")
        } catch (e: Exception) {
            android.graphics.Typeface.SANS_SERIF
        }
    }

    // --- button/hand interaction (stage 1: tap actions; stage 2: drag hands to set time) ---
    // All of this state lives on the RENDER thread (the envs are only ever touched there),
    // except [handGrab], which the UI thread reads to suppress the face-slide gesture.
    private var pressedButton: ButtonPart? = null
    private var pressActed = false
    private var pressSilencedRing = false
    /** UI-thread mirror of "this press owns the gesture": an immediate or repeating button
     *  never turns into a swipe while held (iOS touchesMoved `repeatingPartTimer ||
     *  [buttonPressed immediate]` branch, ChronometerAppDelegate.m:2335). */
    @Volatile private var pressSticky = false
    // Repeat-while-held (iOS repeatingPartTimer, ChronometerAppDelegate.m:2042-2126): a held
    // button re-fires at kECButtonFirstRepeatInterval, then kECButtonRepeatInterval, then
    // log-accelerates per its strategy; intervals below the 50 ms floor act multiple times
    // per fire. All on the render thread with the rest of the press state.
    private var repeatCount = 0
    private var repeatNextUptime = 0L
    private val repeatJob = object : Runnable {
        override fun run() {
            val loaded = current ?: return
            val button = pressedButton?.takeIf { it.repeats } ?: return
            var effectiveMs = REPEAT_MS
            if (++repeatCount > 1 && button.repeatStrategy != com.plasticsmoke.beryl.watch.REPEAT_SLOWLY_ONLY) {
                val maxIter: Int
                val maxSpeedMs: Double
                if (button.repeatStrategy == com.plasticsmoke.beryl.watch.REPEAT_ACCELERATES_ONCE) {
                    maxIter = 11; maxSpeedMs = FAST_REPEAT_MS
                } else {
                    maxIter = 35; maxSpeedMs = FASTER_REPEAT_MS
                }
                // iOS interpolateByLog: geometric ramp from the base to the max-speed interval.
                effectiveMs = if (repeatCount < maxIter) {
                    Math.exp(
                        Math.log(REPEAT_MS) +
                            (Math.log(maxSpeedMs) - Math.log(REPEAT_MS)) * (repeatCount - 1) / (maxIter - 1)
                    )
                } else maxSpeedMs
            }
            // Faster rates than the timer floor act more times per fire.
            var intervalMs = effectiveMs
            var timesToAct = 1
            while (intervalMs < MIN_REPEAT_MS) {
                timesToAct++
                intervalMs = effectiveMs * timesToAct
            }
            setAnimOverride(loaded, (intervalMs * 3 / 4).toLong())
            // Cadence anchored to absolute uptime (the iPhone holds dead-on 100 ms steps):
            // dispatch latency behind an in-flight frame must not accumulate into a slower
            // repeat rate. A stall never builds a catch-up burst — the anchor is re-based to
            // now, so at most one fire runs immediately.
            repeatNextUptime = maxOf(repeatNextUptime + intervalMs.toLong(), SystemClock.uptimeMillis())
            renderHandler.postAtTime(this, repeatNextUptime)
            act(loaded, button, timesToAct)
        }
    }

    /** iOS animationOverrideInterval: bound new hand-sweeps to the repeat cadence. */
    private fun setAnimOverride(loaded: LoadedFace, ms: Long) {
        for (state in loaded.states) state.cache.animOverrideMs = ms
    }
    private var dragSession: HandDragSession? = null
    @Volatile private var handGrab = false
    // One-shot: the next live render seeds the hand-sweep state (mode changes snap, like iOS).
    @Volatile private var seedNextRender = false
    // One-shot session warmup: anchor every face's sweep state near launch (iOS background
    // loader + load-snap), so any switch shows the hands catching up from session start.
    private var warmupStarted = false
    private var warmupBitmap: Bitmap? = null

    // --- alarm bell (stage 3): iOS ECAudio repeatRing. Triangle.wav is a SINGLE triangle strike
    // that rings out over ~2.5 s; iOS fires it ECAlarmRings (20) times at ECAlarmRepeatInterval
    // (0.5 s) via AudioServicesPlayAlertSound — a FIRE-AND-FORGET play that OVERLAPS: each new
    // strike sounds while the previous one is still ringing, so the tails stack into the shimmer
    // (and the last strike rings out alone = the trailing single ding). SoundPool has exactly
    // those semantics; MediaPlayer.seekTo(0) can't overlap — restarting cuts the tail, which
    // made it sound like sparse isolated dings. Looping the 5 s file dinged once per 5 s. ---
    private var soundPool: android.media.SoundPool? = null
    private var bellSoundId = 0
    private var bellLoaded = false
    private var wasRinging = false
    private var bellRings = 0
    private val bellRinger = object : Runnable {
        override fun run() {
            if (bellRings >= BELL_RINGS) return   // last strike's tail rings out on its own
            val sid = soundPool?.takeIf { bellLoaded }?.play(bellSoundId, 1f, 1f, 1, 0, 1f) ?: 0
            if (bellRings == 0) Log.d(TAG, "alarm ring 1/$BELL_RINGS every ${BELL_REPEAT_MS}ms (SoundPool sid=$sid loaded=$bellLoaded)")
            bellRings++
            renderHandler.postDelayed(this, BELL_REPEAT_MS)
        }
    }

    // --- background-face alarm firing (iOS ECGLWatch alarmTimerFire + alarmFiredInWatch) ---
    // On the iPhone every watch stays resident with its alarm timer armed; when one fires the
    // app SWITCHES to that watch so the user can see why it's ringing. Here the per-face
    // subsystem states are session-resident in the repository (repo.states), and this 1 s
    // render-thread tick checks every face's alarm — not just the rendered ones. The switch
    // brings the face on screen, whose own render loop then starts the bell.
    private val alarmWasRinging = BooleanArray(faces.size)
    private val alarmScan = object : Runnable {
        override fun run() {
            for (i in faces.indices) {
                val alarm = repo.states(faces[i].name).alarm
                alarm.checkFire()
                val ringing = alarm.ringing
                if (ringing && !alarmWasRinging[i] && i != faceIndex) {
                    // Mid-gesture: retry next tick (ringing persists ~20 s) instead of
                    // yanking the strip out from under the finger.
                    if (handGrab || dragging) continue
                    Log.d(TAG, "alarm fired on ${faces[i].name} — switching (iOS alarmFiredInWatch)")
                    post { slideToFace(i) }
                }
                alarmWasRinging[i] = ringing
            }
            renderHandler.postDelayed(this, ALARM_SCAN_MS)
        }
    }

    private fun setBellPlaying(play: Boolean) {
        try {
            if (play) {
                if (bellRings in 1 until BELL_RINGS) {
                    bellRings = 1   // already ringing: another n rings (iOS startRinging)
                    return
                }
                ensureSoundPool()
                bellRings = 0
                renderHandler.removeCallbacks(bellRinger)
                renderHandler.post(bellRinger)
            } else {
                // iOS stopRinging: stop scheduling, but let voices still ringing decay to
                // silence naturally (lastTime → tail-off) — don't cut them.
                renderHandler.removeCallbacks(bellRinger)
                bellRings = BELL_RINGS
            }
        } catch (e: Exception) {
            Log.e(TAG, "alarm bell", e)
        }
    }

    /** Lazily build the SoundPool (USAGE_ALARM) and load Triangle.wav. maxStreams allows the
     *  overlapping decay tails of consecutive 0.5 s strikes to ring together, like iOS. */
    private fun ensureSoundPool() {
        if (soundPool != null) return
        val pool = android.media.SoundPool.Builder()
            .setMaxStreams(BELL_MAX_STREAMS)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        pool.setOnLoadCompleteListener { _, id, status ->
            bellLoaded = (status == 0)
            Log.d(TAG, "alarm sound loaded id=$id status=$status")
        }
        bellSoundId = pool.load(context, R.raw.triangle, 1)
        soundPool = pool
    }

    /**
     * iOS touchesMoved (ChronometerAppDelegate.m:2335-2356): a finger move never becomes a
     * face slide while a stem is out (set mode disables swiping entirely — that's what lets
     * expanded='1' crowns and the advance buttons track the finger loosely), nor while an
     * immediate/repeating button is held. Reads render-thread-owned state from the UI
     * thread: worst case a just-changed value gates one gesture a few ms stale — harmless.
     */
    private fun slideSuppressed(): Boolean {
        if (pressSticky) return true
        val loaded = current ?: return false
        return loaded.time.manualSet || loaded.alarm.manualSet
    }

    /** View px -> face units (XML coordinate space, Y-up, origin at the watch center). */
    private fun unitScale() = min(width / SCREEN_W, height / SCREEN_H).toFloat()

    private fun unitX(px: Float) = (px - width / 2.0) / unitScale()
    private fun unitY(py: Float) = (height / 2.0 - py) / unitScale()

    /** Hit-test on DOWN (render thread): press feedback, immediate actions, or a hand grab. */
    private fun pressDown(px: Float, py: Float) {
        val loaded = current ?: return
        // A ringing alarm is silenced by any touch (iOS stopAlarmRinging); the touch still acts.
        pressSilencedRing = loaded.alarm.stopRinging()
        if (pressSilencedRing) postRender()
        val state = loaded.states[modeIndex]
        val ux = unitX(px)
        val uy = unitY(py)
        val hit = findPartAt(state.watch, state.env, loaded.images, ux, uy) ?: return
        // A grabbable hand (stem out): start a drag-to-set-time session (iOS dragStartAtPoint).
        val grab: Pair<String, DoubleArray>? = when (hit) {
            is QHandPart -> hit.kind!! to dragAnchorOf(hit, state.env)
            is WheelPart -> hit.kind!! to doubleArrayOf(evalAttr(hit.x, state.env), evalAttr(hit.y, state.env))
            else -> null
        }
        if (grab != null) {
            Log.d(TAG, "hand grab: ${hit.name} (${grab.first})")
            // Terra's ring mover: the drag executes the part's ACTION once per 15° notch
            // (iOS moveWorldtimeRingForDeltaAngle) instead of moving a time.
            val ringAction = if (grab.first == com.plasticsmoke.beryl.astro.WORLDTIME_RING_KIND) {
                when (hit) {
                    is WheelPart -> hit.action
                    is QHandPart -> hit.action
                    else -> null
                }
            } else null
            dragSession = HandDragSession(
                loaded.time, grab.first, grab.second[0], grab.second[1], ux, uy,
                alarm = loaded.alarm,
                ringAct = ringAction?.let { action ->
                    { times -> runPartAction(loaded, hit.name, action, times) }
                },
            )
            handGrab = true
            return
        }
        val button = hit as? ButtonPart ?: return
        Log.d(TAG, "button down: ${button.name}")
        pressedButton = button
        pressActed = false
        pressSticky = button.immediate || button.repeats
        // iOS acts on touch-down for immediate buttons and for repeating ones (the first
        // repeat step); everything else acts on touch-up inside the same button.
        state.env.pressedButton = button.name
        // Buttons render live (the depress slides in per frame); only one baked inside a
        // <static> block still needs its sprite rebuilt to show pressed.
        if (button in state.staticBakedButtons) state.cache.invalidate()
        postRender()   // show the depress (act() below also renders, harmlessly coalesced)
        if (button.immediate || button.repeats) {
            act(loaded, button)
        }
        if (button.repeats) {
            // iOS addRepeatingPartTimerForPart: first re-fire after 0.75 s, then repeatJob
            // reschedules itself. NO override yet: iOS acts BEFORE arming the timer, so the
            // touch-down act's sweeps run at NATURAL speed (a pusher tap hops the hour hand
            // in ~0.13 s); only sweeps started by repeat fires get the override — repeatJob
            // sets it before its act. (Our renders are async, so an override set here would
            // wrongly capture the initial act's sweeps too.)
            repeatCount = 0
            repeatNextUptime = SystemClock.uptimeMillis() + FIRST_REPEAT_MS.toLong()
            renderHandler.postAtTime(repeatJob, repeatNextUptime)
        }
    }

    /** Drag move (render thread): turn the grabbed hand — the session re-derives the time. */
    private fun handDragMove(px: Float, py: Float) {
        val session = dragSession ?: return
        session.moveTo(unitX(px), unitY(py))
        postRender()
    }

    /** Release (render thread): act if the finger lifted inside the same button. */
    private fun pressUp(px: Float, py: Float) {
        val loaded = current ?: return
        if (dragSession != null) {
            handDragMove(px, py)
            dragSession = null
            handGrab = false
            post { statusTick() }   // a hand drag warped the time (or moved the alarm)
            return
        }
        val hit = pressedButton
        if (hit != null && !pressActed) {
            val state = loaded.states[modeIndex]
            // iOS touchEnded: with a stem out, an expanded='1' button (every face's crown)
            // tracks the finger LOOSELY — the release acts anywhere inside its expanded
            // bounds, without the closest-part finder having to re-resolve the same button
            // (ChronometerAppDelegate.m:2438). Everything else needs an exact re-hit.
            val setMode = loaded.time.manualSet || loaded.alarm.manualSet
            if (setMode && hit.expanded &&
                com.plasticsmoke.beryl.render.buttonEnclosesExpanded(
                    hit, state.env, loaded.images, unitX(px), unitY(py),
                )
            ) {
                act(loaded, hit)
            } else {
                val stillHit = findPartAt(state.watch, state.env, loaded.images, unitX(px), unitY(py))
                if (stillHit === hit) act(loaded, hit)
            }
        } else if (hit == null && !pressSilencedRing) {
            // A tap that hit nothing toggles the name status line (iOS tapCount==1 path).
            post { toggleNameStatus() }
        }
        cancelPress()
    }

    /** Clear the press state (render thread) — release outside, drag start, or touch cancel. */
    private fun cancelPress() {
        dragSession = null
        handGrab = false
        renderHandler.removeCallbacks(repeatJob)   // iOS cancelRepeatingPartTimers
        val loaded = current
        if (loaded != null) setAnimOverride(loaded, 0)
        val released = pressedButton
        if (released != null && loaded != null) {
            val state = loaded.states[modeIndex]
            state.env.pressedButton = null
            // Live buttons redraw released on the next frame; only <static>-baked ones rebake.
            if (released in state.staticBakedButtons) state.cache.invalidate()
            postRender()
        }
        pressedButton = null
        pressActed = false
        pressSticky = false
        pressSilencedRing = false
    }

    /**
     * Execute the button's action in the current mode's env, then propagate the variable
     * changes to the sibling mode envs (iOS has ONE VM per watch; we build one env per mode,
     * so an action's assignments — Miami's body/bodySlot — must reach all three) and rebake.
     */
    private fun act(loaded: LoadedFace, button: ButtonPart, times: Int = 1) {
        pressActed = true
        runPartAction(loaded, button.name, button.action ?: return, times)
    }

    /** Run a part's action expression [times] times (iOS actNumberOfTimes: fast repeat rates
     *  and multi-notch ring drags batch several acts into one repaint) with the full act
     *  machinery: variable propagation to sibling mode envs, persistence, rebake, status. */
    private fun runPartAction(
        loaded: LoadedFace,
        name: String,
        action: com.plasticsmoke.beryl.expr.ASTNode,
        times: Int = 1,
    ) {
        val env = loaded.states[modeIndex].env
        val before = HashMap(env.variables)
        try {
            env.evaluatingButton = name
            try {
                repeat(times) { com.plasticsmoke.beryl.expr.evaluate(action, env) }
            } finally {
                env.evaluatingButton = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "action failed: $name", e)
            return
        }
        val changed = env.variables.filter { (k, v) -> before[k] != v }
        if (changed.isNotEmpty()) Log.d(TAG, "action $name: $changed")
        // Rebake the static runs ONLY when the action could change what they draw — iOS never
        // re-rasterizes on acts (parts are independent textures), and a rebake per act caps a
        // repeating advanceDay() at ~7 Hz instead of the iOS 10 Hz (measured: 134-167 ms steps
        // vs the iPhone's dead-on 100 ms) while its 30-80 ms frames judder the sweeps. A state
        // rebakes only if the action assigns a variable its baked content reads, or calls a
        // non-pure leaf while that content reads some non-math function (no shipped face does).
        val actionPure = com.plasticsmoke.beryl.render.actionAffectsOnlyVariables(action)
        for ((i, state) in loaded.states.withIndex()) {
            if (i != modeIndex) for ((k, v) in changed) state.env.variables[k] = v
            val touchesStatics = (!actionPure && state.staticVolatileFuncs.isNotEmpty()) ||
                changed.keys.any { it in state.staticVarRefs }
            if (touchesStatics) state.cache.invalidate()
        }
        // Persist the changed vars: face XML state machines (stem `stat`) must survive LRU
        // eviction like iOS's session-resident VM variables (see FaceRepository.persistVars).
        if (changed.isNotEmpty() && currentIdx in faces.indices) {
            repo.persistVars(faces[currentIdx].name, changed)
        }
        postRender()
        // iOS stem/reset/alarm-stem wrappers end with showECStatusMessage:nil — the warp
        // banner appears/refreshes/hides right after the action (Kyoto crown → "Off by …",
        // its reset button → banner gone).
        post { statusTick() }
    }

    /** Show [text] on the status line, fading IN over ECControlFadeTime like iOS's UIView
     *  opacity animation; when [thenFade], hold ECStatusPersistence then let [statusTick]
     *  decide (fade out, or replace with the red warp banner — iOS suTimer → statusTick).
     *  Repeated calls while sliding just update the text without restarting the fade-in. */
    private fun showStatus(text: String, thenFade: Boolean, bgColor: Int = 0) {
        statusText = text
        statusBgColor = bgColor
        removeCallbacks(statusTicker)
        statusFadeOut?.cancel()
        statusFadeOut = null
        if (statusAlpha < 1f && statusFadeIn?.isRunning != true) {
            val fade = ValueAnimator.ofFloat(statusAlpha, 1f)
            fade.duration = (STATUS_FADE_MS * (1f - statusAlpha)).toLong()
            fade.addUpdateListener {
                statusAlpha = it.animatedValue as Float
                invalidate()
            }
            statusFadeIn = fade
            fade.start()
        }
        if (thenFade) {
            // iOS: 5 s persistence normally; 1 s when the warp banner is waiting to reappear.
            postDelayed(statusTicker, if (bannerWanted()) 1000L else STATUS_PERSIST_MS)
        }
        invalidate()
    }

    private fun hideStatus() {
        removeCallbacks(statusTicker)
        statusFadeIn?.cancel()
        statusFadeOut?.cancel()
        val fade = ValueAnimator.ofFloat(statusAlpha, 0f)
        fade.duration = (STATUS_FADE_MS * statusAlpha).toLong()
        fade.addUpdateListener {
            statusAlpha = it.animatedValue as Float
            invalidate()
        }
        statusFadeOut = fade
        fade.start()
    }

    /** True when the red status banner should be up: the watch is off true time, or an alarm
     *  stem is out (iOS showECStatusMessage:nil's decision). */
    private fun bannerWanted(): Boolean {
        val loaded = current ?: return false
        return !loaded.time.isCorrect || loaded.alarm.manualSet
    }

    /**
     * iOS showECStatusMessage:nil — re-evaluate the warp/alarm state: show/refresh the red
     * banner ("Off by …" / "Setting Alarm …"), or fade the line out when all is normal.
     * Self-sustaining while a banner condition holds (iOS reschedules suTimer at 0.1 s), so
     * the offset keeps counting and alarm-hand drags update live. Runs on the UI thread;
     * called after every button action, drag release, and status timeout.
     */
    private fun statusTick() {
        removeCallbacks(statusTicker)
        val loaded = current ?: return
        val time = loaded.time
        val alarm = loaded.alarm
        if (time.isCorrect && !alarm.manualSet) {
            if (statusAlpha > 0f) hideStatus()
            return
        }
        val text: String
        val bg: Int
        if (time.isCorrect) {
            // Alarm stem out on a running watch: live alarm-setting readout.
            bg = BANNER_RED_50
            text = alarmSettingText(alarm)
        } else {
            // iOS: solid red while an alarm stem is ALSO out, translucent otherwise.
            bg = if (alarm.manualSet) BANNER_RED_100 else BANNER_RED_50
            val delta = time.deltaOffsetText()
            text = when {
                time.warp == 1.0 -> "Off by $delta"
                time.warp == -1.0 -> "Off by $delta; running backwards"
                time.runningBackward -> "Off by $delta; stopped backward"
                else -> "Off by $delta; stopped"
            }
        }
        showStatus(text, thenFade = false, bgColor = bg)
        postDelayed(statusTicker, 100L)
    }

    /** iOS warp-banner alarm text while the alarm stem is out. */
    private fun alarmSettingText(alarm: com.plasticsmoke.beryl.astro.AlarmState): String {
        if (alarm.mode == com.plasticsmoke.beryl.astro.AlarmState.Mode.INTERVAL) {
            val rem = Math.round(alarm.effectiveOffset)
            return "Setting Alarm Interval = %02d:%02d:%02d"
                .format(rem / 3600, (rem % 3600) / 60, rem % 60)
        }
        val t = java.time.Instant.ofEpochMilli(alarm.currentAlarmTimeMs)
            .atZone(java.time.ZoneId.systemDefault())
        val h12 = (t.hour % 12).let { if (it == 0) 12 else it }
        return "Setting Alarm @ %2d:%02d %s".format(h12, t.minute, if (t.hour >= 12) "PM" else "AM")
    }

    /** iOS slide format, verbatim: names in screen order, ASCII arrows, fixed gap. */
    private fun slideStatusText(): String {
        val cur = faces[faceIndex].display
        if (slideX == 0f) return cur
        // The neighbor entering from the drag direction (dragging left reveals the next face).
        val peekIdx = wrap(faceIndex + if (slideX < 0) 1 else -1)
        val peek = faces[peekIdx].display
        return if (slideX < 0) "<- $cur                     $peek ->"
        else "<- $peek                     $cur ->"
    }

    /** Fired on the empty-area tap alongside the name toggle — iOS also reveals the corner
     *  status indicator texts (time-sync / alarms / location) with the same gesture. */
    var onEmptyTap: (() -> Unit)? = null

    /** A tap on empty watch area toggles the current name (iOS toggleECStatusNameDisplay).
     *  When the warp banner is due, taps show the name briefly (1 s) and the banner returns —
     *  on iOS a warped watch's banner is persistent, not toggleable. */
    private fun toggleNameStatus() {
        onEmptyTap?.invoke()
        if (bannerWanted()) {
            if (statusBgColor != 0) showStatus(faces[faceIndex].display, thenFade = true)
            else statusTick()
        } else if (statusAlpha > 0f) hideStatus()
        else showStatus(faces[faceIndex].display, thenFade = true)
    }

    private fun drawStatus(canvas: Canvas) {
        val text = statusText
        if (text == null || statusAlpha <= 0f) return
        // iOS warpLabel geometry (ChronometerAppDelegate.m:3782-3792): a 20 pt bar pinned
        // 20 pt below the SAFE-AREA top, systemFontOfSize:16 — pt ≈ dp here. The iPhone's
        // safe area includes the notch; the Android analog is the DISPLAY CUTOUT — with the
        // status bar hidden (fullscreen app) the statusBars inset is 0 and the title would
        // sit behind the punch-hole camera.
        val d = resources.displayMetrics.density
        val inset =
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val types = android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.displayCutout()
                rootWindowInsets?.getInsets(types)?.top ?: 0
            } else 0
        val barTop = inset + 20f * d
        val barH = 20f * d
        val baseSize = 16f * d
        // iOS adjustsFontSizeToFitWidth: full-width single line, shrunk to fit.
        statusPaint.textSize = baseSize
        val maxW = width - 32f
        val w = statusPaint.measureText(text)
        if (w > maxW) statusPaint.textSize = baseSize * maxW / w
        if (statusBgColor != 0) {
            // iOS warpLabel: a full-width translucent bar behind the text.
            statusBarPaint.color = statusBgColor
            statusBarPaint.alpha = (Color.alpha(statusBgColor) * statusAlpha).toInt()
            canvas.drawRect(0f, barTop, width.toFloat(), barTop + barH, statusBarPaint)
        }
        statusPaint.alpha = (statusAlpha * 255).toInt()
        val fm = statusPaint.fontMetrics
        canvas.drawText(text, width / 2f, barTop + barH / 2 - (fm.ascent + fm.descent) / 2, statusPaint)
    }

    init {
        Thread {
            wallpaper = try {
                FaceLoader.bitmap(context.assets, "${FaceLoader.DATA_DIR}/wallpaper-2x.png")
            } catch (e: Exception) {
                Log.e(TAG, "no wallpaper", e)
                null
            }
            post { showFace(faceIndex) }
        }.start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                // A touch during a settle CANCELS it and grabs the slide where it is (iOS
                // animatingPositionZoom=false on touch-move). We're already sliding, so this
                // touch owns a drag from the current slideX — no snap, no waiting for it to land.
                touchMoved = false
                if (slideAnimator?.isRunning == true) {
                    slideAnimator?.cancel()
                    slideAnimator = null
                    dragging = true
                    // Continuity: this finger position maps to the current slideX (inverse of the
                    // STRIDE_RATE drag mapping below), so the grab doesn't snap.
                    slideAnchorX = event.x - slideX / STRIDE_RATE
                    outgoingBitmap = null; outgoingIdx = -1   // fresh gesture; resolver takes over
                } else {
                    dragging = false
                    val px = event.x
                    val py = event.y
                    renderHandler.post { pressDown(px, py) }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // A grabbed hand owns the gesture: every move turns the hand (never a face
                // slide). If a slide engaged first (grab raced the slop check), the slide wins.
                if (handGrab && !dragging) {
                    val px = event.x
                    val py = event.y
                    renderHandler.post { handDragMove(px, py) }
                    return true
                }
                velocityTracker?.addMovement(event)
                if (!dragging) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.2f && !slideSuppressed()) {
                        dragging = true
                        slideAnchorX = downX            // anchor at the touch-down point
                        renderHandler.post { cancelPress() }   // a swipe after all (iOS rule)
                    }
                }
                if (dragging) {
                    touchMoved = true
                    // iOS: finger dx (points) is assigned to drawCenter (watch units), so the
                    // strip moves iPhoneScaleFactor× the finger — one screen-width of finger
                    // travel is exactly one page stride. Same mapping here via STRIDE_RATE.
                    slideX = ((event.x - slideAnchorX) * STRIDE_RATE)
                        .coerceIn(-pageStride(), pageStride())
                    // Keep both neighbor peeks current (normally pre-rendered → these no-op).
                    renderPeekInto(peekNext, wrap(faceIndex + 1))
                    renderPeekInto(peekPrev, wrap(faceIndex - 1))
                    // iOS: while an adjacent watch shows, the status line carries BOTH names.
                    if (slideX != 0f) showStatus(slideStatusText(), thenFade = false)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                if (dragging) {
                    settle()
                } else {
                    val px = event.x
                    val py = event.y
                    renderHandler.post { pressUp(px, py) }
                    performClick()
                }
                velocityTracker?.recycle()
                velocityTracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) settle(cancel = true)
                renderHandler.post { cancelPress() }
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return true
    }

    private fun wrap(i: Int): Int = ((i % faces.size) + faces.size) % faces.size

    /**
     * Center-to-center distance between adjacent sliding pages, ported from iOS's actual
     * geometry: the GL projection maps the screen in WATCH COORDINATES (glOrthof over
     * applicationSizeWatchCoordinates, then glTranslatef(drawCenter)), but the positioning code
     * assigns drawCenter in POINTS — `drawCenter.x = dx + windowWidthPoints · adjacentIndex`
     * (handleTouchMoveWithDeltaX / setAllNoGridPositions). So adjacent watches sit
     * windowWidthPoints WATCH-UNITS apart while the watch itself is 320 units wide: on a 390-pt
     * iPhone that's a 390/320 stride — a 21.9% granite gap between the sliding screens. The
     * same units mismatch makes the strip travel iPhoneScaleFactor× the finger (dx points → dx
     * watch-units), so one screen-width of finger travel lands exactly one page over — see
     * STRIDE_RATE at the drag site.
     */
    private fun pageStride(): Float = width * STRIDE_RATE

    /** Ensure [peek] holds a rendered frame of face [target] (current lights mode), loading it
     *  then rendering on the render thread. Idempotent: a no-op when already assigned [target],
     *  so pre-render + on-demand callers don't double-render. [onReady] is posted to the UI
     *  thread once the frame exists (immediately if it already does). */
    private fun renderPeekInto(peek: Peek, target: Int, onReady: (() -> Unit)? = null) {
        if (peek.idx == target) {
            if (peek.ready) onReady?.let { post(it) }
            return
        }
        peek.idx = target
        peek.ready = false
        repo.load(faces[target]) { loaded ->
            if (loaded == null || peek.idx != target) return@load
            renderHandler.post {
                if (peek.idx != target || width == 0 || height == 0) return@post
                val bmp = peek.bitmap?.takeIf { it.width == width && it.height == height }
                    ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        .also { it.density = Bitmap.DENSITY_NONE; peek.bitmap = it }
                // Peeks show the neighbor's OWN persisted side (iOS watches stay resident on
                // whatever side they were left — a back-mode Tombstone slides away showing
                // its back, not a flash of its front).
                // HOLD: the peek shows the face's LAST-DRAWN pose without touching the sweep
                // state (an iOS hidden watch simply isn't updated) — the face slides in at its
                // stale pose and the hands sweep their true drift live after the reveal. A face
                // never drawn this session shows the current time and winds from 12:00 on reveal.
                val mi = modeFor(faces[target].name).coerceIn(0, FaceRepository.MODES.size - 1)
                if (renderInto(bmp, loaded, mi, com.plasticsmoke.beryl.render.AnimMode.HOLD)) {
                    peek.ready = true
                    postInvalidate()
                    onReady?.let { post(it) }
                }
            }
        }
    }

    /** Pre-render both neighbor peeks for the current face so a slide never waits (iOS keeps
     *  every watch drawable). Cheap when a slot already holds the right neighbor. */
    private fun preloadNeighborPeeks() {
        val n = faces.size
        renderPeekInto(peekNext, (faceIndex + 1) % n)
        renderPeekInto(peekPrev, (faceIndex + n - 1) % n)
    }

    /** Force both peeks to re-render (size or lights-mode changed → their frames are stale). */
    private fun resetPeeks() {
        peekNext.idx = -1; peekNext.ready = false
        peekPrev.idx = -1; peekPrev.ready = false
        ridePeek.idx = -1; ridePeek.ready = false
        preloadNeighborPeeks()
    }

    /**
     * On release: a swipe FLICK (or a drag past half-width) advances one face; else spring back.
     * iOS decides on the swipe GESTURE (touchEndedPossiblySwipingLeft:right:), not a position
     * threshold, so a slow drag-and-release returns home while a quick flick always commits.
     *
     * On commit, faceIndex advances IMMEDIATELY (iOS currentWatchIndex) and slideX re-bases so the
     * new face keeps its on-screen position, then animates to 0 — a fresh touch can grab it mid-flight.
     */
    private fun settle(cancel: Boolean = false) {
        val w = width.toFloat()
        val stride = pageStride()
        var vx = 0f
        velocityTracker?.let { it.computeCurrentVelocity(1000); vx = it.xVelocity }
        // Snap to the nearest page after a short fling projection (iOS-like flick momentum): the
        // centered page is faceIndex - slideX/stride, so shift = round(-(slideX + v·T)/stride),
        // bounded to one step. vx is FINGER velocity; the strip moves STRIDE_RATE× the finger.
        // A pure tap during a slide (no finger travel) keeps the in-flight commit.
        val shift = when {
            cancel || !touchMoved -> 0
            else -> Math.round(-(slideX + vx * STRIDE_RATE * PROJECT_S) / stride).toInt().coerceIn(-1, 1)
        }

        if (shift != 0) {
            commitShift(shift)
            // The committed face is a pre-loaded neighbor: promote it to `current` right here on
            // the UI thread and render it immediately, skipping the loader-thread hop so its frame
            // lands fast enough for even rapid chained swipes. showFace still re-preloads neighbors.
            // The mode swaps WITH the face (its own persisted side): rendering the newcomer even
            // once in the outgoing face's mode flashed the wrong side (MainActivity's per-watch
            // mode apply used to land only after that first frame).
            repo.cached(faces[faceIndex].name)?.let {
                modeIndex = modeFor(faces[faceIndex].name).coerceIn(0, FaceRepository.MODES.size - 1)
                currentIdx = faceIndex
                current = it
                stateSerial++
                postRender(urgent = true)
            }
            showFace(faceIndex)
        } else if (currentIdx != faceIndex) {
            // A grab interrupted a programmatic slide (slideToFace) mid-hop and released without
            // committing further: faceIndex points at an intermediate face the loader never
            // promoted — finish that switch properly or the strip freezes on a stale bridge frame.
            showFace(faceIndex)
        }
        // iOS shows the landed face's name AT RELEASE while the watch is still sliding home.
        showStatus(faces[faceIndex].display, thenFade = true)

        // iOS timing: a committed switch animates a FIXED 0.5 s (setAllNoGridPositionsWithAnimation
        // Interval:0.5); a spring-back moves at kECGLSwipeAnimateSpeed = 1200 pt/s. Both LINEAR.
        animateHome(
            if (shift != 0) COMMIT_SLIDE_MS
            else (abs(slideX) * 1000 / (1200f * (w / SCREEN_W.toFloat()))).toLong().coerceIn(40, COMMIT_SLIDE_MS)
        )
    }

    /** Commit a one-page shift NOW (iOS advances currentWatchIndex the moment a swipe commits):
     *  snapshot the outgoing face so it stays drawable sliding away, bridge the newcomer with its
     *  pre-rendered peek, advance faceIndex and re-base slideX so the strip keeps its on-screen
     *  position. Promoting the newcomer to `current` is the caller's job — settle does it
     *  immediately; slideToFace only at arrival (intermediate faces just whoosh past). */
    private fun commitShift(shift: Int) {
        val committedIdx = wrap(faceIndex + shift)
        val peek = if (shift > 0) peekNext else peekPrev
        // Snapshot the OUTGOING face — advancing to the new current pushes it out of the main
        // buffer, and its peek re-renders slowly. Only if the buffer really holds this face:
        // mid slideToFace the buffer still has the ORIGIN face, not the hop-committed one.
        val out = synchronized(bufLock) {
            if (displayIdx >= 0 && frameFace == faceIndex) buffers[displayIdx] else null
        }
        if (out != null) {
            outgoingBitmap = out.copy(Bitmap.Config.ARGB_8888, false)
                .also { it.density = Bitmap.DENSITY_NONE }
            outgoingIdx = faceIndex
        } else if (centerIdx == faceIndex && centerBitmap != null) {
            // Hop chain: the outgoing face never reached the main buffer — its bridge bitmap
            // is the only frame it has, so hand that off as the departing snapshot.
            outgoingBitmap = centerBitmap
            outgoingIdx = faceIndex
            centerBitmap = null; centerIdx = -1
        }
        // Bridge the swap: stash the newcomer's rendered frame as the center bitmap until its
        // own live frame lands. Detach so the pre-render reallocates, not overwrites. A ride's
        // arrival face lives in ridePeek (the hop peeks were reassigned along the way).
        val bridge = when {
            peek.idx == committedIdx && peek.ready -> peek
            ridePeek.idx == committedIdx && ridePeek.ready -> ridePeek
            else -> null
        }
        bridge?.let {
            centerBitmap = it.bitmap
            centerIdx = committedIdx
            it.bitmap = null; it.idx = -1; it.ready = false
        }
        faceIndex = committedIdx
        slideX += shift * pageStride()
    }

    /** Animate slideX home (0) over [durationMs], linear like iOS scrollIntoPosition. [onEnd]
     *  runs only if the slide LANDS — a new touch grabbing the strip mid-flight cancels the
     *  chain (and keeps slideX where the finger caught it, so the grab doesn't snap). */
    private fun animateHome(durationMs: Long, onEnd: (() -> Unit)? = null) {
        val anim = ValueAnimator.ofFloat(slideX, 0f)
        anim.duration = durationMs
        anim.interpolator = android.view.animation.LinearInterpolator()
        anim.addUpdateListener {
            slideX = it.animatedValue as Float
            invalidate()
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            private var grabbed = false
            override fun onAnimationCancel(animation: Animator) { grabbed = true }
            override fun onAnimationEnd(animation: Animator) {
                if (slideAnimator === animation && !grabbed) {   // not cancelled by a new touch
                    dragging = false
                    slideX = 0f
                    slideAnimator = null
                    outgoingBitmap = null; outgoingIdx = -1   // slide done — drop the snapshot
                    invalidate()
                    onEnd?.invoke()
                }
            }
        })
        slideAnimator = anim
        anim.start()
    }

    /** Set the displayed mode (0=front, 1=night, 2=back — the FaceRepository.MODES order).
     *  [animateFlip] plays the iOS 3-D turn (backFlip's allowAnimation:true); without it the
     *  mode snaps, like iOS nightFlip/dayFlip (allowAnimation:false) and face switches. */
    fun setModeIndex(index: Int, animateFlip: Boolean = false) {
        val idx = index.coerceIn(0, FaceRepository.MODES.size - 1)
        val serial = ++modeOrderSerial   // stale any flip still waiting on its pre-render
        flipRequest = null               // a newer order supersedes a not-yet-started flip
        // A mode order while a turn is in flight snaps the turn to done first (iOS
        // setCurrentModeNum during animatingFlip sets the mode without animating).
        flipAnimator?.let { running ->
            flipAnimator = null
            running.cancel()
            flipAngle = 0f
            flipBitmapMode = -1
            invalidate()
        }
        // No-op unless the mode actually changes: MainActivity re-applies the mode on EVERY
        // face switch (per-visit flip reset), and seeding then would snap away the drift the
        // reveal is supposed to sweep (the hands-adjust animation).
        if (idx == modeIndex) return
        val loaded = current
        if (!animateFlip || loaded == null || width == 0 || height == 0) {
            applyModeSwap(idx)
            return
        }
        Log.d(TAG, "flip order: mode $modeIndex -> $idx")
        // iOS startFlipAnimation prepares the hidden side BEFORE the turn begins
        // (prepareAllPartsForDrawForModeNum forcingUpdate + attachTexture): pre-render the
        // target side so the second half of the turn always has a frame to show. The request
        // rides renderJob (see FlipRequest) — postRender guarantees an immediate run.
        flipRequest = FlipRequest(idx, serial, loaded)
        postRender(urgent = true)
    }

    /** Swap the mode NOW: seed the next render (iOS updateAllParts animating:false).
     *  Neighbor peeks are untouched: modes are PER-WATCH, so changing this face's side
     *  never changes what the neighbors' peeks should show. */
    private fun applyModeSwap(idx: Int) {
        modeIndex = idx
        seedNextRender = true
        stateSerial++
        postRender(urgent = true)
    }

    /** The iOS flip (ECGLWatch drawForModeNum, kECGLFlipAnimationTime = 0.5 s): a LINEAR
     *  turn about the watch's vertical axis. First half rotates the shown side 0→-90°
     *  (right edge toward the eye); at halfway the content swaps to the target side, which
     *  rotates in from +90° back to 0. */
    private fun startFlip(target: Int, serial: Int) {
        // A newer mode order (face-switch reset, another flip) raced the pre-render: it wins.
        if (serial != modeOrderSerial || modeIndex == target) {
            Log.d(TAG, "flip start SKIPPED: serial $serial vs $modeOrderSerial, mode $modeIndex vs $target")
            return
        }
        Log.d(TAG, "flip start: mode $modeIndex -> $target")
        var swapped = false
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = FLIP_MS
        anim.interpolator = android.view.animation.LinearInterpolator()
        anim.addUpdateListener {
            val t = it.animatedValue as Float
            if (!swapped && t >= 0.5f) {
                swapped = true
                applyModeSwap(target)
            }
            flipAngle = if (t < 0.5f) -t * 180f else (1f - t) * 180f
            invalidate()
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (flipAnimator === animation) {   // not snapped by a newer mode change
                    if (!swapped) applyModeSwap(target)
                    flipAnimator = null
                    flipAngle = 0f
                    flipBitmapMode = -1
                    invalidate()
                }
            }
        })
        flipAnimator = anim
        anim.start()
    }

    /** Draw [bmp] turned [flipAngle]° about the vertical axis through the watch center with
     *  the iOS perspective: the eye sits zoomFactor(=3) × halfWidth from the watch plane
     *  (ECGLWatch zTranslation), i.e. 1.5 screen-widths. Camera.setLocation is in inches of
     *  72 px. Negative angles bring the right edge toward the eye, matching the GL turn. */
    private fun drawFlipped(canvas: Canvas, bmp: Bitmap, x: Float) {
        flipCamera.save()
        flipCamera.setLocation(0f, 0f, -width * 1.5f / 72f)
        flipCamera.rotateY(flipAngle)
        flipCamera.getMatrix(flipMatrix)
        flipCamera.restore()
        flipMatrix.preTranslate(-width / 2f, -height / 2f)
        flipMatrix.postTranslate(width / 2f + x, height / 2f)
        canvas.drawBitmap(bmp, flipMatrix, blitPaint)
    }

    /** iOS Background.xml 'gotoAlarmer' (switchToNextActiveAlarmWatch): scan FORWARD from
     *  the current face for one with an armed (or ringing) alarm and slide to it. */
    fun showNextAlarmFace() {
        for (k in 1..faces.size) {
            val i = wrap(faceIndex + k)
            val a = repo.states(faces[i].name).alarm
            if (a.enabled || a.intervalRunning || a.ringing) {
                slideToFace(i)
                return
            }
        }
    }

    /** Slide the strip to face [target] through the intervening faces — iOS switchToWatchNumber:
     *  `setAllNoGridPositionsWithAnimationInterval:0.5 oneDirectionOnly:true`. iOS lays the ring
     *  out at signed offsets ±n/2 around the current watch and the target slides in from its old
     *  spot, so the motion goes the SHORTEST way around, one continuous direction, ~0.5 s total
     *  (per-hop floor keeps long trips from strobing). The target's name shows as the slide
     *  starts (iOS showECStatusMessage right after the position animation is kicked off). */
    fun slideToFace(target: Int) {
        val t = wrap(target)
        if (t == faceIndex) return
        if (slideAnimator?.isRunning == true || dragging) {
            showFace(t)   // strip already in motion under a finger or slide — snap instead
            return
        }
        val n = faces.size
        var hops = ((t - faceIndex) % n + n) % n
        var dir = 1
        if (hops > n / 2) { hops = n - hops; dir = -1 }
        val hopMs = maxOf(COMMIT_SLIDE_MS / hops, MIN_HOP_MS)
        showStatus(faces[t].display, thenFade = true)
        // iOS's target watch is always resident and drawable; ours may be LRU-evicted — and a
        // load warmed at ride start gets re-evicted by the hops' own peek loads (MAX_LOADED is
        // small). So gate the ride on a RENDERED FRAME of the arrival face in its own slot:
        // the landing bridges from that bitmap while the live reload fills in behind it, and
        // the "Loading …" placeholder never draws. The gate wait (~a load, sub-300 ms) reads
        // as reaction latency; a failed/slow load starts the ride anyway and lands honestly.
        val serial = ++rideSerial
        var started = false
        val start = fun() {
            if (serial != rideSerial || started) return
            started = true
            if (dragging || slideAnimator?.isRunning == true) showFace(t)  // finger got there first
            else hopToward(t, dir, hopMs)
        }
        ridePeek.idx = -1; ridePeek.ready = false
        renderPeekInto(ridePeek, t, onReady = start)
        postDelayed(start, RIDE_GATE_MAX_MS)
        // Best-effort warm of the intermediates (in ride order, queued behind the arrival's
        // load) so the hop peeks find them cached and they show as they whoosh past. Only for
        // short rides: the single loader thread (~200 ms/face) can't outrun 80 ms hops, and a
        // long warm queue would just delay the arrival face's live reload after landing.
        if (hops <= 3) {
            var k = wrap(faceIndex + dir)
            while (k != t) { repo.load(faces[k]); k = wrap(k + dir) }
        }
    }

    /** One page of a slideToFace ride: commit the shift, then either land (full showFace
     *  machinery) or pre-render the next face along the ride and chain the next hop. */
    private fun hopToward(target: Int, dir: Int, hopMs: Long) {
        commitShift(dir)
        if (faceIndex == target) {
            showFace(target)   // arrival: promote, live render, neighbor preloads, status
            animateHome(hopMs)
        } else {
            val peek = if (dir > 0) peekNext else peekPrev
            renderPeekInto(peek, wrap(faceIndex + dir))
            animateHome(hopMs) { hopToward(target, dir, hopMs) }
        }
    }

    /** Switch to face [index] (wrapping), loading it if needed, and preload its neighbors.
     *  The display mode carries over (the iPhone's lights-out state is app-global too). */
    fun showFace(index: Int) {
        val n = faces.size
        val idx = ((index % n) + n) % n
        faceIndex = idx
        onFaceShown(idx)
        // iOS shows the watch name at startup and on every programmatic switch (picker/list),
        // not just swipe releases (ChronometerAppDelegate.m:4132, :1961).
        showStatus(faces[idx].display, thenFade = true)
        val face = faces[idx]
        repo.load(face) { loaded ->
            // Loader thread. Ignore stale completions after further swipes.
            if (faceIndex == idx && loaded != null) {
                // Set currentIdx BEFORE current so a render-thread reader seeing the new `current`
                // always sees the matching index (frameFace tagging stays correct across a commit).
                // The mode swaps WITH the face (see settle) — the newcomer renders its own side.
                modeIndex = modeFor(face.name).coerceIn(0, FaceRepository.MODES.size - 1)
                currentIdx = idx
                current = loaded
                stateSerial++
                postRender(urgent = true)
                // Pre-render both neighbors so the next slide reveals them instantly (iOS keeps
                // all watches resident). repo.load of the neighbors happens inside renderPeekInto.
                preloadNeighborPeeks()
                maybeStartWarmup()
            }
        }
    }

    /**
     * iOS's background loader works through EVERY watch shortly after launch, and each load
     * ends with a snap of all values to that moment (loadFromArchive → updateAllParts
     * animating:false). That anchor is why every switch on the iPhone shows the hands
     * catching up: the drift is measured from session start, not from moments before the
     * reveal. Emulate it: trickle one HOLD render of each face (creating its sweep state
     * snapped at ~launch time) through the loader, spaced out to stay off the user's way.
     * The LRU churns while warming, but peek bitmaps survive eviction and reveals reload.
     */
    private fun maybeStartWarmup() {
        if (warmupStarted) return
        warmupStarted = true
        renderHandler.postDelayed({ warmFace(0) }, WARMUP_START_MS)
    }

    private fun warmFace(i: Int) {
        if (i >= faces.size) {
            warmupBitmap?.recycle()
            warmupBitmap = null
            // Re-load the current neighborhood: warming evicted it from the LRU.
            val n = faces.size
            repo.load(faces[faceIndex]) {}
            repo.load(faces[(faceIndex + 1) % n]) {}
            repo.load(faces[(faceIndex + n - 1) % n]) {}
            return
        }
        repo.load(faces[i]) { loaded ->
            renderHandler.post {
                if (loaded != null && width > 0 && height > 0) {
                    val bmp = warmupBitmap?.takeIf { it.width == width && it.height == height }
                        ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            .also { it.density = Bitmap.DENSITY_NONE; warmupBitmap = it }
                    // HOLD: creates absent sweep state snapped to NOW (the load-snap) and
                    // never disturbs faces the user has already visited.
                    val mi = modeFor(faces[i].name).coerceIn(0, FaceRepository.MODES.size - 1)
                    renderInto(bmp, loaded, mi, com.plasticsmoke.beryl.render.AnimMode.HOLD)
                }
                renderHandler.postDelayed({ warmFace(i + 1) }, WARMUP_STEP_MS)
            }
        }
    }

    fun currentFaceIndex(): Int = faceIndex

    /** Queue a frame render, coalescing with any not-yet-started job.
     *
     *  [urgent] front-inserts, for USER-ACTION renders only (swipe commit, flip, mode change,
     *  face load): the visible face must beat queued neighbor pre-renders. The steady
     *  Choreographer tick must NOT front-insert — postAtFrontOfQueue enqueues at when=0, and
     *  on a face that renders back-to-back (Tombstone) a per-quantum stream of when=0 inserts
     *  STARVES every plain-posted job forever (touch handlers, peek renders, warmup — the
     *  same pathology that blocked the flip pre-render). Fair FIFO ticks keep them all live. */
    private fun postRender(urgent: Boolean = false) {
        renderHandler.removeCallbacks(renderJob)
        if (urgent) renderHandler.postAtFrontOfQueue(renderJob) else renderHandler.post(renderJob)
    }

    /** Render a full frame of [loaded] in mode [mi] into [bmp]; false on a render failure.
     *  [animMode]: LIVE = sweep normally; HOLD = snapshot at the last-drawn pose (peeks);
     *  SEED = snap state to targets (mode flips — iOS updateAllParts animating:false). */
    private fun renderInto(
        bmp: Bitmap,
        loaded: LoadedFace,
        mi: Int,
        animMode: com.plasticsmoke.beryl.render.AnimMode = com.plasticsmoke.beryl.render.AnimMode.LIVE,
    ): Boolean {
        val w = bmp.width
        val h = bmp.height
        val pool = layerPool?.takeIf { it.width == w && it.height == h }
            ?: LayerBitmapPool(w, h).also { layerPool = it }

        // Frames are TRANSPARENT around the watch: the wallpaper is composited in onDraw as a
        // fixed base layer, so slides move only the watch over stationary granite (like the
        // iPhone — the wallpaper does not travel with the watch).
        bmp.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bmp)

        // iOS logical screen is 320×480; fit it in the view, centered (renderFrame centers itself).
        val scale = min(w / SCREEN_W, h / SCREEN_H)
        val ctx = AndroidCanvasDrawingContext(canvas, w, h, context.assets, pool)
        val state = loaded.states[mi]
        state.cache.bakeUprightHands = true   // thin-strip hand sprites (cheap rotated blits)
        // Alarm firing rides the render loop (a visible face redraws at its part cadence);
        // the bell sound tracks the CURRENT face's ringing state.
        loaded.alarm.checkFire()
        if (loaded === current) {
            val ringing = loaded.alarm.ringing
            if (ringing != wasRinging) {
                wasRinging = ringing
                setBellPlaying(ringing)
            }
        }
        // The face's own time base (stem-stopped, time-traveled, reversed…) drives the frame.
        state.clock.startFrame(loaded.time.displayedNow())
        return try {
            // animateAtRealMs enables the iOS hand-sweep (hands chase their targets at
            // animSpeed): a freshly shown face winds its hands into place, jumps sweep.
            renderFrame(
                ctx, state.watch, state.env, scale, w, h, loaded.images, state.cache,
                state.clock.beat, animateAtRealMs = System.currentTimeMillis(),
                animMode = animMode,
            )
            true
        } catch (e: Exception) {
            // A porting gap in one face (e.g. an unregistered leaf) must not kill the app.
            Log.e(TAG, "render failed mode=$mi", e)
            false
        }
    }

    private val renderJob: Runnable = object : Runnable {
        override fun run() {
        val serial = stateSerial   // validated before promoting: stale pairs never display
        val loaded = current ?: return
        val ci = currentIdx     // the face `loaded` holds (set with `current` in showFace)
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        // A pending flip order: pre-render its hidden side FIRST (iOS prepares the flip side
        // before the turn begins), then hand the animation start back to the UI thread.
        flipRequest?.let { req ->
            flipRequest = null
            val fb = flipBitmap?.takeIf { it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    .also { it.density = Bitmap.DENSITY_NONE; flipBitmap = it }
            // SEED: iOS prepares the flip side with allowAnimation:false (snap, no sweep).
            flipBitmapMode =
                if (renderInto(fb, req.loaded, req.target, com.plasticsmoke.beryl.render.AnimMode.SEED)) req.target else -1
            post { startFlip(req.target, req.serial) }
        }
        val t0 = SystemClock.uptimeMillis()

        // Pick a buffer that is neither displayed nor pending display.
        val idx = synchronized(bufLock) { (0..2).first { it != pendingIdx && it != displayIdx } }
        val bmp = buffers[idx]?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                .also { it.density = Bitmap.DENSITY_NONE; buffers[idx] = it }

        val mi = modeIndex
        val mode = if (seedNextRender) com.plasticsmoke.beryl.render.AnimMode.SEED
        else com.plasticsmoke.beryl.render.AnimMode.LIVE
        seedNextRender = false
        if (!renderInto(bmp, loaded, mi, mode)) return

        val tEnd = SystemClock.uptimeMillis()
        // At fast beat rates this runs every frame — log a sample, not the firehose.
        if (frameCount++ % 60 == 0L || tEnd - t0 > 150) {
            val cache = loaded.states[mi].cache
            Log.d(
                TAG,
                "frame face=${if (ci in faces.indices) faces[ci].name else "?"} mode=$mi ${tEnd - t0}ms" +
                    " override=${cache.animOverrideMs}" +
                    " animating=${cache.animatingParts().take(8)}",
            )
        }
        // A commit/mode-swap raced this render: the (face, mode) pair it drew is stale —
        // DISCARD it (never display the wrong side) and render again with the new state.
        if (stateSerial != serial) {
            if (mode == com.plasticsmoke.beryl.render.AnimMode.SEED) seedNextRender = true
            postRender()
            return
        }
        // Promote the buffer and its face/mode tags as ONE atomic tuple under bufLock — tags
        // published outside the lock let onDraw promote a buffer and read stale tags (a
        // single-frame wrong-face/wrong-side flash during rapid swipe+flip).
        synchronized(bufLock) {
            pendingIdx = idx
            frameFace = ci
            frameMode = mi
        }
        // A hand-sweep in progress needs continuous frames until it settles (iOS keeps
        // redrawing while any part is animating); target ~60 fps ACCOUNTING for the frame's
        // own render time — a fixed 16 ms delay ON TOP of a ~15-30 ms render paced sweeps at
        // 22-30 fps and made short slides visibly steppy. Slow frames repost immediately.
        if (loaded.states[modeIndex].cache.isAnimating) {
            renderHandler.postDelayed(this, (16 - (tEnd - t0)).coerceAtLeast(0))
        }
        postInvalidate()
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            // Render when the face's fastest part-update epoch rolls over: Paris re-renders once
            // a minute's worth of seconds, Geneva every 100 ms (its seconds tick), Tombstone every
            // frame (balance wheel update ≈ 8 ms → clamped to the display rate). The render thread
            // coalesces if frames outpace it.
            val loaded = current
            if (loaded != null && width > 0) {
                val quantum = loaded.states[modeIndex].minUpdateMs
                // Tick on the DISPLAYED time, not real time: part epochs derive from the face's
                // own time base, so after a time-set the redraw must land ON the displayed
                // epoch rollovers (real-time ticks drew each rollover up to a quantum late —
                // iOS schedules exact fire times converted watch→phone, ECDynamicUpdate.m:202).
                val tick = loaded.time.displayedNow() / quantum
                if (tick != lastPostedTick) {
                    lastPostedTick = tick
                    postRender()
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(frameCallback)
        renderHandler.removeCallbacks(alarmScan)
        renderHandler.postDelayed(alarmScan, ALARM_SCAN_MS)
        ensureSoundPool()   // decode Triangle.wav ahead of any fire so ring 1 isn't dropped
    }

    /** Stop the frame driver and silence the bell while the Activity is paused. iOS suspends
     *  rendering in the background; without this a continuously-animating face keeps software-
     *  rendering full frames (and a ringing alarm keeps ringing) the whole time the app is
     *  home-screened — the Choreographer keeps firing for a paused-but-attached window. */
    fun pause() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        renderHandler.removeCallbacks(renderJob)
        renderHandler.removeCallbacks(alarmScan)
        setBellPlaying(false)
        wasRinging = false
    }

    fun resume() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)   // never double-register
        Choreographer.getInstance().postFrameCallback(frameCallback)
        renderHandler.removeCallbacks(alarmScan)   // never double-schedule
        renderHandler.postDelayed(alarmScan, ALARM_SCAN_MS)
        postRender()
    }

    override fun onDetachedFromWindow() {
        setBellPlaying(false)
        soundPool?.release()
        soundPool = null; bellLoaded = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        renderHandler.removeCallbacks(renderJob)
        renderHandler.removeCallbacks(alarmScan)
        renderThread.quitSafely()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        postRender()
        resetPeeks()   // peek bitmaps are sized to the view — re-render both at the new size
    }

    /** The stationary base layer: granite scaled to the view (built once per size, UI thread). */
    private fun drawWallpaper(canvas: Canvas) {
        val wp = wallpaper
        if (wp == null) {
            canvas.drawColor(Color.BLACK)
            return
        }
        val scaled = scaledWallpaper?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createScaledBitmap(wp, width, height, true)
                .also { it.density = Bitmap.DENSITY_NONE; scaledWallpaper = it }
        canvas.drawBitmap(scaled, 0f, 0f, null)
    }

    /** The best available bitmap for face [idx] right now: the live main buffer if it holds that
     *  face, else a pre-rendered neighbor peek, else the swap-bridge center bitmap. */
    private fun bitmapFor(idx: Int, mainBuffer: Bitmap?, bufFace: Int): Bitmap? = when {
        idx == bufFace && mainBuffer != null -> mainBuffer
        idx == peekNext.idx && peekNext.ready -> peekNext.bitmap
        idx == peekPrev.idx && peekPrev.ready -> peekPrev.bitmap
        idx == ridePeek.idx && ridePeek.ready -> ridePeek.bitmap   // slide arrival, approaching
        idx == centerIdx -> centerBitmap
        idx == outgoingIdx -> outgoingBitmap   // the departing face's held snapshot
        else -> null
    }

    override fun onDraw(canvas: Canvas) {
        // Promote the pending buffer and snapshot its face/mode tags as one consistent tuple
        // (the render thread publishes all three under the same lock).
        var bufFace: Int
        var bufMode: Int
        val mainBuffer = synchronized(bufLock) {
            bufFace = frameFace
            bufMode = frameMode
            if (pendingIdx >= 0) {
                displayIdx = pendingIdx
                buffers[displayIdx]
            } else null
        }
        // Once the committed face's own frame has landed, drop the swap-bridge bitmap.
        if (centerIdx == bufFace) { centerIdx = -1; centerBitmap = null }

        drawWallpaper(canvas)

        val w = width.toFloat()
        val stride = pageStride()
        // Composite the current face and any neighbor within view: face (faceIndex + d) sits at
        // slideX + d·stride (a granite gap wider than one screen so watches aren't edge-to-edge).
        // Each is drawn from whatever buffer currently holds it — never the wrong face, so rapid
        // chained swipes can't flash a stale neighbor. Cull by the bitmap's own width.
        var drewAny = false
        for (d in -1..1) {
            val x = slideX + d * stride
            if (x <= -w || x >= w) continue        // fully off-screen
            val idx = wrap(faceIndex + d)
            if (d == 0 && flipAngle != 0f) {
                // Mid-flip the main buffer is only right for the SIDE it holds: after the
                // halfway swap, bridge with the pre-rendered flip side until the new side's
                // own live frame lands (iOS never has this gap — all sides stay resident).
                val bmp = when {
                    bufMode == modeIndex && bufFace == idx && mainBuffer != null -> mainBuffer
                    flipBitmapMode == modeIndex -> flipBitmap
                    else -> bitmapFor(idx, mainBuffer, bufFace)
                }
                if (bmp != null) {
                    drawFlipped(canvas, bmp, x)
                    drewAny = true
                }
                continue
            }
            val bmp = bitmapFor(idx, mainBuffer, bufFace) ?: continue
            canvas.drawBitmap(bmp, x, 0f, blitPaint)
            drewAny = true
        }
        if (!drewAny && slideX == 0f) {
            canvas.drawText("Loading ${faces[faceIndex].display}…", width / 2f, height / 2f, labelPaint)
            return
        }
        drawStatus(canvas)
    }

    private companion object {
        const val TAG = "Beryl"
        const val SCREEN_W = 320.0
        const val SCREEN_H = 480.0
        // The status line (iOS warpLabel): geometry computed in drawStatus from the safe-area
        // inset + dp (iOS pins it in points); ECStatusPersistence/ECControlFadeTime timings.
        const val STATUS_PERSIST_MS = 5000L
        // iOS warp banner red: colorWithRed:0.75 green:0 blue:0 at alpha .5 / 1.0.
        const val BANNER_RED_50 = 0x80BF0000.toInt()
        const val BANNER_RED_100 = 0xFFBF0000.toInt()
        const val STATUS_FADE_MS = 330L
        // iOS setAllNoGridPositionsWithAnimationInterval:0.5 — the face-switch slide time.
        // A slideToFace ride spreads it across the hops; the floor keeps far targets from
        // strobing past faster than a frame or two per face (iOS blits residents at any speed).
        const val COMMIT_SLIDE_MS = 500L
        const val MIN_HOP_MS = 80L
        // slideToFace waits for the arrival face's frame at most this long before riding anyway.
        const val RIDE_GATE_MAX_MS = 800L
        // iOS kECGLFlipAnimationTime — the front↔back 3-D turn.
        const val FLIP_MS = 500L
        // iOS ECAudio defaults: ECAlarmRings (20) strikes at ECAlarmRepeatInterval (0.5 s).
        const val BELL_RINGS = 20
        const val BELL_REPEAT_MS = 500L
        // Overlapping decay tails of consecutive 0.5 s strikes ring together; a ~2.5 s audible
        // tail over 0.5 s spacing means ~5 voices can sound at once.
        const val BELL_MAX_STREAMS = 6
        // iOS button auto-repeat cadence (esastro/src/ECConstants.h:103-106): first re-fire
        // after 0.75 s, then 0.25 s, log-accelerating to 0.1 s by iteration 11 (strategy
        // AcceleratesOnce) or 0.02 s by 35 (AcceleratesTwice); 50 ms timer floor (modern hw).
        const val FIRST_REPEAT_MS = 750.0
        const val REPEAT_MS = 250.0
        const val FAST_REPEAT_MS = 100.0
        const val FASTER_REPEAT_MS = 20.0
        const val MIN_REPEAT_MS = 50.0
        // Background-face alarm check cadence (iOS uses exact per-watch NSTimers; a 1 s poll
        // of the session-resident alarm states lands within the bell's own ring granularity).
        const val ALARM_SCAN_MS = 1000L
        // Session warmup pacing: start after launch settles, one face every couple seconds.
        const val WARMUP_START_MS = 4000L
        const val WARMUP_STEP_MS = 1500L
        // Fling projection horizon (s): a quick flick carries slideX this much further when
        // deciding which face to snap to, so a short fast swipe still commits.
        const val PROJECT_S = 0.16f
        // iOS's watch spacing falls out of a units mismatch: drawCenter is consumed in WATCH
        // COORDINATES (the glOrthof projection spans applicationSizeWatchCoordinates) but is
        // ASSIGNED window-width POINTS per adjacent index — so the stride is windowWidthPoints
        // watch-units against a 320-unit watch, i.e. stride/watch = devicePointWidth/320
        // (= iPhoneScaleFactor, width-limited). 390 pt is the modern-iPhone baseline width.
        // The same mismatch scales finger dx (points) onto the strip (watch units).
        const val STRIDE_RATE = 390f / 320f
    }
}
