"""Builds and runs the song detector's host C++ test (song_detector_test.cpp) with the system compiler."""
import os
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))


class SongDetectorTest(unittest.TestCase):
    def test_host_cpp(self):
        cxx = shutil.which("c++") or shutil.which("clang++") or shutil.which("g++")
        if not cxx:
            self.skipTest("no C++ compiler")
        with tempfile.TemporaryDirectory() as tmp:
            exe = os.path.join(tmp, "song_detector_test")
            subprocess.run([cxx, "-std=c++17", "-Wall", "-Werror", "-o", exe,
                            os.path.join(HERE, "song_detector_test.cpp")], check=True)
            r = subprocess.run([exe], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


if __name__ == "__main__":
    unittest.main()
