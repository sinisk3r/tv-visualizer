#!/usr/bin/env python3
"""Gives every preset a fixed channel number, for typing on the remote (docs/next.md item F).

Numbers never change once given: favourites shared as "try 4321" must keep working. The first run numbers the full
pack in a fixed order (style, sub-style, name; "!" folders skipped like the app does). Later runs keep every
existing number and add new presets at the end. Writes app/src/main/assets/numbers.txt as "<number>\\t<file name>".

Usage: tools/make-numbers.py [presets dir]   (default: the full Cream of the Crop pack)
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "third_party/presets-cream-of-the-crop"
OUT = ROOT / "app/src/main/assets/numbers.txt"


def ordered(src):
    found = []
    for f in src.rglob("*.milk"):
        parts = f.relative_to(src).parts
        if parts[0].startswith("!"):
            continue
        style = parts[0] if len(parts) > 1 else "Other"
        sub = parts[1] if len(parts) > 2 else "General"
        found.append((style.lower(), sub.lower(), f.name.lower(), f.name))
    seen, names = set(), []
    for *_, name in sorted(found):
        if name not in seen:  # a name found twice keeps its first number, as the app counts it once
            seen.add(name)
            names.append(name)
    return names


numbers = {}
if OUT.exists():
    for line in OUT.read_text(encoding="utf-8").splitlines():
        n, name = line.split("\t", 1)
        numbers[name] = int(n)
next_number = max(numbers.values(), default=0) + 1
added = 0
for name in ordered(SRC):
    if name not in numbers:
        numbers[name] = next_number
        next_number += 1
        added += 1
OUT.write_text("".join(f"{n}\t{name}\n" for name, n in sorted(numbers.items(), key=lambda x: x[1])),
               encoding="utf-8")
print(f"{len(numbers)} numbered ({added} new) -> {OUT.relative_to(ROOT)}")
