#!/usr/bin/env python3
"""Colours the grey DJ line-art logo with a neon gradient (the visuals' palette), keeping its charcoal ground.

Usage: colour-logo.py art/logo-dj.png art/logo-dj-colour.png   (needs Pillow + numpy)
Lines take their brightness from the source and their hue from a diagonal gradient:
cyan (left) -> violet -> magenta -> amber (right, the raised hand). The brightest cores fade towards white, like a glow.
"""
import sys
import numpy as np
from PIL import Image

src, dst = sys.argv[1], sys.argv[2]
im = np.asarray(Image.open(src).convert("RGB")).astype(np.float32) / 255.0
h, w, _ = im.shape
lum = im @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
bg = np.median(im[:40, :40].reshape(-1, 3), axis=0)  # charcoal from the corner
bg_l = float(bg @ np.array([0.2126, 0.7152, 0.0722]))
line = np.clip((lum - bg_l) / (1 - bg_l), 0, 1)       # 0 on the ground, 1 on the brightest line

stops = np.array([[0.24, 0.96, 1.00],   # cyan
                  [0.55, 0.36, 1.00],   # violet
                  [1.00, 0.24, 0.66],   # magenta
                  [1.00, 0.69, 0.24]],  # amber
                 dtype=np.float32)
# Stretch the gradient over the figure itself (not the canvas), so every colour lands on the lines.
ys, xs = np.nonzero(line > 0.45)
x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
print("figure box", x0, x1, y0, y1)
yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
t = np.clip(((xx - x0) / (x1 - x0)) * 0.85 + ((yy - y0) / (y1 - y0)) * 0.15, 0, 1) * (len(stops) - 1)
i = np.minimum(t.astype(int), len(stops) - 2)
f = (t - i)[..., None]
grad = stops[i] * (1 - f) + stops[i + 1] * f

lit = np.clip(np.power(line, 0.6) * 1.25, 0, 1)[..., None]
core = np.clip((line - 0.75) / 0.25, 0, 1)[..., None] * 0.55   # hottest cores towards white
colour = grad * (1 - core) + core
out = bg * (1 - lit) + colour * lit
Image.fromarray((np.clip(out, 0, 1) * 255).astype(np.uint8)).save(dst)
print("saved", dst)
