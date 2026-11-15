"""Checks assets/numbers.txt: channel numbers never change, so these must hold on every commit."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets"


def rows(name):
    return [l.split("\t") for l in (ASSETS / name).read_text(encoding="utf-8").splitlines() if l.strip()]


class NumbersTest(unittest.TestCase):
    def test_numbers_are_unique_and_positive(self):
        nums = [int(r[0]) for r in rows("numbers.txt")]
        self.assertEqual(len(nums), len(set(nums)))
        self.assertGreater(min(nums), 0, "0 and 00 are reserved for favourites and playlists")

    def test_names_are_unique(self):
        names = [r[1] for r in rows("numbers.txt")]
        self.assertEqual(len(names), len(set(names)))

    def test_every_bundled_preset_has_a_number(self):
        names = {r[1] for r in rows("numbers.txt")}
        bundled = {p.name for p in (ASSETS / "presets").rglob("*.milk")}
        self.assertEqual(set(), bundled - names)

    def test_known_numbers_never_move(self):
        # Spot checks from the first numbering (15-Nov-2026). If one fails, a tool renumbered: revert it.
        first = {int(r[0]): r[1] for r in rows("numbers.txt")}
        self.assertEqual(first[1], "$$$ Royal - Mashup (257).milk")
        self.assertEqual(first[2403], "martin - amandio c - a different view of the green machine.milk")
        self.assertEqual(first[9791], "The NG + Flexi + AdamFX + shifter + LuxXx + Many Others - My Final Wish.milk")
        self.assertGreaterEqual(len(first), 9791, "numbers are only ever added")


class AssetListsTest(unittest.TestCase):
    def test_reactivity_rows(self):
        for level, score, name in rows("reactivity.txt"):
            self.assertIn(level, {"0", "1", "3"}, "level 2 is the default and left out")
            self.assertTrue(name.endswith(".milk"))
            int(score)

    def test_bundle_has_no_deaf_or_heavy_presets(self):
        deaf = {n for lv, _, n in rows("reactivity.txt") if lv == "0"}
        heavy = {r[1] for r in rows("heavy.txt")}
        bundled = {p.name for p in (ASSETS / "presets").rglob("*.milk")}
        self.assertEqual(set(), bundled & (deaf | heavy))


if __name__ == "__main__":
    unittest.main()
