#!/bin/bash
# Upscale comparison (docs/next.md S7): renders each preset at 1080p, 720p and 480p (same moment, same music),
# upscales the small ones to 1080p with linear / CAS / FSR 1, scores them against the 1080p render and writes
# crops plus index.html into third_party/thumbs/upscale/ (gitignored).
# Usage: tools/mac-render/upscale-compare.sh [frames, default 180 = 3 s]
# Env: CAS_SHARP, RCAS_STOPS (see upscale-compare.cpp). Build first (see CMakeLists.txt).
set -euo pipefail
cd "$(dirname "$0")/../.."
FRAMES=${1:-180}
BIN=tools/mac-render/build/upscale-compare
TEX=third_party/presets-milkdrop-texture-pack
ROOT=third_party/presets-cream-of-the-crop
OUT=${OUT:-third_party/thumbs/upscale}
mkdir -p "$OUT/renders"
[ -x "$BIN" ] || { echo "build first: cmake --build tools/mac-render/build --target upscale-compare"; exit 1; }

# The TV bench set plus three presets with thin bright lines (halo check).
{
    grep -v '^#' tools/bench-presets.txt
    echo "Waveform/Wire Flat/10.milk"
    echo "Waveform/Wire Flat/224.milk"
    echo "Waveform/Wire Flower/Bmelgren - Tonic.milk"
} > "$OUT/presets.txt"

printf 'name\tsize\tmethod\tpsnr\tssim\tsharp\tover%%\tunder%%\tringOver%%\tringUnder%%\tms\n' > "$OUT/results.tsv"
while IFS= read -r p; do
    [ -n "$p" ] || continue
    name=$(basename "$p" .milk | tr -c 'A-Za-z0-9\n' '-' | tr -s '-' | sed 's/^-//; s/-$//' | cut -c1-40)
    for size in 1920x1080 1280x720 854x480; do
        "$BIN" render "$TEX" "$ROOT/$p" "${size%x*}" "${size#*x}" "$FRAMES" "$OUT/renders/${name}_${size#*x}.png"
    done
    "$BIN" compare "$OUT" "$name" "$OUT/renders/${name}_1080.png" "$OUT/renders/${name}_480.png" \
        "$OUT/renders/${name}_720.png" | tee -a "$OUT/results.tsv"
    cp "$OUT/renders/${name}_1080.png" "$OUT/${name}_full.png"
done < "$OUT/presets.txt"

python3 - "$OUT" <<'EOF'
import csv, html, sys, os
out = sys.argv[1]
rows = list(csv.DictReader(open(os.path.join(out, "results.tsv")), delimiter="\t"))
names = list(dict.fromkeys(r["name"] for r in rows))
methods = ["linear", "cas", "fsr1"]
labels = {"linear": "linear (today)", "cas": "CAS", "fsr1": "FSR 1"}
h = ["""<!doctype html><meta charset="utf-8"><title>Upscale compare</title>
<style>
:root{color-scheme:dark}body{background:#111;color:#ddd;font:14px system-ui;margin:16px}
h2{margin:32px 0 8px}figure{margin:0}figcaption{padding:4px 0 10px;color:#aaa}
.g{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:6px}img{width:100%;image-rendering:pixelated;display:block}
table{border-collapse:collapse;margin:6px 0 12px}td,th{padding:2px 8px;text-align:right;border-bottom:1px solid #333}
td:first-child,th:first-child{text-align:left}.best{color:#7fd67f}
</style>
<h1>Sharpen the stretch: linear vs CAS vs FSR 1</h1>
<p>Each crop is a 480x270 region of the 1080p output, shown 2x (nearest). Columns: today's linear stretch, CAS, FSR 1,
then the real 1080p render. Rows: from 480p, from 720p. psnr/ssim: luma vs the 1080p render (higher is closer).
sharp: edge strength vs the 1080p render (1.00 = as crisp). ring: share of pixels near bright detail that the filter
pushed 16+ levels past its input's local range (halo). "-ds" rows: the 1080p render shrunk then upscaled (upscaler
alone, no render differences). Mac only; TV cost still to measure.</p>"""]
for n in names:
    h.append(f'<h2>{html.escape(n)}</h2><p><a href="{n}_full.png">full 1080p frame</a></p>')
    h.append("<table><tr><th>from</th><th>method</th><th>psnr</th><th>ssim</th><th>sharp</th><th>ring over %</th><th>ring under %</th><th>Mac ms</th></tr>")
    for r in (r for r in rows if r["name"] == n):
        h.append("<tr>" + "".join(f"<td>{html.escape(r[k])}</td>" for k in ["size", "method", "psnr", "ssim", "sharp", "ringOver%", "ringUnder%", "ms"]) + "</tr>")
    h.append("</table>")
    for size in ["480p", "720p"]:
        h.append('<div class="g">')
        for m in methods:
            h.append(f'<figure><img loading="lazy" src="{n}_{size}_{m}.png"><figcaption>{size} &rarr; 1080p, {labels[m]}</figcaption></figure>')
        h.append(f'<figure><img loading="lazy" src="{n}_ref.png"><figcaption>1080p render (reference)</figcaption></figure></div>')
open(os.path.join(out, "index.html"), "w").write("\n".join(h))
print("wrote", os.path.join(out, "index.html"))
EOF
