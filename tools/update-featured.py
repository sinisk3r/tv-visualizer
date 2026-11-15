#!/usr/bin/env python3
"""Grows app/src/main/assets/featured.txt (playlist 001 Featured), never shrinks it.

Adds, in order: the favourites on the connected TV (read from the app's start-up log), then the best-scoring
presets from the Mac renders (third_party/thumbs/shortlist.tsv, from make-featured.py) until the list has
--target entries. Lines already in the file stay where they are. Heavy presets (assets/heavy.txt) are skipped.

  tools/update-featured.py                  # favourites from the TV (adb), then shortlist up to 50
  tools/update-featured.py --no-tv --target 60
Then bump versionCode in app/build.gradle (assets changed) and rebuild.
"""
import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/assets/featured.txt"
SHORTLIST = ROOT / "third_party/thumbs/shortlist.tsv"
HEAVY = ROOT / "app/src/main/assets/heavy.txt"
NUMBERS = ROOT / "app/src/main/assets/numbers.txt"


def tv_favourites():
    """Favourite preset file names, in the order they were added, from the app's start-up log."""
    pkg = "io.github.sinisk3r.visualizer"
    subprocess.run(["adb", "logcat", "-c"], check=True)
    subprocess.run(["adb", "shell", "am", "force-stop", pkg], check=True)
    subprocess.run(["adb", "shell", "am", "start", "-n", pkg + "/.MainActivity"], check=True,
                   stdout=subprocess.DEVNULL)
    subprocess.run(["sleep", "6"])
    log = subprocess.run(["adb", "logcat", "-d", "-s", "Visualizer"], check=True, capture_output=True,
                         text=True).stdout
    return parse_log(log)


def parse_log(log):
    out = []
    for line in log.splitlines():
        i = line.find(" favourite ")
        if i < 0 or " favourite style " in line:
            continue
        name = line[i + len(" favourite "):].strip()
        if name.endswith(".milk") and name not in out:
            out.append(name)
    return out


def read_lines(p):
    if not p.exists():
        return []
    return [l.strip() for l in p.read_text(encoding="utf-8").splitlines() if l.strip() and not l.startswith("#")]


def grow(existing, favourites, shortlist, heavy, known, target):
    out = list(existing)
    for f in favourites:
        if f not in out and f in known:
            out.append(f)
    for f in shortlist:
        if len(out) >= target:
            break
        if f not in out and f not in heavy and f in known:
            out.append(f)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", type=int, default=50)
    ap.add_argument("--no-tv", action="store_true", help="skip reading favourites from the TV")
    a = ap.parse_args()
    known = {l.split("\t", 1)[1] for l in NUMBERS.read_text(encoding="utf-8").splitlines() if "\t" in l}
    shortlist = [l.split("\t")[2] for l in SHORTLIST.read_text(encoding="utf-8").splitlines() if l.count("\t") >= 2]
    favs = [] if a.no_tv else tv_favourites()
    existing = read_lines(OUT)
    heavy = {l.split("\t")[-1] for l in read_lines(HEAVY)}  # lines are "<number>\t<file name>"
    out = grow(existing, favs, shortlist, heavy, known, a.target)
    OUT.write_text("# Playlist 001 Featured: one preset file name per line. Grow with tools/update-featured.py.\n"
                   + "".join(f + "\n" for f in out), encoding="utf-8")
    print(f"featured.txt: {len(existing)} before, {len(out)} now ({len(favs)} favourites on the TV)",
          file=sys.stderr)


if __name__ == "__main__":
    main()
