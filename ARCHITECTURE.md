# Beryl Chronometer — architecture and porting notes

This document is the detailed companion to the README. It covers the source material and the
rule for using it, the module layout, the pipeline from XML to pixels, the Android layer, how
the port is verified, and every quirk of the original that had to be reproduced. Nothing here
is speculative: each item was found by comparing this port against the original iPhone app or
its source.

Contents

1. [Sources and the canonical rule](#sources-and-the-canonical-rule)
2. [Repository layout](#repository-layout)
3. [The engine pipeline](#the-engine-pipeline)
4. [The render architecture](#the-render-architecture)
5. [The Android layer](#the-android-layer)
6. [Assets and staging](#assets-and-staging)
7. [Verification](#verification)
8. [iOS fidelity notes](#ios-fidelity-notes)
9. [Known deliberate divergences](#known-deliberate-divergences)
10. [Measurement techniques](#measurement-techniques)
11. [Tools](#tools)

## Sources and the canonical rule

Three upstream repositories matter:

- `EmeraldSequoia/Chronometer` is the original Objective-C++ iOS app. The canonical face
  definitions live at `Watches/Builtin/<Face>/<Face>.xml`, the dial and hand drawing in
  `Classes/ECQView.m`, the part update machinery in `Classes/ECGLPart.m`, the expression VM in
  `Classes/ECVirtualMachineOps.m`, the app shell in `Classes/ChronometerAppDelegate.m`, and
  the corner chrome (alarm glyph, buttons) in `Watches/Builtin/Background/Background.xml`.
  Its build runs the XML through an OpenGL preprocessor that bakes per-part textures; the
  checked-in results are under `archiveHD/`.
- `EmeraldSequoia/chronometer-web` is a TypeScript re-implementation that renders the same
  XML straight to HTML Canvas. It proved the preprocessor can be skipped, it ships a vitest
  suite, and its astronomy is a line-for-line match with the C++ in `esastro`. It also
  stripped the night and back sides and simplified a fair amount of rendering.
- `EmeraldSequoia/esastro` (with `estime`, `eslocation`) holds the astronomy library and the
  constants header `src/ECConstants.h`, which is where hand line-width defaults, alarm ring
  counts and repeat cadences come from.

**The rule:** the iOS source is canonical. The web port is a validated reference for renderer
logic and the golden oracle for astronomy math, but wherever the two differ the port follows
the Objective-C++. Every staged face XML is kept byte-canonical against the iOS file except
for a few explicit `modes` patches described below.

The approved face list is `Watches/Builtin/Approvals.txt`. Its "26 approved" count includes
`Haleakala-Android`, a Wear OS variant of the same Haleakala face, hence 25 distinct faces.
The faces marked `no` there (`Cairo`, `OldGreenwich`, `OldTerra`, `Wimm-1`, `GenevaHD`) are
not ported. There is no current "Greenwich" face; `OldGreenwich` is a discontinued design.

## Repository layout

```
beryl-chronometer/
├── engine/                     pure Kotlin/JVM, no Android dependency
│   ├── src/main/kotlin/com/plasticsmoke/beryl/
│   │   ├── expr/               Tokenizer, Parser, Evaluator (the face expression language)
│   │   ├── watch/              WatchModel (part types), WatchXmlParser
│   │   ├── astro/              time, calendar, sun/moon/planet series, rise/set, eclipse,
│   │   │                       sidereal, day/night rings, WatchEnvironment (leaf functions),
│   │   │                       WatchTime/WatchAlarm/WatchStopwatch/WatchActions (interaction
│   │   │                       state machines), HandDrag, TerraEnv
│   │   └── render/             DrawingContext (platform-neutral Canvas-2D subset),
│   │                           StaticRenderer, HandRenderer, WheelWedge, Terminator,
│   │                           CalendarRenderer, Analemma, WatchRenderer + StaticCache,
│   │                           AngleAnimator, ButtonHitTest, StaticDeps
│   ├── src/main/resources/planet-tables.bin
│   └── src/test/               ported oracle suites, per-face render tests, AwtDrawingContext,
│       └── resources/          PhoneFrame harness; STAGED FACE XML + ART (single source of truth)
├── app/                        the Android application (needs the SDK)
│   ├── build.gradle.kts        stages engine/src/test/resources into generated assets
│   └── src/main/
│       ├── kotlin/com/plasticsmoke/beryl/app/
│       │   AndroidCanvasDrawingContext, WatchView, MainActivity, FaceCatalog (FaceRepository,
│       │   LoadedFace, FrameClock), FaceLoader, FaceVarStore, LocationStore, MiniFaces
│       ├── assets/             faces.json (generated), fonts/, help/, thumbs/
│       └── res/                icon, raw/triangle.wav
├── tools/                      gen_face_manifest.py, convert_planet_tables.py, refresh-previews.py
├── licenses/                   third-party license texts
├── DEVLOG.md                   chronological porting log
└── docs/                       git-ignored: local previews, screenshots, proprietary preview fonts
```

The engine is deliberately a plain JVM library so that the entire platform-independent core
(expressions, astronomy, XML model, renderer) is compiled and unit-tested with only a JDK.
The Android module holds only what needs the platform.

## The engine pipeline

### Expressions (`expr/`)

Faces are written in a small C-like language: numbers, variables, assignment, arithmetic,
comparison, `&&`/`||`/`?:`/`!` with short-circuit laziness, the comma operator, hex color
literals, and calls to "leaf" functions supplied by the environment (`hour12ValueAngle()`,
`moonAgeAngle()`, `dayNumber()` and hundreds more). The evaluator preserves the semantics the
iOS VM actually has, which are not JavaScript's and not the web port's:

- `log()` is base 10 and `ln()` is natural.
- Truthiness is `> 0.5`; `&&`, `||` and `!` produce coerced 0/1 via `llrint`.
- Integer operations are 64-bit and `llrint`-rounded, not 32-bit truncated. `round()` uses
  ties-to-even.
- `fmod()` is floored (`fmod(-6, 7) = 1`), matching `EC_fmod`.
- `pi()` exists as a function. Leading-zero (octal-looking) constants are rejected.
- Reading an undefined variable yields 0, like the iOS VM. Calling an undefined function
  throws, because that is a real porting gap that must surface.
- Action expressions (button handlers) are never evaluated at parse time.

### Watch model and parser (`watch/`)

`WatchXmlParser` turns a face XML into a `Watch` of typed parts: `QDial`, `QHand` (types
`rect`, `tri`, `quad`, `sun`, `breguet`, `wire`, `spoke`, `gear`, image hands and more), `Image`,
`QRect`, `Qtext`, `QWheel`/`SWheel`/`TWheel`, `QWedge`, `QdayNightRing`, `Terminator`, calendar
parts, `Window`, `Static` groups, and `button` parts with actions and motion. Parsing uses the
JDK DOM, which exists on both the JVM and Android.

`<init>` blocks are evaluated incrementally in document order, and constant geometry
attributes (`x`, `y`, `radius`, and the other members of iOS's `boundsCheck` set) are
snapshotted into literals at parse time. This matches iOS exactly and matters: a later mode's
`<init>` must not move an earlier mode's parts. Live attributes (`angle`, `offsetAngle`,
`motion`, and anything an action can change) are never folded; see the fidelity notes for why.

### Astronomy (`astro/`)

Ported to Kotlin from the TypeScript, which was first confirmed to be a line-for-line match of
`esastro/src/ESAstronomy.cpp`:

- `EsTime`, `EsCalendar`: Apple-epoch time intervals, the hybrid Julian/Gregorian calendar
  (Gregorian from 1582, proleptic Julian before), Delta-T (Espenak) with `DeltaTTable`.
- `WbSun`, `WbMoon`, `WbPlanets`, `PlanetTables`, `LunarTables`, `SunData`: Willmann-Bell
  planetary series (Sun through Saturn, which is what Miami, Firenze and Terra need) and the
  Chapront lunar series at full precision. The coefficient tables are a binary resource
  converted from the upstream C header by `tools/convert_planet_tables.py`, which cross-checks
  every value against the TypeScript tables.
- `EsCoordinates`, `EsSidereal`, `EsAltAz`: RA/declination, ecliptic longitude, precession,
  Greenwich and local sidereal time (P03), altitude/azimuth, topocentric parallax, equation of
  time, lunar ascending node.
- `EsRiseSet`: the iterative rise/set/transit solver (Meeus with the parabolic/linear
  convergence accelerator, transit retry and polar binary-search recovery). The original's
  `AstroCachePool` only memoizes within one iteration, so the port drops it with identical
  results.
- `EsEclipse`: solar and lunar eclipse classification and the abstract 0 to 3 separation.
- `EsDayNight`: the day/night ring leaf angles in both the clock-time and sidereal
  (`timeBase='LST'`) variants.
- `WatchEnvironment` registers all of the above as leaf functions into an expression
  environment for an observer position and timezone. `TerraEnv` adds Terra's per-city
  functions. `WatchTime`, `WatchAlarm`, `WatchStopwatch` and `WatchActions` are ports of the
  iOS `ECWatchTime`, `ECAlarmTime` and stopwatch-slot state machines that back time warp, the
  Istanbul and Thebes alarms, the Thebes countdown and the Olympia chronograph. `HandDrag`
  converts a dragged hand angle back into a time change.

### Renderer (`render/`)

`DrawingContext` is the platform-neutral drawing interface: save/restore/transform, rects,
circles, rings with linear and sweep gradients, paths, arcs, images, text with measured
metrics, offscreen layers with cut-outs (destination-out), shadow capture and blur, clipping,
difference and luminosity blends, sprite capture and rotated blitting. Two adapters exist:
`AwtDrawingContext` (tests, `java.awt.Graphics2D`) and `AndroidCanvasDrawingContext` (app).

`StaticRenderer` draws dials (background, tick and dot marks, guilloche line and arc marks,
compass roses, text in upright, demi, radial, rotated and tachymeter orientations), images,
rects with curved pane dividers, text labels and static groups. `HandRenderer` draws every hand
type with ornaments, tails, outline widths, gears with their luminosity overlays, and drop
shadows. `WheelWedge` draws rotating text wheels (full circle and partial arc, half-and-half
difference-blended `TWheel`s) and annular wedges. `Terminator` renders the moon-phase leaf
texture. `CalendarRenderer` handles the perpetual calendar grids and their covers, including
the October 1582 gap. `Analemma` draws Hernandez's figure-eight.

`WatchRenderer.renderFrame` walks the parts in document order for the requested mode,
accumulates `Window` parts and punches their holes into the following part, and drives the
per-part update clock.

## The render architecture

The frame model is the iOS one, arrived at in stages:

1. **Static cache.** `renderFrame` takes an optional `StaticCache`. The part list is split
   into maximal static runs (dials, images, rects, text, static groups, buttons, and window
   clusters whose cut-out target is itself static). Each run is baked once into a device-space
   sprite and re-blitted each frame; dynamic parts evaluate and draw live between the sprites in
   document order. A run only begins with no windows pending, and windows that target a dynamic
   part fall back to the uncached path, so semantics are unchanged. `StaticDeps` computes which
   variables and which non-pure leaf functions a face's static content reads, so an action that
   changes only the time base does not force a re-bake.
2. **Baked hand sprites.** With a cache present, each hand is rasterized once at its
   first-frame pose (content sprite plus a pre-blurred shadow sprite; the blur is
   rotation-invariant so rotating the baked shadow is exact) and then blitted rotated by the
   angle delta about its pivot. Excluded and drawn live: Terra's world-time ring (city colors
   change with the date) and offset image hands (they orbit without spinning). On the device,
   upright hands are baked cropped to thin strips.
3. **Terminator and wheel sprites.** Terminator leaves have phase-independent shapes (phase
   only spins each leaf about its rim anchor, exactly as `ECTerminatorLeaf` does), so a per-leaf
   sprite bank reassembles a new phase in 24 rotated blits. Full-circle wheels are sprites;
   partial-arc wheels counter-rotate their labels and half-and-half wheels blend against the
   pixels beneath them, so both stay live.
4. **Beat clock.** Every part has an update interval (iOS default 1 s; Geneva's seconds hand
   `.1`, Tombstone's escapement `updateRate` and balance wheel `updateRate/30`). The interval
   floors at the watch's beat grid (`1/beatsPerSecond`) and rounds to the 5 ms grid iOS uses.
   The environment's display time is quantized to the part's epoch, so ticking, sweeping and
   rocking all emerge from the XML's continuous expressions. Stateful expressions are evaluated
   once per epoch, never once per rendered frame.
5. **Sweep animation.** `AngleAnimator` reproduces the iOS easing, stop time and snap
   threshold at 2 rad/s times `animSpeed`; linear motion (`xOffset`/`yOffset` on stems and
   pushers) slides at 80 px/s times `animSpeed`. Repeat-while-held buttons honor
   `animationOverrideInterval`, which compresses sweeps while the button is held.

Without a cache, the single-pass path is byte-identical to the original renderer;
`StaticCacheTest` proves the cache is invisible on faces that exercise buttons and chrome,
window-before-static reveals over digit wheels, windows with subdials, and gears.

## The Android layer

- **`AndroidCanvasDrawingContext`** mirrors the AWT adapter: an explicit offscreen-layer stack
  with `PorterDuff.CLEAR` hole punching, a tracked transform matrix (`Canvas.getMatrix` is not
  reliable), fractional `Paint.FontMetrics`, `BlendMode.DIFFERENCE` and `LUMINOSITY` (hence
  `minSdk 29`), native radial/linear/sweep gradients, and shadows via `Bitmap.extractAlpha`
  plus `BlurMaskFilter`. Every primitive unions its conservative device bounds into the current
  layer's dirty rect: shadows crop to the silhouette before the blur, composites blit only the
  dirty region, and pooled layer bitmaps (`LayerBitmapPool`) erase only what was touched. This
  is what makes Terra's 31 shadowed hands affordable. Canvas arc angles are screen-clockwise
  like the engine's Y-down space, so there is no AWT-style negation.
- **`WatchView`** owns the render thread (a `HandlerThread` with coalesced jobs), triple
  buffering with a locked hand-off (the earlier double buffer tore under 30 fps Tombstone),
  adaptive scheduling on the face's fastest update epoch (Tombstone ~30 fps, Geneva 10, most
  faces 1 Hz), gestures (manual slop and `VelocityTracker` handling so a bare tap stays free
  for the watch's own buttons and hand dragging), the slide between faces with live neighbor
  peeks and the name labels, `slideToFace` for chained alarm rides, the 3-D flip, hold-repeat
  with a drift-anchored timer, hand dragging, and the alarm bell.
- **`MainActivity`** holds the corner controls (picker, lights-out, flip, settings, info,
  list), the status line, the alarm glyph and the "1 alarm" tap reveal, the info panel (a
  `WebView` over the bundled iOS help), and the settings dialog.
- **`FaceCatalog` / `FaceRepository`** are manifest-driven: faces load on one loader thread
  (parse three modes plus decode bitmaps), an LRU keeps three resident, swipe neighbors preload,
  and per-face interaction state stays session-resident so background alarms keep counting
  down. `FrameClock` is the engine's `TimeSource`. Terra is the only face with its own extra
  environment registration.
- **`FaceVarStore`** persists per-face variables in `SharedPreferences` using the iOS key
  names one to one (alarm, stopwatch, warp, planet body, per-watch mode).
- **`LocationStore`** uses plain `android.location`, no Play Services. The last good fix
  persists; on launch or resume it seeds from every provider's last-known location and requests
  a single fresh fix per enabled provider. A move of about 5 km rebuilds the face environments
  (the observer is baked in at registration). San Francisco is the fallback until a first fix.
- **`MiniFaces`** renders the live picker grid: all 25 faces once a second at cell scale on
  their own thread, double-buffered by sweep parity, using `inSampleSize` decoding so all faces
  stay resident.
- **Audio.** The alarm bell is the upstream `Triangle.wav` played through `SoundPool` with
  overlapping voices, 20 strikes at 0.5 s like `ECAudio`, silenced by touch.

## Assets and staging

`engine/src/test/resources` is the single source of truth for face data: the staged XML for
each face, the face art at the best available resolution, the flattened partsBin chrome (case,
band, back, buttons, wallpaper), and the PT Sans preview font. The app's Gradle build copies
every `*.png` and `*.xml` from there into a generated assets directory, so 52 MB of art is not
duplicated in git.

`app/src/main/assets/faces.json` is generated by `tools/gen_face_manifest.py`, which parses
the per-face render tests (their image maps are the contract: literal `"key" to img(...)`
entries only) and cross-checks every `src=` in each staged XML against the manifest and the
chrome. It fails on any gap. Re-run it whenever a render test's image map changes.

Staging conventions:

- Prefer the `-4x` asset at scale 0.25. Some art exists only at 1x and is fine at device
  resolution; London's dial is a 304 px image on iOS too and is inherently soft.
- When iOS ships only low-resolution art, a higher-resolution version can sometimes be
  synthesized (a crisp alpha from one asset under a low-frequency color gradient from another),
  and the web port occasionally has higher-resolution art of the same piece that can be
  registered to the iOS framing by centroid and overlap search.
- `saturnb` is the porthole-framed Saturn and only exists at 2x; the sharp `saturn-4x` glyph
  is larger and overflows the porthole, so it is not a drop-in.
- The help bundle is the pruned upstream `Help/` directory with `product.css` and a generated
  contents page.

Fonts: the faces specify Arial, Arial Bold, Times New Roman and Verdana. The app bundles
Liberation Sans, Serif and Mono (metric-compatible with Arial, Times New Roman and Courier),
PT Sans for Verdana, and a DejaVu Sans glyph subset for the alarm note glyphs, and loads them
by XML font name. CJK text (Kyoto's zodiac) falls back to system fonts.

## Verification

- **Expression tests** are the ported vitest suite.
- **Astronomy goldens** (`astro-golden.json`, `astro-riseset-golden.json`,
  `astro-back-golden.json`, `astro-watchenv-golden.json`) were produced by running the
  TypeScript astronomy under Node. Positions match to 1e-9, rise/set to 0.05 s. They are an
  independent implementation, which is how a Kotlin `Int` overflow (`24*3600*36525`) was caught
  on day one. The calendar is additionally checked against `java.time`.
- **Render tests**, one per face and mode, compose the face on the full 320×480 iOS logical
  screen with real chrome and buttons over the granite wallpaper (`PhoneFrame`) at 1024×1536
  and assert key pixels. Their PNG output in `engine/build/` is the visual review artifact; it
  was compared against iPhone screenshots for every face and side, 75 pairs in the last full
  pass.
- **`StaticCacheTest`** asserts cached third-frame renders equal single-pass renders within
  compositing rounding. **`StaticVolatileFuncsTest`** pins that no shipped face bakes a
  non-pure leaf into static content. **`StatefulEvalTest`**, **`AngleAnimatorTest`**,
  **`HandGrabTest`**, **`ButtonInteractionTest`**, **`WatchTimeTest`**, **`WatchAlarmTest`**,
  **`StopwatchTest`** and **`TerraSlotTest`** cover the interaction layer.
- Goldens are byte-exact by design: on the device, upright-hand baking and other
  device-only optimizations are switched on only in `WatchView`, never in tests.
- The AWT harness needs two settings to match Android: bicubic image interpolation (Java2D
  defaults to nearest-neighbor even under `RENDER_QUALITY`) and `CAP_BUTT` stroke caps
  (`BasicStroke` defaults to `CAP_SQUARE`, which overhangs every open line by half its width).
- Real Arial or Times New Roman files dropped into the git-ignored `docs/` directory are
  picked up by the preview renderer for closer comparison, but the committed goldens are pure
  free-font renders, reproducible from a clean checkout.

## iOS fidelity notes

Everything in this section was wrong in the port at some point, usually because it was
inherited from the web port or guessed, and was then corrected against the iOS source or the
iPhone itself. They are grouped by subsystem.

### Modes and windows

- **A part without a `modes` attribute is visible in all modes on iOS.** The parser originally
  defaulted it to front-only, which silently hid night and back displays: AtlantisIV's night
  and back digit windows, Istanbul's teal alarm-state ring (missing on night, leaking into
  front), Geneva and Chandra night portholes. Windows inherit their mode from context on iOS,
  which consumes them positionally from a bin rather than filtering. The staged XMLs carry
  explicit `modes` patches where needed; check every new face with portholes or top-level
  no-modes parts.
- **Window frames:** iOS defaults every window to a 2-unit black border. Rectangular windows
  are stroked and then cleared, so only the outer half of the stroke survives; portholes are
  stroked after clearing, so their full width shows. Windows are stroked and cleared per window
  in document order inside the same texture, so a later window's clear erases an earlier
  window's border (Istanbul's am/pm "pill" is two overlapping windows that only read as a pill
  because of this).
- **Static textures:** inside a `<static>` group, iOS renders all pieces into one texture
  placed at the union of their bounds, and `QDial` pieces land at `xmlPos + unionCenter` while
  images and text are placed faithfully. It only matters when the union is asymmetric.
  Tombstone's pendant loop skews it by +29.5 units and the XML's `dial y='-30'` pre-compensates.
  Caveat: the union on iOS includes case and band art that the port may substitute, so an
  apparently off-center piece can fake a sub-unit shift; offsets under 2 units are treated as
  null.

### Line widths and shapes

- Hand `lineWidth` unset is `fillColor == 'clear' ? 1.0 : 0.25` (`ECHandLineWidthOutline`
  and `ECHandLineWidthFill`); `oLineWidth` unset uses the same conditional, and `tLineWidth`
  unset is the resolved `oLineWidth`. The web port's flat 0.5 was wrong in both directions.
  `QWedge borderWidth` (Terra uses 0.25 and 0.07) and day/night-ring wedges use the same
  default.
- `tri` hands always have a 4-point outline whose tail tapers to a point (with a small notch
  when `oRadius` is non-zero); a flat-wedge counterweight was a port invention.
- `QRect` pane dividers are arcs of a huge circle (radius 2.5 times the height, center far
  to the left for positive pane counts and to the right for negative), bowing about a
  twentieth of the height. Straight lines were the web simplification.
- Gear elements never reset the Core Graphics line width, so the hub strokes at the spoke
  width and the pinion disc at whatever is residual (spoke width for leafless gears, 2 for
  single dots, leaf width for pinions). The solid red dots on Tombstone depend on this.
- Compass-rose petals are stroked only when clear-filled.
- The mainspring and keyless-works overlays composite with a true luminosity blend stretched
  over the gear square.
- Terminator leaf borders use the Core Graphics default 1.0 stroke.
- Hand `tail` defaults to `ceil(length/10)`; `sun` hands center at `length2 + raysRad`.

### Dial text

The glyph line box is top-aligned with its outer edge at the radius, and the two halves of a
demi dial are deliberately asymmetric, which is what `demiTweak` corrects per face:

- Radial and the upper demi half: baseline at `radius − ascent`.
- Anti-radial (lower demi) half: iOS's flip-and-rotate composition puts the baseline at
  `radius − lineHeight + ascent` with the ink extending inward.
- `demi` uses the radius exactly. The `ECDialRadiusFactor` of 0.92 applies to demi only when a
  `tick` attribute is set, and no demi dial sets one. `tachy` always applies 0.92 (this is
  what put Olympia's tachymeter numerals back on the white flange).
- Plain `radial` uses a box `[0.92r − h, 0.92r]`, top-aligned.
- Text height is the measured font line height (`sizeWithFont`), not the font size, in all
  orientations. Using the font size is why a "31" once peeked above its date-ring cover.
- Wheel labels are measured and drawn untrimmed, spaces included. The alarm note string
  `',♬ ,,♫ ,,♪ '` needs its trailing space to seat the glyph. AWT ignores trailing
  whitespace, so the goldens never showed the bug; Android's `measureText` counts it.

### Evaluation and update timing

- **Never fold live attributes at parse time.** Any `angle`, `offsetAngle` or `motion`
  expression whose variables happen to be parse-time constants would freeze forever:
  Tombstone's keyless gears (`stat==1 ? tic=tic+2*pi/180 : tic`), McAlester's stem buttons
  and its Easter-egg image hands. Miami escaped only because its selector init references a
  runtime variable, which is luck, not design.
- **Stateful expressions accumulate per update epoch, not per render.** With per-frame
  evaluation, Tombstone's winding would run at 8°/s at rest but 125°/s during any sweep
  animation; iOS holds a constant 100°/s.
- **The update interval floors at the beat grid.** Tombstone is `beatsPerSecond='4'`, so its
  balance wheel (`update='updateRate/30'`, about 8 ms in the XML) still samples every 0.25 s,
  and its 2 Hz sine lands exactly on the zeros. The iPhone's balance is stationary by
  arithmetic, and so is the port's.
- **There is no `animate` tween.** The attribute appears in the XML and iOS never reads it.
  An interpolation between update samples was built, made the pallet lever rock beautifully,
  and was deleted for fidelity.
- **Every angle evaluation goes through `fmod2pi`.** iOS wraps with `EC_fmod` at each
  evaluation; without it, accumulated angles lose float precision and sweeps visibly freeze.
- Year-driven parts (`update='1 * years()'`) are evaluated at the current time whenever they
  fire and force-updated on launch and switch; quantizing the time instead can show last year
  for hours after New Year.
- Repeat-while-held buttons fire on a 100 ms cadence anchored to absolute uptime, not to the
  previous frame's completion; anchoring to frames drifted to 134 to 167 ms.
- `staticVolatileFuncRefs` is empty for all shipped faces; a non-pure action such as
  `advanceDay` therefore must not invalidate the static cache (it used to cost a 30 to 80 ms
  re-bake per press).

### Shadows

Hand drop shadows and window inner shadows are Gaussian blurs of a captured silhouette
composited in a fixed screen direction (down-right), following the opacity and sigma formula of
iOS's `makeOneShadow.pl` and the `z/4.3`, `z/2.15` offsets. The shadow does not rotate with the
hand; the web port's rotating approximation was wrong. On Android the blur is
`Bitmap.extractAlpha` plus `BlurMaskFilter` with `radius = (sigma − 0.5) / 0.57735`.

### Astronomy and environment

- `QdayNightRing` needs both leaf variants (`dayNightLeafAngle` for clock time,
  `dayNightLeafAngleLST` for `timeBase='LST'`) and applies `masterOffset`; 24-hour watches set
  it to π for noon on top, and forgetting it swaps day and night. White is day, gray is night
  with the moon up, black is night with the moon down.
- The web port's LST leaf returns NaN because it stripped the back; the port's ring is
  therefore verified against the iPhone by eye, while the scalar back functions are
  golden-validated.
- `DSTNumber()` is computed from the time environment, not stubbed; Atlantis and Geneva have
  DST indicator hands.
- `goodAccuracy()` follows the location-overridden state, which is what enables Uraniborg's
  bad-accuracy sidereal fallback.
- Terra's world-time ring constants (city name font and radii, channel arcs, dashes) come from
  the live code in `ECGLPart.m`, not from the arithmetic in a nearby comment. Terra's back
  subdial labels likewise use the live values beside the comment.

### App shell

- Mode is **per watch and persisted** (`<name>-ModeNum`): a watch flipped to its back stays
  on its back across switches and relaunches, and night is per watch too. Switching to a watch
  adopts that watch's own mode.
- There is no back-night mode. The flip button slides out in night mode.
- Corner chrome swaps to dim night art in night mode.
- The watch name shows at launch and on every programmatic switch, not only after a swipe.
- The background-alarm switch slides the whole face strip in one continuous motion in the
  scan direction (`setAllNoGridPositions` with a 0.5 s interval), showing the intermediate
  faces, then shows the target's name. The alarm glyph in the corner is the `alarm state`
  `SWheel` of `Background.xml` (one alarm shows ♬, two ♫, three or more ♪) with a `gotoAlarmer`
  button over it that runs `switchToNextActiveAlarmWatch()`.
- The alarm bell rings a bounded number of strikes (Triangle: 20 at 0.5 s) with overlapping
  decay tails, and silence on touch. `MediaPlayer.seekTo(0)` cut each strike's tail and sounded
  like sparse single dings; `SoundPool` retriggering matches. The system default alarm
  ringtone can be unset on de-Googled devices, so a bundled sound is required anyway.
- Debug-signed release builds are what run on a device. A debuggable APK disables most of
  ART's optimization and renders about ten times slower on expression-bound work, which once
  masqueraded as an app-wide regression.

### Features that turned out to be disabled on iOS

Each of these was implemented and then retracted after finding the iOS mechanism commented
out. The lesson is to search the canonical XML and source for comment markers before porting
a feature that is not visible on the iPhone.

- Tick/tock sounds: the `AudioFX` calls are commented out in `ECVirtualMachineOps.m`.
- Terra's ring drag: the ring-mover `TWheel` and the `worldtimeRingKind` hand are
  XML-commented; the shipping app rotates the city ring only via the two pushers. The drag
  machinery remains as dormant code mirroring iOS's own unreferenced implementation.
- Thebes' back tachymeter tick marks: the `'tachy marks'` dial sits inside an XML comment.
  The `tachym` mark type exists in the port as inert support.

### Android drawing pitfalls

- `Canvas.getMatrix` is unreliable; the adapter tracks its own matrix.
- Layer bitmaps at full resolution are about 10 MB each; allocating them per frame is a
  frame-time disaster, so they are pooled and dirty-rect erased.
- The granite wallpaper must be pre-scaled once per size; rescaling it per frame was a
  60 to 100 ms floor under every face.
- The `postAtFrontOfQueue` render job can starve plain posts on faces that render
  back-to-back (Tombstone); touch handling, neighbor peeks and warm-ups must not depend on a
  plain post being serviced.
- Allocation in the sprite blit path (`Matrix`, `Rect` per call) caused GC spikes at
  thousands of allocations per second; scratch objects are reused.
- Frames are tagged with their face identity so a stale buffer can never show the wrong face
  after a swipe commit.

## Known deliberate divergences

Documented and left as is:

- **Stride 390/320.** The inter-watch gap models a 390-point iPhone while the watch is scaled
  like a 320-point device, so a granite gap shows between faces mid-slide where the
  matching-scale iPhone shows watches edge to edge.
- **Chronograph tick grid** is not aligned to the chronograph's own epoch (a constant sub-tick
  offset of at most 0.1 s on Olympia, phase set at the start press).
- **`updateOffset`** is parsed but not consumed; every shipped XML sets it to 0.
- **Mid-flight sweep retarget** uses the previous target rather than the current hand
  position, and there is no mid-flight early snap.
- **`digitValue`** uses its own rounding variant.
- **Action evaluation** throws on undefined variables where iOS logs and returns 0.
- **`updateAtNextSunrise` / `DSTChange` / `envChange`** degrade to 1 s polling; the values are
  equivalent, the scheduling is not modeled.
- **Environment variables reset on LRU eviction** except the persisted ones (`body` and the
  `__`-prefixed time, alarm and stopwatch state). XML state machines such as Tombstone's or
  McAlester's `stat` rebuild to 0 after a face is evicted. iOS keeps every watch resident for
  the session.
- The `♪/♫/♬` glyphs render from the bundled DejaVu subset, which is close to but not
  identical to iOS's symbol fallback.
- **AtlantisIV GPS readouts** (altitude, horizontal error, unit system, location indicator)
  are fixed stub values from the manifest; only latitude and longitude are live.
- No NTP synchronization and no indicator lights.

## Measurement techniques

These are the methods that settled the timing questions, kept here because they will be
needed again.

- **iPhone ground truth from a screen recording:** crop the red "Off by …" warp banner from
  each frame (`crop=W:70:0:245,negate,eq=contrast=2`), OCR it with tesseract (psm 7, digit
  whitelist), and diff the parsed offsets to get the exact act cadence. The same works on the
  Android app.
- **Reproducible touch-and-hold:** `adb shell input swipe X Y X Y 6000` holds a button for
  6 s. Face units convert to pixels with `scale = min(W/320, H/480)` about the screen center.
- **Frame log:** `adb logcat -v epoch -s Beryl` prints a frame line every 60th frame or for
  any frame over 150 ms, with the current animation overrides, and an `action <name>` line
  whenever an action changes environment variables.
- **Presentation cadence:** `screenrecord` output is variable frame rate; histogram
  `ffprobe -show_entries frame=pts_time` deltas against the 16.7 ms grid rather than counting
  frames.
- **Per-part profiling:** wrap the dynamic-part draw call in `renderFrame` with `nanoTime`,
  aggregate by part name and print every 120 frames. It is a throwaway; do not leave it in.

## Tools

- `tools/gen_face_manifest.py` regenerates `app/src/main/assets/faces.json` from the render
  tests and validates every XML `src=` against the staged assets. It also emits picker
  thumbnails from front previews in `docs/`, so run the render tests and
  `tools/refresh-previews.py` first if thumbnails need regenerating.
- `tools/convert_planet_tables.py` rebuilds `planet-tables.bin` from the upstream
  `esastro` header, cross-checking against the `chronometer-web` tables. It expects the two
  upstream checkouts as siblings of this repository, or `EMERALD_SRC` pointing at a directory
  that contains them.
- `tools/refresh-previews.py` copies fresh test renders from `engine/build/` into the
  git-ignored `docs/` directory under `<face>-{front,night,back}.png` names.
- `PebbleStaticExportTest` renders each face's static layer face-only at 260×260 into
  `engine/build/pebble/` for a separate Pebble watch-face project that uses this engine as an
  offline asset compiler.
