# Devlog — Beryl Chronometer (Android port of Emerald Chronometer)

A chronological, step-by-step record of how this port came together, so it's easy to
retrace the reasoning and decisions later. Newest work at the bottom of each section.

Target: a native Android app that matches the original iPhone
app's "native feel" — no WebView, smooth hands at the display's refresh rate.

---

## 0. The question

Emerald Chronometer (an early-2008 iPhone astronomical watch app by Emerald Sequoia) went
open source. Was a native Android version feasible?

## 1. Research — what's actually on GitHub

Looked at the [EmeraldSequoia org](https://github.com/EmeraldSequoia). Findings:

- **`Chronometer`** — the original iOS/iPad app, Objective-C++. Not directly portable.
- **`chronometer-web`** — a complete, MIT-licensed **TypeScript** re-implementation that
  renders the faces to HTML Canvas, runs fully client-side, *still under active development*.
  This is the gold mine and the authoritative reference for the port.
- **`esastro` / `estime` / `eslocation`** — shared C/C++ astronomy/time/location libraries.
- A Wear OS (Android) version existed but was **not** open-sourced (deprecated APIs).

Three facts made a native port unusually tractable:
1. **Faces are data, not code** — each face is an XML definition + image assets, reused verbatim.
2. **1:1 drawing API** — the web port renders via Canvas 2D, which maps almost directly onto
   Android's `android.graphics.Canvas`. (The original iOS app went through an OpenGL
   preprocessor; the web port proved you can skip that and render straight from the XML.)
3. **A golden-output oracle exists** — chronometer-web ships a vitest test suite, so every
   Kotlin module can be validated against ported tests and won't silently drift.

## 2. Decisions

- **Go native** (not a WebView/PWA wrapper) — browser-link latency was the whole reason to leave.
- **Port TS → Kotlin**, reusing the XML face data + image assets as-is.
- **Engine as a pure Kotlin/JVM module** (`:engine`) with no Android dependency, so the
  platform-independent core (expressions, astronomy, calendar, XML model) compiles and unit-tests
  with only a JDK. A thin Android `:app` module sits on top for the renderer + system glue.
- **Astronomy**: port to Kotlin (single language, validate against the vitest oracle) rather than
  reuse the C libs via NDK.
- **Render host**: a custom `View` driven by `Choreographer` (most direct match to the web port's
  render loop) — to be built in `:app`.
- **First milestone**: render **Milano** (the simplest face, 5 KB XML) end-to-end, natively, smoothly.

## 3. Walking-skeleton plan

Don't port 30k LOC up front. Build one face end-to-end, verifying each module against the oracle:
`expr` → project scaffold → `astro` (Milano scope) → XML parser → renderer → `:app` shell → Milano.

---

## Session log (2026-06-11)

### Step 1 — Scaffold + `expr` module
- Created the Gradle multi-module project under `android/`: `:engine` (pure Kotlin/JVM) plus a
  scaffolded `:app` (not yet enabled in `settings.gradle.kts`).
- Ported the C-expression engine TS → Kotlin: `Tokenizer.kt`, `Parser.kt`, `Evaluator.kt` under
  `com.plasticsmoke.beryl.expr`. Preserved the JS semantics that bite silently: ToInt32 bitwise ops,
  unsigned-32-bit hex color constants, `Math.round` (half-up), JS truthiness in `&&`/`||`/`?:`/`!`.
- Ported the vitest suite as `ExprTest.kt`.
- Staged the Milano face data (`Milano-I.xml` + `partsBin/berryWhite.png`).

### Step 2 — Toolchain
- This machine had JDK 21 but no Android SDK / Gradle / Kotlin. Found a **cached Gradle 8.9** and
  a cached `kotlin-stdlib 1.9.20`, so pinned the project to **Kotlin 1.9.20** to minimize downloads.
- Generated the Gradle wrapper jar; ran `:engine:test`. → **expr: 81 tests, all green.**

### Step 3 — Version control
- Made `android/` its own git repository.

### Step 4 — `astro` module (Milano scope)
- Ported the slice of the astronomy/calendar the Milano face needs:
  - `EsCalendar.kt` — hybrid Julian/Gregorian calendar (from `es-calendar.ts`).
  - `EsTime.kt` — ESTimeInterval ↔ epoch-millis (Apple epoch = 2001-01-01).
  - `WatchEnvironment.kt` — `registerWatchFunctions(env, tzOffsetSec, TimeSource)` registers
    `hour12/minute/secondValueAngle`, `hour24Number`, `minuteNumber`, `day/month/weekday/year/
    eraNumber` (+ angle siblings) into an expr `Environment`. Battery functions default to
    "unsupported" and will be overridden by the Android layer via `BatteryManager`.
- Deferred: sun/moon/planet astronomy + Delta-T/Julian machinery (the bulk of `es-time.ts`).
- Validated the calendar against `java.time` for the Gregorian range + fixed points (Apple epoch =
  Monday, Unix epoch = Thursday, the 1582 switchover). One useful catch: the calendar's *time-of-day*
  fields carry the original algorithm's sub-day float imprecision — harmless, because faces read
  time-of-day from the live clock, not the calendar. → **+13 tests (94 total).**

### Step 5 — Android SDK installed
- Installed via command-line tools to `~/Android/Sdk`: **build-tools 35.0.0, platform-tools 37.0.0,
  platforms;android-35** (licenses accepted, `adb` present). Unblocks building `:app` later.

### Step 6 — XML parser
- Ported the part **model** (`WatchModel.kt`, from `types.ts`) and **parser** (`WatchXmlParser.kt`,
  from `xml-parser.ts`), scoped to Milano's part types: `QDial / QHand / Image / QRect / Static /
  Window` plus `init` / `atlas`. Other part types skip silently (a clean extension seam), matching
  the reference's `default` case. Uses JDK DOM (`javax.xml`, present on JVM and Android).
- `WatchXmlParserTest.kt` mirrors the reference's small-XML cases and validates against the real
  `Milano-I.xml`: name/beatsPerSecond/faceWidth/bezel, 7 init blocks, the front Static with 9
  children, 7 QDials + 1 QRect + 1 Image + 7 QHands, and specific part attributes/expressions.
  → **+7 tests (101 total).**

### Step 7 — Renderer: Milano's static layer
- Introduced a small platform-independent **`DrawingContext`** interface (`com.plasticsmoke.beryl.render`)
  — the Canvas-2D subset the renderer needs (save/restore/transform, fill rect/circle/ring, stroke,
  drawImage, text). This keeps the renderer logic in `:engine` (pure, testable) with thin adapters
  per platform: `android.graphics.Canvas` for the app, `java.awt.Graphics2D` for headless tests.
- `StaticRenderer.kt` ports the static-drawing paths of `renderer.ts`: the `translate(center)+scale`
  transform, per-part Y-negation, QRect, QDial (background + text in upright/demi/rotated/radial
  orientations), Image, and a simplified solid-ring bezel. ARGB color handling via `argbToCSS`-equivalent
  helpers. (QDial tick/dot/rose marks, Windows, and the dynamic hands are deferred.)
- `StaticRenderTest.kt` renders the **real Milano face** through an AWT adapter to a PNG
  (`docs/milano-static.png`) and asserts key pixels (black mask center, ~190 grey bezel, non-trivial
  rendering). The result is correct: grey bezel, black face, 12/3/6/9 numerals, the cyan retrograde
  month arc (Jan→Dec), yellow day scale (1→31), green weekday letters, tick dots, and the berry.
  → **+1 test (102 total).** See `docs/milano-static.png`.
- (Fonts/metrics differ between AWT and Android, so the PNG is a pipeline smoke + visual check, not a
  pixel-exact preview of the device output.)

### Step 8 — Renderer: hands (full Milano)
- Added a small **path API** to `DrawingContext` (beginPath/moveTo/lineTo/closePath/fill/stroke) for
  polygon hand shapes, implemented in the AWT adapter via `Path2D`.
- `HandRenderer.kt` ports the QHand paths from `renderer.ts`: `rect` (thin radial bars — the
  retrograde calendar + power hands) and `tri` (diamond hour/minute/second), plus the `oCenter`
  center dot. (quad/sun/breguet/wire/spoke types, ornaments, tail circles, z-shadows deferred.)
- `WatchRenderer.kt` adds `renderFrame()` — walks all parts in document order (static parts +
  hands), then the bezel. This is the single-pass renderer; the static-cache/dynamic split is a
  later optimization.
- `WatchRenderTest.kt` renders the **full Milano** at a fixed time to `docs/milano-full.png` and
  checks the red second hand drew. **Verified visually**: at 2026-06-12 10:09:36 UTC (a Friday) every
  hand lands correctly — hour upper-left (~10), minute upper-right (9.6m), red second lower-left
  (36s), cyan month hand on **Jun**, yellow day hand on **11–13** (the 12th), green weekday hand on
  **F**. → **+1 test (103 total).**

### Step 9 — Bezel realism + Geneva face
- **Bezel realism:** completed the web port's full bezel layer stack on top of the radial base —
  top-lit directional gradient, a tight conic specular reflection, and fine machining hairlines
  (new `DrawingContext.fillRingLinearGradient` / `fillRingSweepGradient`). Note: the iPhone bezel was
  a case *image*; this procedural bezel is the web port's invention and the faithful reference.
- **Geneva:** the second face. Added the renderer pieces it needs beyond Milano — QDial marks
  (outer/tickOut/center), hand arrowhead ornaments (`oLength`), tail circles (`oRadius`), and simple
  image hands (`src`) — on top of the `faceFront.png` background. Renders to `docs/geneva.png`:
  date ring, weekday/month/24-hour/moon-age subdials, rise/set sub-dials, and the time hands, all
  recognizably Geneva. Core time/calendar hands are correct; unported astronomy (moon phase,
  rise/set, seasons, DST) is stubbed to 0; QWheel/QWedge/terminator/window parts not yet rendered.
  → **+1 test (104 total).**

### Step 10 — Astronomy: Sun + Moon (positions + moon phase), golden-validated
- Set up the **golden-value oracle** the earlier maybe-TODO described: ran the chronometer-web
  TypeScript astronomy under Node (esbuild bundle) to dump sun/moon ephemeris for 6 dates →
  `engine/src/test/resources/astro-golden.json`. An independent implementation, so it validates the
  Kotlin port for real (and immediately caught a Kotlin `Int`-overflow bug: `24*3600*36525`).
- **Sun** (`astro` stage 1): AstroConstants (esFmod + epochs), EsTime Delta-T (Espenak) + Julian
  date + Julian-centuries-since-J2000, WbSun series, EsCoordinates (sun RA/decl + ecliptic long),
  EsSidereal (GST P03). Data auto-converted: SunData (50), DeltaTTable (193).
- **Moon** (`astro` stage 2): LunarTables auto-converted from the Chapront series (longitude Sv*,
  latitude Su*, nutation — full precision), WbMoon (RA/decl + ecliptic longitude), EsAstro.moonAge
  (age + illuminated phase).
- `SunAstroTest` asserts sun + moon RA/decl, ecliptic longitudes, GST, Julian centuries, Delta-T,
  moon age & phase all to **1e-9** vs golden. → **+1 test (105 total).**
- Deferred: rise/set (needs the AstroCachePool) + altitude/azimuth; then wire into WatchEnvironment
  so Geneva's moon/rise-set/season indicators become real (currently stubbed to 0).

### Step 11 — Astronomy: rise/set (sun + moon)
- Ported the iterative rise/set/transit solver (`planetaryRiseSetTimeRefined` — Meeus pp102-103
  with the parabolic/linear convergence accelerator, transit-retry, and polar binary-search
  recovery) **cache-free**: the `AstroCachePool` only memoizes intra-iteration sub-results
  (slop=0 ⇒ no cross-iteration reuse), so dropping it gives identical results.
- Supporting deps: moon **distance** series (Sr* tables; WbMoon precision-parameterized for the
  solver's Low→Full schedule), `altitudeAtRiseSet`/size/parallax, `convertGSTtoUTclosest`, rise/set
  sentinels + planet radii.
- `RiseSetAstroTest` validates sun rise/set/transit + moon rise/set (San Francisco, 4 dates) to
  **0.05 s** vs golden captured from the TS solver. → **+1 test (106 total).**
- The astronomy core (sun + moon positions, phase, rise/set) is now complete. Remaining: wire it
  into WatchEnvironment (the `riseSetForDay` fwd/bwd day-selection + hour-angle conversion, moon
  phase angles, season) and re-render Geneva with live subdials.

### Step 12 — Wire astronomy into Geneva
- Ported the watch-env astronomy registrations from `astro-env.ts` into `WatchEnvironment`
  (now takes observer lat/lon): the `riseSetForDay` fwd/bwd local-noon search (same-local-day only),
  `riseSetAngles` (precise local time-of-day → hour12/minute/hour24), and registrations for
  sunrise/sunset/moonrise/moonset (valid + hour12/minute/hour24 angles), `moonAgeAngle`,
  `realMoonAgeAngle`, `season`, `offsetOfWinterSolstice`, `GregorianEra`, `yearNumberCEMonotonic`,
  `monthLen`, `leapYearIndicatorAngle`, `DSTNumber` (stub), `tzOffset`, time-unit constants.
  (`moonRelativeAngle`/`moonRelativePositionAngle` stay stubbed — they need alt/az and only affect
  the moon-disc libration and the deferred terminator.)
- `WatchEnvGoldenTest` validates the wired functions against golden captured by calling the web's
  `registerAstroFunctions` directly — confirms the wiring end-to-end. → **+1 test (107 total).**
- `GenevaRenderTest` now renders with **live astronomy** at San Francisco (no stubs): the rise/set
  subdials, moon-age hand, and season hand are driven by the real ported computations. See
  `docs/geneva.png`.

### Step 13 — Geneva fidelity fixes (from iPhone comparison)
- **4x assets**: faceFront/seasons/moonES80 loaded as `-4x` at scale 0.25 (were 1x upscaled → blurry
  baked text). Convention: map XML src → its `-4x` asset at scale 0.25.
- **Window porthole cutout**: `DrawingContext` gained `pushLayer`/`popLayerToParent` + `cutCircle`/
  `cutRect` (offscreen layer + destination-out). `renderFrame` accumulates Window parts and punches
  holes in the following part — so the moon disc shows through `faceFront`'s porthole.
- **line/arc marks** (subdial crossbars): ported from iOS `ECQView.m` `drawGuilloche` (the original
  parses `marks` as a numeric `ECDiskMarksMask`; the web port read strings and only did 5 types).
  `drawQDial` now draws `line` (diameters dividing a subdial) and `arc`; `DrawingContext` gained `strokeArc`.
- **Moon terminator** (phase shadow): ported the leaf-based display (`Terminator.kt`) + the
  `moonRelativePositionAngle` astronomy (`positionAngle`/`greatCircleCourse`/`northAngleForObject`;
  alt/az computed inline). The moon now renders its phase with the iOS leaf texture.
- Also: full local archive of the EmeraldSequoia org cloned (esastro/estime/eslocation/esutil/
  buildscripts/docs/Timestamp/Observatory + the 720 MB iOS Chronometer, full history) — the original
  Obj-C++ was the source for the `line`/`arc` mark geometry.

---

### Step 14 — QWheel + QWedge
- `WheelWedge.kt`: QWheel (rotating text + circular background; orientations three/six/twelve/nine;
  full-circle and partial-arc) and QWedge (filled annular sector at 12 o'clock, optional polar
  offset). Model + parser for both. Deferred: TWheel half-and-half, calendar wheels, wheel ticks.
- Geneva now renders its date-ring covers (short-month cell covers) and am/pm indicator wheels —
  **every part type Geneva uses is now implemented** (QDial + all marks, QHand, QRect, Image,
  Static, Window, Terminator, QWheel, QWedge).

---

### Step 15 — Night mode
- The web port **stripped** night/back/info parts; the original iOS XMLs (now cloned, at
  `Chronometer/Watches/Builtin-Android/<Face> I/`) still have them. Geneva's night mode is a
  dark-themed parallel set of the same part types my renderer already handles.
- Staged the original Geneva XML (`Geneva-full.xml`); patched a `bezelColor` (the original used a
  case image) and made the night windows `modes='night'` (the iOS doesn't mode-filter windows —
  it consumes them positionally via a winBin; per-mode parsing needs the mode explicit).
- Render-layer `evalAttr`/`evalColorArgb` now tolerate undefined *variables* (→ 0, like the iOS VM;
  Geneva's night rise/set hands reference an unset `nfgclr`) while still throwing on undefined
  *functions* (a real porting gap). → `docs/geneva-night.png`, 108 tests green.
- (The tap-to-toggle interaction is an `:app` concern; this is the render side.)

---

## Current state

- **Engine: 108 tests, all green** (81 expr + 16 astro + 7 parser + 4 render). Render fixes are
  visual (validated by eye against the iPhone), atop the golden-validated engine.
- **Milano + Geneva (front and night) render fully.** Pipeline complete: XML → model →
  expressions → astronomy → render, with mode filtering (front/night/back).
- Modules done: `com.plasticsmoke.beryl.{expr, astro, watch, render}` — all pure Kotlin/JVM.
- **Milano renders fully**; **Geneva renders** (core hands correct, astronomy stubbed) — see
  `docs/milano-full.png`, `docs/geneva.png`.
- Android SDK installed; `:app` not yet wired.

### Build / test
```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  ~/.gradle/wrapper/dists/gradle-8.9-bin/*/gradle-8.9/bin/gradle :engine:test
# (or open android/ in Android Studio / IntelliJ)
```

## Maybe-TODO (verification rigor)

- The `ExprTest` cases were hand-transcribed from the upstream TS test file, so they carry some
  correlated-error risk (same source for code and test). A more rigorous approach: run the upstream
  TypeScript/vitest suite to dump golden input→output pairs and assert the Kotlin port reproduces
  those exact numbers — an *independent* oracle. (The astro/calendar tests already use java.time as
  an independent oracle; the Milano static PNG was verified visually.) Deferred, not urgent.

## Roadmap remaining

- [x] **renderer (static layer)** — `DrawingContext` + `StaticRenderer`; Milano's static layer renders.
- [x] **renderer (hands)** — QHand rect/tri + center dot; `renderFrame` draws the full face. Milano
      renders with correctly-posed hands.
- [ ] **renderer (polish)** — static-cache Bitmap + per-frame dynamic split; QDial marks; gradient
      bezel; remaining hand types (quad/sun/breguet/wire/spoke) + ornaments + z-shadows.
- [ ] **`:app`** — `AndroidCanvasDrawingContext` (android.graphics.Canvas adapter), custom `View` +
      `Choreographer` loop (idle vs animating two-state), asset loader, `FusedLocation`/manual lat-lon,
      `BatteryManager` overrides for `batteryLevel()`.
- [ ] **band/case chrome** — the canonical iOS Geneva front draws `band.png` + `case.png` Image parts
      (`../partsBin/HD/.../band.png`, `.../case.png`) that our front XML drops (replaced by a flat black
      `maskRect`). This is why the watch band shows on iPhone but not in our render. Currently we draw a
      procedural gradient bezel (`bezelColor`) instead. Real fix: render the case/band art assets.
      Deferred — tackle after info button / reverse / night accuracy.
- [ ] First milestone: **Milano** rendering natively with smooth hands at the display's refresh rate.
- [ ] Then fan out to the richer faces (astronomy tables, more part types) against the same engine.

## Front-vs-canonical sanity check (2026-06-14)

Diffed our web-derived front `Geneva-I.xml` against canonical `Builtin/Geneva/Geneva.xml`. Our front
turns out to be a dev-made derivative ("Geneva I", copied from iOS Geneva 2017-05-21 with back/night/
buttons stripped) — render-affecting attributes are byte-identical across all 49 QDials, 24 hands,
9 wedges, 5 wheels, and the terminator. Aligned 3 tiny geometry deltas back to iOS: `outer edge`
markWidth 2→1, guilloché radius 137→140, `day` date-ring radius 134.7→135.7. The only substantive gap
is the missing band/case chrome (TODO above).

## Reverse (back) side (2026-06-14)

Geneva's astronomical back now renders from the canonical iOS XML (`ModeFilter.BACK`): zodiac + month
rings, the sidereal dial with LST hands, Civil/UTC/Solar subdials, year/century digit wheels (showing
2026 through window cutouts), the eclipse display, and the sun/moon day-night rings. See
`docs/geneva-back.png`.

New astronomy (ported from chronometer-web, golden-validated where the web is reliable):
`EsAltAz.kt` (localSiderealTime, planetAltAz + sun/moon altitude/azimuth, topocentricParallax),
`eotSeconds` (equation of time), `generalPrecessionSinceJ2000` + `raAndDeclO` (EsCoordinates),
`wbMoonAscendingNodeLongitude` + `lunarAscendingNodeRA`, and `EsDayNight.kt` (the day/night-ring
leaf-angle computation). These are wired into `WatchEnvironment` as the back's watch functions, and
checked by `BackAstroGoldenTest` against `astro-back-golden.json`.

Two new part types: **Qtext** (a single positioned label) and **QdayNightRing** (an annulus of
up/down wedges). **SWheel** needed no new code — it was already a Wheel variant.

Caveat worth recording: the web port stripped the back, so its day/night LST leaf returns NaN
(its `planetaryRiseSetTimeRefined` echoes the transit as the rise/set time). Our Kotlin produces
real, correctly-distributed wedge angles and the ring renders, so the day/night ring is verified by
eye against the iPhone rather than by golden value. The scalar back functions (sidereal time, EOT,
sun/moon RA, lunar node, altitude/azimuth) are golden-validated normally.

Still stubbed on the back (safe placeholders): the eclipse calculation (`eclipseSeparation`/
`eclipseKind` → port `calculateEclipse` next). The 'i' info button is an :app-layer concern
(a UI panel overlay), not watch-face rendering.

### Web-vs-iPhone divergence audit (2026-06-15)

Since the engine was ported from `chronometer-web`, it inherited that port's simplifications. We
audited the web renderer against the canonical iOS source (`Chronometer/Classes/ECQView.m` etc.) to
catalog every divergence — see the `web-vs-ios-divergences` memory for the full backlog. Root causes:
iOS bakes hand/window shadows via a build-time ImageMagick pipeline the browser can't run; Canvas-2D
API gaps; the back/wadokei paths were stripped from the web so never tested; and eyeball shortcuts.
**Policy: where web and iOS diverge, port from the iOS source, not the web.**

Fixed from the audit: dial text now uses the measured font line height (`textHeight()`) in all
orientations instead of the raw font size (iOS uses `NSString sizeWithFont` height) — this is why the
"31" peeked above its date-ring cover; it's now fully covered, with no regression on the other dials.
Also corrected the sun-hand center radius (`length2 + raysRad`, matching iOS, not the web's
shadow-cache bug). Notable backlog items: hand & window drop-shadows (port iOS's fixed-direction
baked-Gaussian model, not the web's rotating approximation), QRect pane dividers should be
large-radius arcs (not straight lines), hand `tail` should default to `ceil(length/10)`, and the
`gear`/`set`/`rise`/`sun2` hand types and `angle0`/`clipRadius`/annulus dial features are unported.

### Shadows — hand drop-shadows + window inner-shadows (2026-06-15)

Added a blur/shadow capability to the drawing layer: `DrawingContext.deviceScale()`,
`popLayerAsShadow()`, and `clipRect()`, implemented in the AWT adapter as a bounding-box-cropped
separable Gaussian blur (two 1-D `ConvolveOp` passes) over a captured layer. Hand drop-shadows
capture each hand's silhouette and drop it blurred underneath at a **fixed screen direction** (it
does not rotate with the hand — the iOS model, not the web's rotating approximation), using iOS's
opacity/sigma formula from `makeOneShadow.pl`. Window inner-shadows clip to the opening, capture a
black frame just outside it, and composite it blurred + offset to give the year/date boxes their
recessed look. The shadow direction (down-right) and magnitudes are eyeball-tuned — iOS's exact
sigma is in template pixels at native resolution, a ~2–3× scale ambiguity, and its Y-sign was
ambiguous from static reading — so these are the obvious knobs to confirm against the iPhone. The
Android `:app` will use `BlurMaskFilter`/`RenderEffect` for the same effect.

### Eclipse calculation (2026-06-15)

Ported `calculateEclipse` (solar/lunar classification + the abstract 0–3 separation) to
`EsEclipse.kt` and wired `eclipseSeparation()` / `eclipseKind()` — the Geneva back's eclipse needle
and kind wheel are now live (every dependency — alt/az, topocentric parallax, angular size — was
already in place). Before porting, confirmed the iOS source (`esastro/src/ESAstronomy.cpp`) is a
line-for-line match with the web version, so the web is a faithful reference for the astronomy *math*
(its divergences are rendering-only) and is the one we can golden-validate against. Validated in
`BackAstroGoldenTest` with a real eclipse case (the 2026-08-12 total solar over Iceland → a partial
solar for Reykjavik, separation ≈ 1.18). 110 tests green.

### Back/night fidelity fixes (from iPhone comparison)

- **Image-hand offset/anchor + `sun` hand type**: ported the polar offset (`offsetRadius`/
  `offsetAngle`) and anchor (`xAnchor`/`yAnchor`) image-hand placement, plus the rayed `sun` hand.
  The back's lunar nodes (red Ω), moon and sun markers now sit around the zodiac ring instead of
  piling up in the center.
- **Parse-time geometry snapshot** (matches iOS `boundsCheck`): the parser now evaluates `<init>`
  blocks incrementally in document order and snapshots constant geometry attributes (x/y/radius/…)
  at parse time, while live attributes (angle, …) stay lazy for per-frame eval. This fixes the night
  month subdial, which had been shoved to the bottom because the *back* section's `<init>`
  (`lx=-0.5, ly=-subsR`) was clobbering the night value (`lx=0, ly=68`) under the old all-inits-
  global-then-lazy model.
- **Font mapping (test harness only)**: `AwtDrawingContext` maps the iOS font names to the
  metric-compatible Liberation faces, so the headless preview's number spacing matches the iPhone's
  Arial (previously it fell back to the wider DejaVu and overflowed). Android will use platform fonts.

## 2026-07-04 — :app wired: the engine runs on Android

First on-device milestone: the `:app` module is now a real Android application that renders
Paris (front/night/back, tap to cycle) over the granite wallpaper — the same composition as the
PhoneFrame test previews, on the actual phone.

- **Gradle wiring**: `:app` enabled in settings; AGP 8.7.3 + kotlin("android") 1.9.20 on the
  cached Gradle 8.9; `local.properties` → the local Android SDK path. The engine now emits JVM-17
  bytecode (still compiled under JDK 21) so AGP's D8 can dex it — its own tests are unchanged.
- **`AndroidCanvasDrawingContext`**: the `android.graphics.Canvas` adapter for `DrawingContext`,
  mirroring `AwtDrawingContext` semantics — explicit offscreen-layer stack with PorterDuff.CLEAR
  hole-punching (window cutouts), tracked transform matrix (Canvas.getMatrix is unreliable),
  fractional Paint.FontMetrics, BlendMode.DIFFERENCE / LUMINOSITY (hence minSdk 29), native
  Radial/Linear/SweepGradient for the bezel, and the same alpha-plane Gaussian shadow blur.
  Canvas arc/sweep angles are screen-clockwise like our Y-down space, so no AWT-style negation.
- **Fonts**: the free stack ships in assets (Liberation Sans/Serif/Mono + PT Sans) and is loaded
  per XML font name, so on-device text metrics match the validated previews (Android's Roboto
  does NOT match Arial metrics). CJK falls back to system fonts automatically.
- **`FaceLoader`**: assets mirror the test-resource layout (`faces/<face>/…` + flattened
  `faces/chrome/partsbin-…`), with a `loadChrome` port resolving `../partsBin/…` srcs.
- **`WatchView`**: loads XML+images on a background thread, builds a per-mode env
  (`registerWatchFunctions` with the device tz offset; observer position hardcoded SF pending a
  LocationManager hookup), renders the full frame into a Bitmap once per second via Choreographer.
  The static-cache + dynamic split stays TODO for sweep-second faces and battery.
- Stale June-era app assets (Milano-I/Geneva-I fixtures) replaced by the new layout.

`gradle :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` (9.2 MB).
Install: `adb pair`/`adb connect` (wireless debugging), then `adb install -r app-debug.apk`.

### Tap latency fix: render thread + native shadow blur

User-reported: multi-second delay between tapping and the face switching. Three causes, all in
the MVP render loop:

1. **Rendering ran on the UI thread** — the once-a-second full-frame software render blocked
   input, so taps queued behind in-flight frames. Rendering now runs on a dedicated
   HandlerThread (coalesced jobs, double-buffered bitmap swap on the UI thread); taps post a
   render immediately.
2. **The hand drop-shadow blur was the whole frame cost**: the straight port of the AWT
   test-harness Gaussian (per-pixel Kotlin float loops) took ~1.3 s per shadowed hand in a
   debuggable APK — 2.6 s of Paris front's 2.7 s frame (logcat-profiled). Replaced with Skia's
   native path: `Bitmap.extractAlpha` + `BlurMaskFilter` (sigma = 0.57735·radius + 0.5, so
   radius = (sigma − 0.5)/0.57735). ~75 ms per shadow, visually identical on-device.
3. **Layer bitmaps** (window cutouts, shadows; ~10 MB each at 1080×2424) were allocated fresh
   every frame — now reused via `LayerBitmapPool` (render-thread-only).

Frame times after (debug build, logcat `Beryl`): Paris front ~2700 → ~250 ms,
night ~130 ms, back ~75 ms. A `frame mode=… (shadows …ms, images …ms)` timing log stays in for
future perf rounds. Remaining headroom: the static-cache split (case/band/dial art is re-drawn
every frame) and a release build (debuggable ART is several-fold slower).

## 2026-07-04 — All 25 faces on-device: swipe rotation + face picker

The app now carries the full approved face set, navigated like the iPhone: horizontal swipe
moves through the rotation, a translucent grid button in the lower-left opens a 3-column
thumbnail picker of all 25 faces, tap still cycles front → night → back. Last shown face
persists across launches.

- **faces.json manifest, generated not hand-written**: `tools/gen_face_manifest.py` parses the
  25 faces' render tests — the single source of truth — for each face's staged XML, image map
  (`src` key → staged asset + unit scale, all three `img()`/inline/computed idioms) and iOS-only
  stub constants (Istanbul/Thebes alarm, Olympia stopwatch, AtlantisIV GPS, Miami's
  `body`=Saturn env var). It also emits the picker thumbnails from the committed docs previews.
  Re-run it when a render test's map changes; it exits nonzero on any warning.
- **Assets staged at build time**: a Gradle Copy task feeds all *.png/*.xml from
  `engine/src/test/resources` into a generated assets dir (`facedata/`, flat names) — 52 MB is
  not duplicated in git. APK: 60 MB.
- **FaceCatalog/FaceRepository**: manifest-driven; faces load on a single loader thread
  (parse 3 modes + decode bitmaps, worst ~1 s for Terra/Geneva/Miami), LRU keeps 3 resident,
  swipe neighbors preload. Terra additionally gets `registerTerraFunctions` (the only face with
  its own env registration — first launch crashed on `dayNumberN` without it; renderFrame is
  now also wrapped so a porting gap logs instead of killing the app).
- **Dirty-rect layer tracking** (AndroidCanvasDrawingContext): every primitive unions its
  conservative device-space bounds into the current layer's dirty rect; shadows crop to the
  silhouette before the native blur, composites blit only the dirty region, pooled layer
  bitmaps erase only what was touched. Terra — 31 shadowed hands/frame — went from 2.3 s to
  ~430 ms (shadows 2000 ms → 22 ms). Full-frame layer passes don't survive contact with Terra.
- Verified on the device: all 25 faces swipe-cycle with zero render failures; eyeballed
  Alexandria, AtlantisIV (live GPS-stub digital), Kyoto (portrait + system-CJK zodiac), Terra
  (city ring correct for the device's actual -5 timezone; LA slot reads -7), Tombstone (gears +
  luminosity overlay), and the picker grid. Timezone now comes from the device; observer
  lat/lon is still hardcoded SF pending the LocationManager hookup.

## 2026-07-04 — Location + the iPhone's corner controls (incl. per-face info panels)

- **Location**: `LocationStore` on plain android.location (no Play Services dependency).
  Last good fix persists in prefs (one fix is enough until you travel); on launch/resume it
  seeds from every provider's last-known and requests a single fresh fix per enabled provider —
  no continuous updates, no battery cost. SF fallback until a first-ever fix. A ≥ ~5 km move
  fires a callback that clears the face cache and rebuilds the current face (envs bake the
  observer in at registration). Manifest has FINE+COARSE; granted via adb on the phone. The
  system Location toggle was off at the time of writing — flip it and the next resume picks up
  a fix (logcat: "location: <lat>, <lon> (fresh gps) — rebuilding faces").
- **Corner fans** (mirroring the iPhone's two bottom quarter-circles): left = grid picker ▦ ·
  lights-out ☾ · flip ⇄; right = settings ⚙ · info ⓘ · list picker ☰. Mode state is now
  side+lights (flip = per-visit, lights = app-global like iOS) instead of tap-cycling; tap on
  the watch is freed for future stem/button interaction.
- **Info panel**: the ORIGINAL iOS per-face help — `Chronometer/Help/<Face>/<Face>.html` — in a
  WebView from assets. Staged the pruned Help bundle (25 face dirs + shared css/images/docs,
  4.4 MB, committed); `product.css` = Chronometer.css (what genHelp.pl ships for the iPhone
  flavor); generated a simple "Help Contents.html" (the iOS one is build-generated); replaced
  the `EMERALD_PRODUCT` placeholder. Back button walks WebView history, then closes.
- **Settings** (minimal for now): observer position, timezone, permission state, refresh-
  location button, and an App-help entry to the contents page. List picker = names dialog.

### Firenze fix: computed image maps don't extract — validate manifest against the XML

User report: Firenze rendered as a mess on-device (fine in previews). Root cause: its FRONT
test built the image map with a buildMap loop, and Miami's tests used a label() helper — the
manifest generator only parses literal `"key" to img(...)` entries, so Firenze's face + 7 front
planets and Miami's 7 transit labels were silently absent from faces.json (missing images just
don't draw). Fixes:
- The offending tests now use literal map entries (the generator's contract, noted in comments);
  the generator flags buildMap/associate in any render test.
- The generator now CROSS-CHECKS every `src=` in each face's staged XML (comments stripped)
  against the manifest + flattened chrome and fails on gaps — this net also caught Kyoto's
  crown (canonical `../McAlester/stem[-n].png` cross-reference, never staged even in tests; now
  mapped, so Kyoto previews gain the winding crown) and McAlester's intentionally-unstaged
  open-case layers (whitelisted).
- Also per user report: the lights (night) button now hides when flipped to the back, matching
  the iPhone (there is no back night mode).

## 2026-07-04 — Live picker grid (all 25 faces animated) + release-build numbers

The picker grid is now LIVE like the iPhone's: while it's open, every face re-renders once a
second at cell scale (MiniFaces, its own thread). Affordable without the static-cache split
because everything shrinks with the cell: ~1/3 linear scale = ~1/9 the pixels, and the 4x art
decodes with inSampleSize (1/16 the memory), so all 25 faces stay resident. Cell bitmaps
double-buffer by sweep parity; the first sweep publishes after each face so the grid fills in
progressively over the static thumbnails. Sweeps stop the moment the picker closes.

Measured (logcat tag Beryl):
- mini sweep, 25 faces: DEBUG ~2000 ms → RELEASE ~580 ms — true 1 Hz for the whole grid.
  At mini scale the bottleneck is per-part expression/astronomy evaluation (scale-independent
  CPU), which is exactly what ART's release optimization speeds up (~3.5×).
- single face (Tombstone front, 13 shadows): ~285 ms release vs ~250 ms debug — barely moves,
  because its cost is already native Skia (extractAlpha blur); Kotlin-heavy faces gain more.
- release APK is debug-signed (personal app) so it installs directly; lintVital tasks needed
  an explicit dependsOn(stageFaceAssets).

Speed conclusions: release-mode ART is the free 3.5× on expression-bound work; the remaining
big lever is the static-cache split (render static art once per face/mode/size, evaluate only
dynamic parts per frame) — that cuts BOTH the pixel work and most of the expression work, and
is what sweep-second hands at high refresh need. Memory note: ~630 MB PSS with the grid open
(largeHeap); trimming mini buffers on picker close is a cheap follow-up if it ever matters.

## 2026-07-04 — Static-cache split (the iOS render architecture)

renderFrame now takes an optional StaticCache: watch.parts splits into maximal STATIC RUNS —
dials, images, rects, text, static groups, buttons, and window clusters whose cutout target is
itself static (windows pass over buttons exactly as at runtime) — each baked ONCE into a
device-space sprite and re-blitted every frame; dynamic parts (hands, wheels, terminator,
wedges, day/night rings, calendar) evaluate and draw live between the sprites in document
order. A run only begins with no windows pending, and windows targeting a dynamic part fall
back to the uncached path, so semantics are unchanged; without a cache (tests/previews) the
single-pass path is byte-identical to before. Caching is skipped for bezel-clipped fixture
faces (layers don't inherit the clip). Sprites crop to the layer's dirty rect on Android;
each ModeState owns its cache (rebuilt on size/observer change with the state itself).

StaticCacheTest proves the cache is invisible: cached third-frame renders match single-pass
renders within compositing rounding (≤2/channel on ≥99.5% of pixels) on Paris (buttons+chrome),
AtlantisIV (window-before-static reveal over digit wheels) and Thebes (windows + subdials).

Also found+fixed while profiling: the granite wallpaper was software-rescaled to the full
frame EVERY frame (~60-100 ms — the invisible floor under every face). Pre-scaled once per
size: ~3 ms. Same fix in the mini grid.

Release-build numbers after both (full sweep of all 25 faces, zero failures):
lightest faces 47-66 ms (Atlantis, AtlantisIV, Milano, Paris), most 80-160 ms, Terra 205 ms,
Tombstone 265 ms. Mini sweep: ~580 → ~310 ms. The per-frame cost is now dominated by hand
DROP-SHADOWS (Tombstone 190 ms of 265; McAlester/London ~90 ms) and dynamic-part
rasterization — the next lever is baking each hand (content + blurred shadow) into a sprite
at load and rotate-blitting per frame, which is precisely what iOS's per-part textures do.

## 2026-07-04 — Baked hand sprites + per-part beat clock (iOS model, second half)

Hands now rasterize ONCE and animate as rotated quads, mirroring iOS's per-part textures:

- **Delta-rotation baking** (HandRenderer): with a StaticCache, drawQHand bakes the hand at its
  first-frame pose (existing drawing code verbatim — gears with their luminosity overlays,
  quads, breguets, image hands, ornaments, everything) into a content sprite plus a pre-blurred
  shadow sprite (blur is rotation-invariant, so rotating the baked shadow is exact), then each
  frame just blits both rotated by (angle − angle0) about the pivot, translated by any
  motion delta. Excluded (live path): Terra's world-time ring (city colors change with the
  date) and offset image hands (live offsetAngle orbits without spinning — not a rigid
  rotation). StaticCacheTest gained London; cached ≡ single-pass holds on all four faces.
- **BeatClock** (the iOS `update` model): every part carries an update interval (iOS default
  1 s; Geneva's seconds '.1', Tombstone's escapement 'updateRate'=.25 and balance wheel
  'updateRate/30' ≈ 8 ms — all already in the canonical XML, already parsed). renderFrame
  calls beat(interval) before each dynamic part; the app's FrameClock quantizes the env's
  display time to floor(frame/interval)·interval. Ticking vs sweeping vs rocking all emerge
  from the XML: Geneva's seconds tick 10×/s, Tombstone's fourth-wheel seconds tick 4×/s while
  its balance wheel rocks smoothly — from the same continuous expressions, exactly like the
  iPhone.
- **Adaptive scheduling** (WatchView): renders when the face's fastest update epoch rolls
  over — Tombstone effectively ~30 fps, Geneva 10, faces without fast parts once a second.
  Frame log now samples (every 60th frame or >150 ms).

Release numbers: Tombstone 265 → ~33 ms/frame (shadows 190 → 0 — all baked),
Geneva 147 → ~28 ms. The movement runs like the iPhone's: gears turning faster than the
ticking seconds, balance rocking smoothly.

### Tombstone fidelity round (user vs iPhone) — beat grid, tween, gear widths, frame race

User report: balance wheel chaotic (iPhone: stationary), pallet lever a 2-frame flip (iPhone:
smooth rocking), solid red gear dots too small, night mode flickering to black with stray
gears/reset button. Four distinct causes:

1. **Beat grid**: iOS schedules part updates on the watch's 1/beatsPerSecond timer. Tombstone
   is beatsPerSecond='4', so its balance wheel — update='updateRate/30'≈8 ms in the XML — still
   samples only every 0.25 s, and its 2 Hz sine expression lands exactly on the zeros: the
   iPhone's balance is STATIONARY by arithmetic, not by choice. updateIntervalOf() now floors
   at the beat grid; ours freezes at -45° identically.
2. **animate='1' tween**: iOS interpolates a part's pose between update samples. Now parsed and
   implemented in the sprite path (BeatClock gained phase + beatPrevious; angle lerps between
   the previous and current epoch samples). The lever rocks through intermediate poses
   (verified: 3 captures 90 ms apart = 3 distinct poses), stepped gears read as turning.
3. **Gear stroke-width statefulness**: ECQView never resets the CG line width between gear
   elements — the hub strokes at the SPOKE width, and the red pinion disc strokes at whatever
   width is residual (spoke width for leafless gears, 2 for single-dot, leaf width for
   pinions). Ported; the solid red dots now match the iPhone (user's red-dot-sizes.png).
4. **Frame-buffer race** (the flicker): the buffer picker read displayIdx racily while onDraw
   promoted that same buffer — partially-rendered frames reached the screen at 30 fps (watch
   vanishing into floating gears + reset button, black flashes in night). Triple buffering
   with a locked handoff: the picker excludes pending+displayed under a lock, onDraw only
   promotes pendingIdx. Verified: 6 rapid captures from cold launch all complete; night mode
   rock-stable. (Also hardened the whole-bitmap createBitmap aliasing case in sprite capture.)

StaticCacheTest gained Tombstone (gears + overlays); cached ≡ single-pass holds.

## 2026-07-04 — iPhone-style slide between faces

Swiping now slides like the iPhone instead of jump-cutting: the current face tracks the finger
while the neighbor peeks in from the edge (both live full renders — the peek is a real frame of
the target face in the current lights mode, produced on the render thread; neighbors are
usually preloaded so it's ready within a finger-width). Release commits past ⅓ width or on a
same-direction fling (>900 px/s), else springs back; the settle animates ≤260 ms decelerated.
After a commit the slid-in peek stays on screen until the new face's own first frame lands
(frames are tagged with their face identity), so there's no old-face flash. GestureDetector
replaced with manual slop/VelocityTracker handling; a bare tap remains free for the future
stem/pusher interaction layer.

### Slide fix: stationary wallpaper (iPhone behavior)

User: on the iPhone the granite stays put and only the watch slides. Frames now render on
TRANSPARENCY; onDraw composites the pre-scaled wallpaper as a fixed base layer and slides only
the watch bitmaps over it (also saves the per-frame wallpaper blit on the render thread).
Verified: mid-slide wallpaper strip is pixel-identical to at-rest (0.0 diff) while the watch
region moves.

### Slide labels (iPhone behavior)

Face-name labels during slides: each page carries its name at the top with an arrow on its
outer side ("‹ Babylon" | "Chandra ›"), the landed face's name lingers ~1.2 s after the settle,
then fades over ~450 ms; any new drag brings the labels straight back.

## 2026-09-13 — Beryl Chronometer: public baseline

Re-baselined the project for publication as **Beryl Chronometer** (beryl being the mineral
emerald is a variety of), in a fresh repository with no history, so the name is clearly
distinct from Emerald Sequoia's product while the README and NOTICE attribute the origin.

- Package `com.plasticsmoke.beryl`, launcher label "Beryl Chronometer", log tag `Beryl`.
- MIT license with the upstream Emerald Sequoia notice; NOTICE.md and `licenses/` carry the
  font licenses (Liberation and PT Sans under OFL 1.1, the DejaVu subset under Bitstream Vera).
- `docs/` (previews, screenshots, recordings) is no longer committed; README rewritten;
  ARCHITECTURE.md holds the design and every fidelity note that used to live in memory.
- **App icon**: the original was a cropped photo of a dial. Beryl's is the same idea from its
  own engine — a crop of the Tombstone front render (bezel edge, XI/XII, the hour hand over
  the gears), with a wider crop for the adaptive foreground so launcher masks show the same
  region. Background color is the granite tone.
- **Release signing**: `keystore.properties` (git-ignored) points at a dedicated release
  keystore; without it, release builds fall back to the debug key so anyone can build.
  Published APKs must always come from the same key or Android refuses the update.
