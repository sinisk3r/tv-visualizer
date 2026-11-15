#!/bin/sh
# Holds each preset at a fixed picture height on the connected TV and prints its fps log lines.
# Usage: tools/tv-bench.sh <height> ["<preset path on the TV>" ...]   (one 10 s fps line per window; first skipped)
# Without presets it runs the standard set in tools/bench-presets.txt (5 presets, ~2.5 min).
# Even 30 fps pacing is off so the raw frame rate shows; PACE=true tools/tv-bench.sh ... turns it on.
PKG=io.github.sinisk3r.visualizer
PACE=${PACE:-false}
INNER=${INNER:-0}   # INNER=480 tools/tv-bench.sh 1080: draw at 480p, stretched onto a 1080p surface
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
  adb shell am start -n $PKG/.MainActivity --es preset "'$q'" --ei height "$H" --ez pace "$PACE" --ei inner "$INNER" >/dev/null
  sleep 30
  echo "== $(basename "$p")"
  adb logcat -d -s Visualizer | sed -E "s/.*I Visualizer: //" | grep -E "^(fps|pace) " | awk '/^pace/ || n++ > 0' | cut -c1-60
done
