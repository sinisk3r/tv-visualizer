#!/bin/sh
# Holds each preset at a fixed picture height on the connected TV and prints its fps log lines.
# Usage: tools/tv-bench.sh <height> ["<preset path on the TV>" ...]   (one 10 s fps line per window; first skipped)
# Without presets it runs the standard set in tools/bench-presets.txt (5 presets, ~2.5 min).
PKG=io.github.sinisk3r.visualizer
H=$1; shift
if [ $# -eq 0 ]; then
  R=/sdcard/Android/data/$PKG/files/presets
  while IFS= read -r line; do
    case "$line" in ''|'#'*) continue ;; esac
    set -- "$@" "$R/$line"
  done < "$(dirname "$0")/bench-presets.txt"
fi
for p in "$@"; do
  adb shell am force-stop $PKG
  adb logcat -c
  q=$(printf "%s" "$p" | sed "s/'/'\\\\''/g")   # quote for the TV's shell: ' becomes '\''
  adb shell am start -n $PKG/.MainActivity --es preset "'$q'" --ei height "$H" >/dev/null
  sleep 30
  echo "== $(basename "$p")"
  adb logcat -d -s Visualizer | sed -E "s/.*I Visualizer: //" | grep "^fps " | tail -n +2 | cut -c1-60
done
