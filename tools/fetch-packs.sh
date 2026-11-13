#!/bin/sh
# Downloads the preset and texture packs into third_party/ (not committed: 156 MB).
set -e
cd "$(dirname "$0")/../third_party"
[ -d presets-cream-of-the-crop ] || git clone --depth 1 https://github.com/projectM-visualizer/presets-cream-of-the-crop.git
[ -d presets-milkdrop-texture-pack ] || git clone --depth 1 https://github.com/projectM-visualizer/presets-milkdrop-texture-pack.git
