#!/usr/bin/env bash
# Before/after speed check on the TV for a renderer change (docs/next.md item T step 4).
# Installs each APK in turn, runs tools/tv-bench.sh with the same arguments, and prints the mean fps per preset
# side by side. Run one at a time (CLAUDE.md); the TV must be the only adb device or set ANDROID_SERIAL.
#
#   tools/tv-compare.sh before.apk after.apk [tv-bench args, default: 0 (Smooth)]
#   SECS=45 PACE=true tools/tv-compare.sh before.apk app/build/outputs/apk/release/app-release.apk 1
#
# Ends with the after APK installed.
set -euo pipefail
[ $# -ge 2 ] || { sed -n 2,9p "$0"; exit 1; }
BEFORE=$1 AFTER=$2; shift 2
[ $# -gt 0 ] || set -- 0
DIR=$(dirname "$0")
OUT=$(mktemp -d)
for side in before after; do
  apk=$BEFORE; [ $side = after ] && apk=$AFTER
  echo "== $side: $apk" >&2
  adb install -r "$apk" >/dev/null
  "$DIR/tv-bench.sh" "$@" > "$OUT/$side.txt"
done
# Mean of the "fps N" lines under each "== preset" heading.
mean() {
  awk '/^== /{name=substr($0,4)} /^fps /{s[name]+=$2; n[name]++; if(!(name in o)){o[name]=++k}}
       END{for(p in o) printf "%d\t%s\t%.1f\n", o[p], p, s[p]/n[p]}' "$1" | sort -n | cut -f2-
}
join -t $'\t' <(mean "$OUT/before.txt" | sort) <(mean "$OUT/after.txt" | sort) |
  awk -F'\t' 'BEGIN{printf "%-50s %7s %7s %7s\n","preset","before","after","change"}
              {printf "%-50.50s %7.1f %7.1f %+6.1f%%\n",$1,$2,$3,($3-$2)/$2*100}'
echo "raw logs: $OUT" >&2
