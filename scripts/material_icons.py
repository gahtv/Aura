#!/usr/bin/env python3
"""Material Symbols -> Aura drawables.

Google's Material Symbols SVGs use viewBox "0 -960 960 960": every Y is
negative (the glyph is centred on y=-480). Dropping that path data straight
into an Android VectorDrawable renders NOTHING, and neither viewport 24 nor
960 alone fixes it -- the Y coordinates have to be shifted by +960 too.

This script downloads the official SVGs and rewrites the path data into
absolute commands with Y+960, so the result is a normal 24dp vector with a
960x960 viewport.

    python3 scripts/material_icons.py android favorite favorite_fill1 ...
    python3 scripts/material_icons.py web favorite play_arrow ... > sprite.svg
    python3 scripts/material_icons.py raw favorite            # print the SVG

Names are the repo folder names under symbols/web/. The filled variant of an
icon is <name>_fill1; symbols that only exist filled are named plain.
"""
import os
import re
import sys
import urllib.request

BASE = "https://raw.githubusercontent.com/google/material-design-icons/master"
STYLE = "materialsymbolsrounded"

DRAWABLE = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app/src/main/res/drawable",
)

TOKEN = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]|-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")


def fetch(symbol, weight=None):
    """Download one symbol's SVG source. `symbol` may be 'favorite_fill1'."""
    weight = weight or "400"
    if symbol.endswith("_fill1"):
        base, variant = symbol[: -len("_fill1")], "fill1"
    else:
        base, variant = symbol, None
    suffix = f"_{variant}" if variant else ""
    if weight != "400":
        suffix += f"_wght{weight}"
    url = f"{BASE}/symbols/web/{base}/{STYLE}/{base}{suffix}_24px.svg"
    try:
        with urllib.request.urlopen(url, timeout=30) as r:
            return r.read().decode("utf-8")
    except Exception:
        # Some macOS python.org builds ship without a CA bundle; curl is the
        # reliable fallback (and this script is a dev-time tool anyway).
        import subprocess

        return subprocess.run(
            ["curl", "-sfL", url], check=True, capture_output=True, text=True
        ).stdout


def path_data(svg):
    paths = re.findall(r'<path[^>]*\sd="([^"]+)"', svg)
    if not paths:
        raise SystemExit("no <path> in svg")
    return "".join(paths)


def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def absolutise(d, dy=0.0):
    """Translate every absolute coordinate: Y gets +dy.

    Emits only M/L/C/Q/Z so the result is unambiguous for VectorDrawable.
    """
    toks = TOKEN.findall(d)
    out = []
    i = 0
    cx = cy = sx = sy = 0.0
    cmd = None
    prev_cmd = None
    prev_c2 = None  # last C control point (for S)
    prev_q = None   # last Q control point (for T)
    while i < len(toks):
        t = toks[i]
        if t.isalpha():
            cmd = t
            i += 1
            if cmd in "Zz":
                out.append("Z")
                cx, cy = sx, sy
                prev_cmd = cmd
                continue
        elif cmd is None:
            raise SystemExit("path does not start with a command")

        rel = cmd.islower()
        up = cmd.upper()
        args = {
            "M": 2, "L": 2, "T": 2,
            "H": 1, "V": 1,
            "C": 6, "S": 4, "Q": 4, "A": 7,
        }[up]
        vals = []
        for _ in range(args):
            if i >= len(toks) or toks[i].isalpha():
                raise SystemExit(f"truncated {cmd} command")
            vals.append(float(toks[i]))
            i += 1

        # Work in the SVG's own coordinate space; add dy only when emitting.
        # (Applying dy to the running point instead breaks paths that OPEN
        # with a relative moveto, e.g. "m274-200 ...": the very first point
        # then never gets shifted and the glyph renders mangled.)
        def px(v):
            return v + cx if rel else v

        def py(v):
            return v + cy if rel else v

        if up == "M":
            cx, cy = px(vals[0]), py(vals[1])
            sx, sy = cx, cy
            out.append(f"M {fmt(cx)} {fmt(cy + dy)}")
            # extra coordinate pairs after M are implicit line-tos
            cmd = "l" if rel else "L"
        elif up == "L":
            cx, cy = px(vals[0]), py(vals[1])
            out.append(f"L {fmt(cx)} {fmt(cy + dy)}")
        elif up == "H":
            cx = px(vals[0])
            out.append(f"L {fmt(cx)} {fmt(cy + dy)}")
        elif up == "V":
            cy = py(vals[0])
            out.append(f"L {fmt(cx)} {fmt(cy + dy)}")
        elif up == "C":
            x1, y1 = px(vals[0]), py(vals[1])
            x2, y2 = px(vals[2]), py(vals[3])
            cx, cy = px(vals[4]), py(vals[5])
            prev_c2 = (x2, y2)
            out.append(
                f"C {fmt(x1)} {fmt(y1 + dy)} {fmt(x2)} {fmt(y2 + dy)} {fmt(cx)} {fmt(cy + dy)}"
            )
        elif up == "S":
            x1, y1 = cx, cy
            if prev_cmd and prev_cmd.upper() in ("C", "S") and prev_c2:
                x1 = 2 * cx - prev_c2[0]
                y1 = 2 * cy - prev_c2[1]
            x2, y2 = px(vals[0]), py(vals[1])
            cx, cy = px(vals[2]), py(vals[3])
            prev_c2 = (x2, y2)
            out.append(
                f"C {fmt(x1)} {fmt(y1 + dy)} {fmt(x2)} {fmt(y2 + dy)} {fmt(cx)} {fmt(cy + dy)}"
            )
        elif up == "Q":
            x1, y1 = px(vals[0]), py(vals[1])
            cx, cy = px(vals[2]), py(vals[3])
            prev_q = (x1, y1)
            out.append(f"Q {fmt(x1)} {fmt(y1 + dy)} {fmt(cx)} {fmt(cy + dy)}")
        elif up == "T":
            x1, y1 = cx, cy
            if prev_cmd and prev_cmd.upper() in ("Q", "T") and prev_q:
                x1 = 2 * cx - prev_q[0]
                y1 = 2 * cy - prev_q[1]
            cx, cy = px(vals[0]), py(vals[1])
            prev_q = (x1, y1)
            out.append(f"Q {fmt(x1)} {fmt(y1 + dy)} {fmt(cx)} {fmt(cy + dy)}")
        elif up == "A":
            raise SystemExit("arc commands are not supported; pick another icon")

        if up not in ("C", "S"):
            prev_c2 = None
        if up not in ("Q", "T"):
            prev_q = None
        prev_cmd = cmd

    return " ".join(out)


def android_xml(d, size_dp=24):
    return f"""<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size_dp}dp"
    android:height="{size_dp}dp"
    android:viewportWidth="960"
    android:viewportHeight="960">
    <path
        android:pathData="{d}"
        android:fillColor="#FFFFFFFF" />
</vector>
"""


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    mode, names = sys.argv[1], sys.argv[2:]
    if mode == "android":
        os.makedirs(DRAWABLE, exist_ok=True)
        for name in names:
            d = absolutise(path_data(fetch(name)), dy=960)
            out = os.path.join(DRAWABLE, f"ic_{name}.xml")
            with open(out, "w") as fh:
                fh.write(android_xml(d))
            print(f"wrote {out}  ({len(d)} chars)")
    elif mode == "web":
        print('<svg xmlns="http://www.w3.org/2000/svg" style="display:none">')
        for name in names:
            d = path_data(fetch(name))
            print(
                f'<symbol id="i-{name}" viewBox="0 -960 960 960">'
                f'<path d="{d}"/></symbol>'
            )
        print("</svg>")
    elif mode == "raw":
        for name in names:
            print(f"<!-- {name} -->")
            print(path_data(fetch(name)))
    else:
        print(f"unknown mode {mode}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
