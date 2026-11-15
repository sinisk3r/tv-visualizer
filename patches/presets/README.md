# Preset patches

- `cream-of-the-crop-pr7.diff`: [Cream of the Crop pull request #7](https://github.com/projectM-visualizer/presets-cream-of-the-crop/pull/7)
  (mbellew, open): fixes expression parse errors in 67 presets that projectM 4 cannot load (3 of them are in our
  bundle). It applies to our pinned pack (`PackDownloader.COMMIT`, 0180df2). The fixed files ship in
  `app/src/main/assets/fixes` and replace the broken copy wherever the preset is found (`PresetLibrary`).
  On the TV, all 67 load (15-Nov-2026). Drop this when the pack pin moves past a merged #7.
  Rebuild the fixed files: copy the 67 files from the pack, `git apply` this diff, copy them into assets/fixes.
