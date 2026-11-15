#!/bin/sh
# Holds each preset at a fixed picture height on the connected TV and prints its fps log lines.
# Usage: tools/tv-bench.sh <height> "<preset path on the TV>" ...   (one 10 s fps line per window; first is skipped)
PKG=io.github.sinisk3r.visualizer
H=$1; shift
for p in "$@"; do
  adb shell am force-stop $PKG
  adb logcat -c
  q=$(printf "%s" "$p" | sed "s/'/'\\\\''/g")   # quote for the TV's shell: ' becomes '\''
  adb shell am start -n $PKG/.MainActivity --es preset "'$q'" --ei height "$H" >/dev/null
  sleep 30
  echo "== $(basename "$p")"
  adb logcat -d -s Visualizer | sed -E "s/.*I Visualizer: //" | grep "^fps " | tail -n +2 | cut -c1-60
done
