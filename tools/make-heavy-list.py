#!/usr/bin/env python3
"""Lists presets too heavy for TV-class hardware, for the app's "Skip heavy presets" option.

A preset is heavy when its custom shapes draw HEAVY_SHAPES or more instances per frame. Each instance runs its
own equations on the CPU and is drawn on its own, so these presets crawl on a TV whatever the picture size
(measured on a Mali-G52 TV with 4 x Cortex-A73 at 1.39 GHz: shapes >= 200 covered 21 of the 38 presets that
needed 480p, 3 of 38 at 540p, none at 720p or 1080p). The list goes to app/src/main/assets/heavy.txt as
"<instances>\\t<file name>"; the app also learns slow presets on each TV, which catches the rest.

Usage: tools/make-heavy-list.py [presets dir]   (default: the full Cream of the Crop pack; fetch-packs.sh first)
"""
import re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "third_party/presets-cream-of-the-crop"
OUT = ROOT / "app/src/main/assets/heavy.txt"
HEAVY_SHAPES = 200


def shape_instances(text):
    kv = dict(re.findall(r"^([A-Za-z_0-9]+)=(.*)$", text, re.M))
    total = 0
    for i in range(4):
        if kv.get(f"shapecode_{i}_enabled", "0").strip() not in ("0", ""):
            total += int(float(kv.get(f"shapecode_{i}_num_inst", "1") or 1))
    return total


heavy = {}
for f in sorted(SRC.rglob("*.milk")):
    n = shape_instances(f.read_text(encoding="latin-1"))
    if n >= HEAVY_SHAPES:
        heavy[f.name] = max(n, heavy.get(f.name, 0))

OUT.write_text("".join(f"{n}\t{name}\n" for name, n in sorted(heavy.items())), encoding="utf-8")
print(f"{len(heavy)} heavy presets (>= {HEAVY_SHAPES} shape instances) -> {OUT.relative_to(ROOT)}")
