#!/usr/bin/env python3
"""Builds the preset pack that ships inside the APK.

Takes N presets per category from Cream of the Crop (fixed seed, so a rebuild picks the same ones) and the
MilkDrop texture pack, and writes them to app/src/main/assets/. Presets listed in tools/skip.txt (ones that
fail or crawl on a test TV) are left out. Run tools/fetch-packs.sh first.
"""
import random, shutil, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "third_party/presets-cream-of-the-crop"
TEX = ROOT / "third_party/presets-milkdrop-texture-pack/textures"
OUT = ROOT / "app/src/main/assets"
PER_CATEGORY = int(sys.argv[1]) if len(sys.argv) > 1 else 40

skip_file = ROOT / "tools/skip.txt"
skip = {l.strip() for l in skip_file.read_text().splitlines()
        if l.strip() and not l.startswith("#")} if skip_file.exists() else set()

shutil.rmtree(OUT / "presets", ignore_errors=True)
shutil.rmtree(OUT / "textures", ignore_errors=True)
rng = random.Random(4)
total = 0
for cat in sorted(p for p in SRC.iterdir() if p.is_dir() and not p.name.startswith(("!", "."))):
    files = sorted(f for f in cat.rglob("*.milk") if f.name not in skip)
    pick = rng.sample(files, min(PER_CATEGORY, len(files)))
    for f in pick:
        dest = OUT / "presets" / cat.name / f.name
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(f, dest)
    total += len(pick)
    print(f"{cat.name:12} {len(pick):4} of {len(files)}")
shutil.copytree(TEX, OUT / "textures")
print(f"total {total} presets, {sum(1 for _ in (OUT / 'textures').iterdir())} textures")
