#!/usr/bin/env python3
"""Shortlists Featured candidates from the Mac renders (tools/mac-render), for the user to pick from.

Reads third_party/thumbs/scores.tsv (name, colourful, bright, dark, motion, ms, ok) for the strongly reactive
presets, drops failed, heavy and deaf ones, very dark or washed-out frames and near-still pictures, scores the
rest by colour x motion (as percentiles), and spreads picks over styles and sub-styles. Colour peaks at the 70th
percentile: the most "colourful" frames are clipped primaries that look garish, and taste is the user's call
anyway (they pick ~50 from this list), so the list aims for variety, not loudness. Writes
third_party/thumbs/shortlist.tsv (number, style/sub-style, name, score).

Usage: tools/make-featured.py [count]   (default 200)
"""
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
THUMBS = ROOT / "third_party/thumbs"
ASSETS = ROOT / "app/src/main/assets"
PACK = ROOT / "third_party/presets-cream-of-the-crop"
COUNT = int(sys.argv[1]) if len(sys.argv) > 1 else 200


def rows(path):
    return [l.rstrip("\n").split("\t") for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


def percentiles(values):
    order = sorted(values)
    return {v: order.index(v) / max(1, len(order) - 1) for v in set(values)}


def main():
    scores = [r for r in rows(THUMBS / "scores.tsv") if len(r) == 7 and r[6] == "1"]
    heavy = {r[1] for r in rows(ASSETS / "heavy.txt")}
    numbers = {r[1]: int(r[0]) for r in rows(ASSETS / "numbers.txt")}
    style = {}
    for f in PACK.rglob("*.milk"):
        parts = f.relative_to(PACK).parts
        style.setdefault(f.name, "/".join(parts[:2]) if len(parts) > 2 else parts[0])

    keep = []
    for name, colourful, bright, dark, motion, _ms, _ok in scores:
        colourful, bright, dark, motion = float(colourful), float(bright), float(dark), float(motion)
        if name in heavy or name not in numbers:
            continue
        if bright < 35 or bright > 200 or dark > 0.6 or motion < 4:
            continue
        if not (THUMBS / "png" / (name[:-5] + ".png")).exists():
            continue
        keep.append((name, colourful, motion))
    pc = percentiles([k[1] for k in keep])
    pm = percentiles([k[2] for k in keep])
    def colour(c):
        return c if c <= 0.7 else max(0.0, 0.7 - (c - 0.7) * 2)  # peak at the 70th percentile

    def value(k):
        return colour(pc[k[1]]) * pm[k[2]]

    ranked = sorted(keep, key=lambda k: -value(k))

    # Round robin over sub-styles (best first in each), so every look gets a few places before any gets many.
    by_style = defaultdict(list)
    for k in ranked:
        by_style[style.get(k[0], "?")].append(k)
    picked = []
    queues = sorted(by_style.items(), key=lambda kv: -value(kv[1][0]))
    while len(picked) < COUNT and any(q for _, q in queues):
        for s, q in queues:
            if q and len(picked) < COUNT:
                picked.append((s, q.pop(0)))

    out = THUMBS / "shortlist.tsv"
    out.write_text("".join(f"{numbers[k[0]]}\t{s}\t{k[0]}\t{value(k):.3f}\n" for s, k in picked[:COUNT]),
                   encoding="utf-8")
    print(f"{len(scores)} rendered, {len(keep)} usable, {min(COUNT, len(picked))} shortlisted "
          f"across {len(by_style)} styles -> {out.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
