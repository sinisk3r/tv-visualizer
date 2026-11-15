package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BarTextTest {
    @Test
    public void styleLineShowsThePresetsStyleThenWhatIsPlaying() {
        assertEquals("Drawing / Liquid  ·  All, shuffled",
                BarText.styleLine("Drawing", "Liquid", "All", false, Settings.SHUFFLE_SET));
        assertEquals("playing its own style: no need to say it twice", "Geometric  ·  in order  ·  held",
                BarText.styleLine("Geometric", PresetLibrary.GENERAL, "Geometric", true, Settings.SHUFFLE_OFF));
        assertEquals("Reaction / Pulse  ·  ★ Favourites, shuffled",
                BarText.styleLine("Reaction", "Pulse", PresetLibrary.FAVOURITES, false, Settings.SHUFFLE_SET));
        assertEquals("Everything plays from all styles, whatever style was picked",
                "Hypnotic  ·  everything shuffled",
                BarText.styleLine("Hypnotic", null, "Geometric", false, Settings.SHUFFLE_ALL));
        assertEquals("unknown style (a preset outside the library)", "All, shuffled",
                BarText.styleLine(null, null, "All", false, Settings.SHUFFLE_SET));
    }

    @Test
    public void clock() {
        assertEquals("0:00", BarText.clock(0));
        assertEquals("0:07", BarText.clock(7));
        assertEquals("0:42", BarText.clock(42));
        assertEquals("1:00", BarText.clock(60));
        assertEquals("5:00", BarText.clock(300));
        assertEquals("0:00", BarText.clock(-3));
    }

    @Test
    public void elapsedIsCappedAtThePresetTime() {
        assertEquals(0, BarText.elapsed(10_000, 10_500, 30));
        assertEquals(12, BarText.elapsed(10_000, 22_900, 30));
        assertEquals(30, BarText.elapsed(10_000, 99_000, 30));
        assertEquals(0, BarText.elapsed(10_000, 9_000, 30));
    }

    @Test
    public void fpsLine() {
        assertEquals("about 60 fps  ·  720p", BarText.fps(59.7f, 720));
        assertEquals("before the first window, the size only", "480p", BarText.fps(0, 480));
        assertEquals("", BarText.fps(60, 0));
    }

    @Test
    public void fpsMeterCountsOneSecondWindows() {
        FpsMeter m = new FpsMeter();
        assertEquals(0, m.fps(), 0);
        long t = 1000;
        m.frame(t);
        for (int i = 0; i < 60; i++) {
            t += 16;
            m.frame(t);
        }
        assertEquals("not yet a full second", 0, m.fps(), 0);
        for (int i = 0; i < 10; i++) {
            t += 17;
            m.frame(t);
        }
        // The window closed on the 63rd frame, 1011 ms in; the 7 after it start the next window.
        assertEquals(63 * 1000f / 1011, m.fps(), 0.01);
        m.reset();
        assertEquals(0, m.fps(), 0);
        assertEquals("a resize keeps the average", 63 * 1000f / 1011, m.average(), 0.01);
    }

    @Test
    public void fpsMeterAveragesTheLast30Seconds() {
        FpsMeter m = new FpsMeter();
        long t = 0;
        m.frame(t);
        // 10 s at 60 fps, then 30 s at 30 fps: the 60s drop out of the average.
        for (int s = 0; s < 10; s++) for (int i = 0; i < 60; i++) m.frame(t += 1000 / 60 + (i < 40 ? 0 : 1));
        assertEquals(60, m.average(), 2);
        for (int s = 0; s < 15; s++) for (int i = 0; i < 30; i++) m.frame(t += 1000 / 30 + (i < 10 ? 1 : 0));
        float mixed = m.average();
        assertTrue("both rates in the last 30 s: " + mixed, mixed > 35 && mixed < 55);
        for (int s = 0; s < 15; s++) for (int i = 0; i < 30; i++) m.frame(t += 1000 / 30 + (i < 10 ? 1 : 0));
        assertEquals(30, m.average(), 1.5);
    }
}
