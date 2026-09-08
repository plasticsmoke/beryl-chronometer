#!/usr/bin/env python3
"""Generate the app's face manifest (faces.json) + picker thumbnails from the engine render tests.

The render tests are the single source of truth for how each face is assembled: which staged XML
it renders, which face-specific images map to which XML `src` keys (and at what unit scale), and
which iOS-only env leaves must be stubbed to constants (alarm/stopwatch/GPS/planet-selector).
This script extracts that data instead of hand-maintaining a parallel Kotlin registry.

Extraction handles the three test idioms:
  "key" to img("/file.png", scale)
  "key" to LoadedImage(AwtImage(resourceImage("/file.png")), scale)
  f["leaf"] = { <const expr> }            (also the for (x in listOf(...)) f[x] = { c } loop)
  env.variables["name"] = <number>

Run from the android/ repo root:  python3 tools/gen_face_manifest.py
Outputs: app/src/main/assets/faces.json + app/src/main/assets/thumbs/<face>.png
Re-run whenever a render test's image map or stubs change.
"""
import glob
import json
import math
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEST_DIR = os.path.join(ROOT, "engine/src/test/kotlin/com/plasticsmoke/beryl/render")
RES_DIR = os.path.join(ROOT, "engine/src/test/resources")
DOCS_DIR = os.path.join(ROOT, "docs")
ASSETS_DIR = os.path.join(ROOT, "app/src/main/assets")
THUMB_HEIGHT = 300

# Non-face test files: renderer-pipeline smoke fixtures (Milano-I/Geneva-I derivatives) + tooling.
SKIP_FILES = {"StaticRenderTest", "WatchRenderTest", "PebbleStaticExportTest"}

DISPLAY_NAMES = {
    "AtlantisIV": "Atlantis IV",
    "ChandraII": "Chandra II",
    "MaunaKea": "Mauna Kea",
}


def face_of(test_base):
    """Map a test file base name (sans RenderTest) to its face name."""
    if test_base in ("Night", "Back"):  # Geneva's night/back tests predate the naming convention
        return "Geneva"
    for suffix in ("Back", "Night"):
        if test_base.endswith(suffix) and test_base != suffix:
            return test_base[: -len(suffix)]
    return test_base


def eval_const(expr):
    """Evaluate a Kotlin constant expression (numbers, Math.PI, + - * /)."""
    py = expr.replace("Math.PI", "math.pi").strip()
    if not re.fullmatch(r"[0-9math.pi+\-*/(). eE_]+", py):
        raise ValueError(f"non-constant stub expression: {expr!r}")
    return float(eval(py, {"__builtins__": {}}, {"math": math}))


def main():
    faces = {}
    warnings = []

    for path in sorted(glob.glob(os.path.join(TEST_DIR, "*Test.kt"))):
        base = os.path.basename(path)[: -len("Test.kt")]
        if not base.endswith("Render"):
            continue
        base = base[: -len("Render")]
        if base + "RenderTest" in SKIP_FILES or base in ("Static", "Watch"):
            continue
        face_name = face_of(base)
        text = re.sub(r"\s+", " ", open(path).read())

        face = faces.setdefault(face_name, {"images": {}, "stubs": {}, "vars": {}, "xml": None})

        for m in re.finditer(r'resourceText\("/([^"]+\.xml)"\)', text):
            xml = m.group(1)
            if face["xml"] and face["xml"] != xml:
                warnings.append(f"{face_name}: multiple XMLs {face['xml']} vs {xml}")
            face["xml"] = xml

        # The img() helper's default scale, when the test defines one (two shapes:
        # `fun img(name: String) = LoadedImage(..., 0.25)` and `scale: Double = 0.25`).
        default_scale = None
        m = re.search(r'fun img\(name: String\) = LoadedImage\(AwtImage\(resourceImage\(name\)\), ([0-9.]+)\)', text)
        if m:
            default_scale = float(m.group(1))
        m = re.search(r'fun img\(name: String, scale: Double = ([0-9.]+)\)', text)
        if m:
            default_scale = float(m.group(1))

        entries = re.findall(r'"([^"]+)" to img\( ?"/([^"]+)", ?([0-9.]+) ?\)', text)
        entries += re.findall(
            r'"([^"]+)" to LoadedImage\(AwtImage\(resourceImage\("/([^"]+)"\)\), ?([0-9.]+) ?\)', text
        )
        for key, fname in re.findall(r'"([^"]+)" to img\( ?"/([^"]+)" ?\)', text):
            if default_scale is None:
                warnings.append(f"{face_name}: img() without scale and no default in test")
                continue
            entries.append((key, fname, default_scale))

        # Guard against non-literal map idioms (buildMap/put/helper-fn entries): they extract
        # nothing here, and the face renders image-less in the app. The XML cross-check below
        # catches the resulting gaps, but flag the construct too.
        if re.search(r"buildMap|\.associate", text):
            warnings.append(f"{face_name}: test uses a computed image map — make it literal mapOf entries")
        for key, fname, scale in entries:
            prev = face["images"].get(key)
            if prev and prev != (fname, float(scale)):
                warnings.append(f"{face_name}: key {key!r} maps to both {prev} and {(fname, scale)}")
                continue
            face["images"][key] = (fname, float(scale))

        for m in re.finditer(r'for \(\w+ in listOf\( ?((?:"[^"]+", ?)*"[^"]+"),? ?\) ?\) f\[\w+\] = \{ ?(.*?) ?\}', text):
            names = re.findall(r'"([^"]+)"', m.group(1))
            val = eval_const(m.group(2))
            for n in names:
                face["stubs"][n] = val
        for m in re.finditer(r'f\["(\w+)"\] = \{ ?([^}]+?) ?\}', text):
            try:
                face["stubs"][m.group(1)] = eval_const(m.group(2))
            except ValueError as e:
                warnings.append(f"{face_name}: {e}")
        for m in re.finditer(r'env\.variables\["(\w+)"\] = ?([-0-9.eE]+)', text):
            face["vars"][m.group(1)] = float(m.group(2))

    # Validate: every referenced file exists in the test resources.
    for name, face in faces.items():
        if not face["xml"]:
            warnings.append(f"{name}: no XML found")
            continue
        for ref in [face["xml"]] + [f for f, _ in face["images"].values()]:
            if not os.path.exists(os.path.join(RES_DIR, ref)):
                warnings.append(f"{name}: missing resource {ref}")

    # Cross-check: every image src the face XML references must be resolvable in the app —
    # either a manifest entry or (for ../partsBin/…) a flattened chrome file. This is what
    # catches image-map idioms the extractor didn't parse (a missing map entry renders the
    # face silently without that art).
    # Parts that never appear at the rendered state are deliberately unstaged:
    known_absent = {
        # McAlester's open-the-case "inmates" layers orbit off-screen at stat=0 (closed).
        ("McAlester", "guts.png"),
        ("McAlester", "back.png"),
    }
    chrome_files = set(os.listdir(RES_DIR))
    for name, face in faces.items():
        if not face["xml"]:
            continue
        xml_text = open(os.path.join(RES_DIR, face["xml"])).read()
        xml_text = re.sub(r"<!--.*?-->", "", xml_text, flags=re.S)
        for src in set(re.findall(r"src='([^']+)'", xml_text)):
            if src in face["images"] or (name, src) in known_absent:
                continue
            if src.startswith("../partsBin/"):
                flat = "partsbin-" + src[len("../partsBin/"):].removesuffix(".png").lower().replace("/", "-")
                if any(f"{flat}{sfx}.png" in chrome_files for sfx in ("-4x", "-2x", "")):
                    continue
            warnings.append(f"{name}: XML src {src!r} has no manifest entry or chrome file")

    # Thumbnails from the committed docs previews (front mode).
    from PIL import Image

    os.makedirs(os.path.join(ASSETS_DIR, "thumbs"), exist_ok=True)
    for name in faces:
        src = os.path.join(DOCS_DIR, f"{name.lower()}-front.png")
        if not os.path.exists(src):
            warnings.append(f"{name}: no docs preview {src}")
            continue
        im = Image.open(src)
        w = int(im.width * THUMB_HEIGHT / im.height)
        im.resize((w, THUMB_HEIGHT), Image.LANCZOS).save(
            os.path.join(ASSETS_DIR, "thumbs", f"{name.lower()}.png"), optimize=True
        )

    manifest = {
        "faces": [
            {
                "name": name,
                "display": DISPLAY_NAMES.get(name, name),
                "xml": face["xml"],
                "thumb": f"thumbs/{name.lower()}.png",
                "images": [
                    {"key": k, "file": f, "scale": s} for k, (f, s) in sorted(face["images"].items())
                ],
                "stubs": face["stubs"],
                "vars": face["vars"],
            }
            # Rotation order: alphabetical by display name (the iOS default archive order).
            for name, face in sorted(faces.items(), key=lambda kv: DISPLAY_NAMES.get(kv[0], kv[0]))
        ]
    }
    out = os.path.join(ASSETS_DIR, "faces.json")
    with open(out, "w") as fp:
        json.dump(manifest, fp, indent=1)

    print(f"{len(faces)} faces -> {out}")
    for name in manifest["faces"]:
        print(f"  {name['name']:12} xml={name['xml']:24} images={len(name['images']):2} "
              f"stubs={len(name['stubs'])} vars={len(name['vars'])}")
    if warnings:
        print("\nWARNINGS:")
        for w in warnings:
            print("  " + w)
        sys.exit(1)


if __name__ == "__main__":
    main()
