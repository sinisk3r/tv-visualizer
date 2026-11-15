import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "update_featured", Path(__file__).resolve().parent.parent / "update-featured.py")
uf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(uf)


class UpdateFeaturedTest(unittest.TestCase):
    def test_log_gives_favourites_in_order_without_styles(self):
        log = ("11-15 I Visualizer: favourite b.milk\n11-15 I Visualizer: favourite style Dancer\n"
               "11-15 I Visualizer: favourite a.milk\n11-15 I Visualizer: favourite b.milk\n")
        self.assertEqual(["b.milk", "a.milk"], uf.parse_log(log))

    def test_grows_never_shrinks_favourites_first(self):
        known = {"a.milk", "b.milk", "c.milk", "d.milk", "h.milk"}
        out = uf.grow(["old.milk"], ["b.milk", "zz.milk"], ["h.milk", "c.milk", "d.milk"], {"h.milk"}, known, 3)
        self.assertEqual(["old.milk", "b.milk", "c.milk"], out)

    def test_favourites_go_in_even_past_the_target(self):
        out = uf.grow(["x.milk"], ["a.milk", "b.milk"], ["c.milk"], set(), {"a.milk", "b.milk", "c.milk"}, 1)
        self.assertEqual(["x.milk", "a.milk", "b.milk"], out)


if __name__ == "__main__":
    unittest.main()
