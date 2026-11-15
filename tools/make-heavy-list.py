#!/usr/bin/env python3
"""Lists presets too heavy for TV-class hardware, for the app's "Skip heavy presets" option.

A preset is heavy when HEAVY_SHAPES or more of its custom shape instances are still drawn one at a time. Since
the shape batching patches (patches/projectm/0001, 0002), instances are drawn in batches, except textured
non-additive shapes with a border: each of those flushes the batch and draws its outline on its own, so these presets crawl on a TV
whatever the picture size. Measured at 480p on a Mali-G52 TV with 4 x Cortex-A73 at 1.39 GHz (15-Nov-2026,
docs/performance.md): 1,000+ unbatched instances ran 10-12 fps, 200-300 ran 29-31 fps, 100 or fewer 39-60 fps
(even with 1,536 instances in all). The list goes to app/src/main/assets/heavy.txt as
"<unbatched instances>\\t<file name>"; the app also learns slow presets on each TV, which catches the rest.

Usage: tools/make-heavy-list.py [presets dir]   (default: the full Cream of the Crop pack; fetch-packs.sh first)
"""
import re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "third_party/presets-cream-of-the-crop"
OUT = ROOT / "app/src/main/assets/heavy.txt"
HEAVY_SHAPES = 500


def shape_instances(text):
    """Instances of textured, non-additive shapes with a border: the ones still drawn one at a time."""
    kv = dict(re.findall(r"^([A-Za-z_0-9]+)=(.*)$", text, re.M))
    total = 0
    for i in range(4):
        if kv.get(f"shapecode_{i}_enabled", "0").strip() in ("0", ""):
            continue
        per_frame = " ".join(v for k, v in kv.items() if k.startswith(f"shape_{i}_per_frame"))
        additive = kv.get(f"shapecode_{i}_additive", "0").strip() == "1"
        border = float(kv.get(f"shapecode_{i}_border_a", "0") or 0) > 0.0001
        # Per-frame code can switch either on or off for each instance; count those shapes as unbatched.
        if re.search(r"\bborder_a\s*=", per_frame):
            border = True
        if re.search(r"\badditive\s*=", per_frame):
            additive = False
        textured = kv.get(f"shapecode_{i}_textured", "0").strip() == "1" or bool(
            re.search(r"\btextured\s*=", per_frame))
        # Since patch 0002, borders of untextured shapes are batched too: only textured ones stay one at a time.
        if border and not additive and textured:
            total += int(float(kv.get(f"shapecode_{i}_num_inst", "1") or 1))
    return total


heavy = {}
for f in sorted(SRC.rglob("*.milk")):
    n = shape_instances(f.read_text(encoding="latin-1"))
    if n >= HEAVY_SHAPES:
        heavy[f.name] = max(n, heavy.get(f.name, 0))

OUT.write_text("".join(f"{n}\t{name}\n" for name, n in sorted(heavy.items())), encoding="utf-8")
print(f"{len(heavy)} heavy presets (>= {HEAVY_SHAPES} unbatched shape instances) -> {OUT.relative_to(ROOT)}")
