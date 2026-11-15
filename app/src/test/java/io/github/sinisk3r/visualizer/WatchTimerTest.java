package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class WatchTimerTest {
    @Test
    public void countsOnlyTimeWithNothingOverTheVisuals() {
        WatchTimer t = new WatchTimer();
        assertNull(t.show("a.milk", 0));
        t.cover(true, 5_000);     // menu opens after 5 s
        t.cover(false, 65_000);   // a minute in the menu does not count
        WatchTimer.Shown s = t.show("b.milk", 72_000);
        assertEquals("a.milk", s.name);
        assertEquals(12, s.seconds);
    }

    @Test
    public void aPresetShownWhileCoveredStartsCountingWhenUncovered() {
        WatchTimer t = new WatchTimer();
        t.cover(true, 0);
        t.show("a.milk", 1_000);  // picked in Find: the menu is still open
        t.cover(false, 3_000);
        assertEquals(4, t.seconds(7_000));
    }

    @Test
    public void theSamePresetAgainDoesNotEndTheShowing() {
        WatchTimer t = new WatchTimer();
        t.show("a.milk", 0);
        assertNull(t.show("a.milk", 5_000));  // a settings refresh reports the same preset
        assertEquals(20, t.end(20_000).seconds);
        assertNull("nothing shown after end", t.end(30_000));
    }

    @Test
    public void afterPauseAndResumeTheTimerStartsOver() {
        WatchTimer t = new WatchTimer();
        t.show("a.milk", 0);
        assertEquals(15, t.end(15_000).seconds);  // app paused
        t.show("a.milk", 100_000);               // resumed on the same preset
        assertEquals(3, t.end(103_000).seconds);
    }
}
