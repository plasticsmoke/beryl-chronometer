# Third-party notices

Beryl Chronometer is an independent project. It is not affiliated with or endorsed by
Emerald Sequoia LLC. "Emerald Chronometer" and "Emerald Sequoia" are the names of
Emerald Sequoia LLC's products and company; they appear in this repository only to
identify the original work and in the reproduced watch-face artwork and help pages.

## Emerald Chronometer (Emerald Sequoia LLC) — MIT

Source: <https://github.com/EmeraldSequoia/Chronometer> and
<https://github.com/EmeraldSequoia/chronometer-web>.
License text: `licenses/EmeraldSequoia-Chronometer-MIT.txt`.

Reused verbatim or with minimal staging edits (the app icon is a crop of this engine's
Tombstone render, so it derives from the Tombstone artwork below):

- Watch-face definitions (`engine/src/test/resources/*.xml`) and their artwork
  (`engine/src/test/resources/*.png`), including the partsBin case, band, button and
  wallpaper art.
- The per-face help pages (`app/src/main/assets/help/`).
- The alarm sound `Triangle.wav` (`app/src/main/res/raw/triangle.wav`).
- The face thumbnails (`app/src/main/assets/thumbs/`), rendered by this engine from the
  face definitions above.

Ported (rewritten in Kotlin from the TypeScript and Objective-C++ sources): the
expression language, watch model and XML parser, astronomy (Willmann-Bell planetary
series, Chapront lunar series, rise/set solver, eclipse classification, sidereal time,
Delta-T), and the renderer. The planetary coefficient tables
(`engine/src/main/resources/planet-tables.bin`) are converted from the upstream C header.

## Liberation Fonts (Red Hat, Inc.) — SIL Open Font License 1.1

`app/src/main/assets/fonts/Liberation{Sans,Serif,Mono}-{Regular,Bold}.ttf`. Copyright (c) 2012 Red Hat, Inc. with Reserved Font
Name Liberation. Digitized data copyright (c) 2010 Google Corporation with Reserved Font
Arimo, Tinos and Cousine. License text: `licenses/OFL-1.1.txt`.

## PT Sans (ParaType Ltd.) — SIL Open Font License 1.1

`app/src/main/assets/fonts/PTSans-Regular.ttf` and `engine/src/test/resources/font-ptsans-regular.ttf`.
Copyright (c) 2009 ParaType Ltd. with
Reserved Font Names "PT Sans", "PT Serif" and "ParaType". License text:
`licenses/OFL-1.1.txt`.

## DejaVu Sans — Bitstream Vera license

`app/src/main/assets/fonts/DejaVuSans-music.ttf`, a glyph subset of DejaVu Sans used for
the alarm note glyphs. License text: `licenses/DejaVu-Bitstream-Vera.txt`.
