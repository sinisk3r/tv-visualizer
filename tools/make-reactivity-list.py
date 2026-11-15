#!/usr/bin/env python3
"""Scores how much each preset follows the music, for the app's play order.

A preset reacts to music through:
- equations (per-frame, per-pixel, shapes, custom waves) that read bass/mid/treb (and the _att versions) and feed
  the result into something drawn: a built-in variable (zoom, rot, warp, colours, shape size...) or a q variable
  that a shader, shape or wave reads. Audio that only lands in an unused user variable does not count;
- shaders (warp/comp) that read bass/mid/treb/vol or a q variable carrying audio;
- the main waveform, when it is visible (fWaveAlpha or wave_a set);
- custom waves, whose points sit on the audio samples unless the per-point code moves both x and y without
  reading value1/value2.

Levels, written to app/src/main/assets/reactivity.txt as "<level>\\t<score>\\t<file name>":
  0 none    no audio at all; skipped by default ("Skip deaf presets")
  1 wave    only a waveform or custom wave moves with the music
  2 some    audio drives a few things
  3 strong  audio drives many things (score >= STRONG, about the top quarter); played more often
Presets not in the file count as "some".

Usage: tools/make-reactivity-list.py [presets dir]   (default: the full Cream of the Crop pack)
"""
import re, sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "third_party/presets-cream-of-the-crop"
OUT = ROOT / "app/src/main/assets/reactivity.txt"
STRONG = 22  # about the top quarter of the pack

AUDIO = {"bass", "mid", "treb", "bass_att", "mid_att", "treb_att", "vol", "vol_att"}
INPUTS = AUDIO | {"time", "fps", "frame", "progress", "meshx", "meshy", "pixelsx", "pixelsy", "aspectx", "aspecty",
                  "instance", "sample", "value1", "value2", "rad", "ang", "x", "y"}
FRAME_OUT = set("""zoom zoomexp rot warp cx cy dx dy sx sy decay wave_a wave_r wave_g wave_b wave_x wave_y
wave_mystery wave_mode ob_size ob_r ob_g ob_b ob_a ib_size ib_r ib_g ib_b ib_a mv_x mv_y mv_dx mv_dy mv_l mv_r mv_g
mv_b mv_a echo_zoom echo_alpha echo_orient wave_usedots wave_thick wave_additive wave_brighten darken_center gamma
wrap invert brighten darken solarize blur1_min blur2_min blur3_min blur1_max blur2_max blur3_max""".split())
PIXEL_OUT = set("zoom zoomexp rot warp cx cy dx dy sx sy".split())
SHAPE_OUT = set("""x y rad ang tex_ang tex_zoom sides textured additive thick r g b a r2 g2 b2 a2 border_r border_g
border_b border_a""".split())
WAVE_OUT = set("r g b a x y".split())
IDENT = re.compile(r"[A-Za-z_][A-Za-z_0-9]*")
ASSIGN = re.compile(r"^\s*([A-Za-z_][A-Za-z_0-9]*)\s*([-+*/%]?=)(?!=)(.*)$", re.S)
Q = re.compile(r"^q([1-9]|[12][0-9]|3[0-2])$")


def code(kv, prefix):
    """Joins numbered lines like per_frame_1, warp_1 or shape_0_per_frame1 into one string, comments removed."""
    pat = re.compile(re.escape(prefix) + r"_?(\d+)$")
    lines = sorted((int(m.group(1)), v) for k, v in kv.items() if (m := pat.match(k)))
    text = "\n".join(v.lstrip("`") for _, v in lines)
    return re.sub(r"//[^\n]*", "", text)


def statements(text):
    return [s for s in re.split(r"[;\n]", text) if s.strip()]


def taint(text, tainted):
    """Adds variables assigned from tainted ones (to a fixed point); returns the tainted set."""
    stmts = [m.groups() for s in statements(text) if (m := ASSIGN.match(s))]
    changed = True
    while changed:
        changed = False
        for name, _, rhs in stmts:
            if name not in tainted and tainted & set(IDENT.findall(rhs)):
                tainted.add(name)
                changed = True
    return tainted


def num(kv, key, default=0.0):
    try:
        return float(kv.get(key, default))
    except ValueError:
        return default


def score(text):
    kv = {}
    for line in text.splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            kv.setdefault(k.strip(), v.strip())
    frame = code(kv, "per_frame")
    pixel = code(kv, "per_pixel")
    shaders = code(kv, "warp") + "\n" + code(kv, "comp")

    t = taint(frame, set(AUDIO))
    q_audio = {v for v in t if Q.match(v)}
    t_pixel = taint(pixel, set(AUDIO) | q_audio)
    points = 0
    points += len(t & FRAME_OUT) * 2                      # per-frame motion and colour
    points += len(t_pixel & PIXEL_OUT) * 2                # per-pixel motion
    q_audio |= {v for v in t_pixel if Q.match(v)}
    shader_ids = set(IDENT.findall(shaders))
    points += len(shader_ids & (AUDIO | q_audio)) * 2     # shaders

    waves = 0
    if num(kv, "fWaveAlpha", num(kv, "wave_a")) >= 0.01 or re.search(r"\bwave_a\s*=", frame):
        waves += 1
    for i in range(4):
        if kv.get(f"shapecode_{i}_enabled", "0") not in ("0", ""):
            ts = taint(code(kv, f"shape_{i}_per_frame"), set(AUDIO) | q_audio)
            points += len(ts & SHAPE_OUT)
        if kv.get(f"wavecode_{i}_enabled", "0") not in ("0", ""):
            wf = code(kv, f"wave_{i}_per_frame")
            pp = code(kv, f"wave_{i}_per_point")
            tw = taint(wf, set(AUDIO) | q_audio)
            tp = taint(pp, tw | {"value1", "value2"})
            ids = {m.group(1) for s in statements(pp) if (m := ASSIGN.match(s))}
            moves = bool(tp & {"x", "y"}) or not {"x", "y"} <= ids
            if moves:
                waves += 1
            points += len((tw | tp) & WAVE_OUT - {"x", "y"})
    if points == 0:
        return (1 if waves else 0), waves
    total = points + waves
    return (3 if total >= STRONG else 2), total


best = {}
for f in sorted(SRC.rglob("*.milk")):
    level, s = score(f.read_text(encoding="latin-1"))
    if f.name not in best or level > best[f.name][0]:
        best[f.name] = (level, s)

OUT.write_text("".join(f"{lv}\t{s}\t{name}\n" for name, (lv, s) in sorted(best.items()) if lv != 2), encoding="utf-8")
counts = Counter(lv for lv, _ in best.values())
names = ["none", "wave", "some", "strong"]
print(", ".join(f"{names[i]} {counts[i]}" for i in range(4)), f"of {len(best)} -> {OUT.relative_to(ROOT)}")
