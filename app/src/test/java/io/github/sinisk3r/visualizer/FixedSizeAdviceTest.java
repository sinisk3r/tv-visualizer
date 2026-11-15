package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FixedSizeAdviceTest {
    @Test
    public void sizesWellAboveWhatAutoSettlesAtAreNotRecommended() {
        // This TV, 15-Nov-2026: Auto settles around 550p in Smooth.
        assertTrue(FixedSizeAdvice.notRecommended(1080, 552));
        assertTrue(FixedSizeAdvice.notRecommended(720, 552));
        assertFalse(FixedSizeAdvice.notRecommended(540, 552));
        assertFalse("nothing learned yet: no advice", FixedSizeAdvice.notRecommended(1080, 0));
    }

    @Test
    public void slowFor30SecondsTellsOncePerSize() {
        FixedSizeAdvice.SlowWatch w = new FixedSizeAdvice.SlowWatch();
        assertFalse(w.feed(30, 1080, false));
        assertFalse(w.feed(30, 1080, false));
        assertTrue(w.feed(30, 1080, false));
        for (int i = 0; i < 5; i++) assertFalse("once", w.feed(30, 1080, false));
        assertFalse(w.feed(30, 720, false));
        assertFalse(w.feed(30, 720, false));
        assertTrue("a new size may be told about", w.feed(30, 720, false));
    }

    @Test
    public void autoMenusAndFastWindowsResetTheCount() {
        FixedSizeAdvice.SlowWatch w = new FixedSizeAdvice.SlowWatch();
        w.feed(30, 1080, false);
        w.feed(30, 1080, false);
        assertFalse("menu open", w.feed(30, 1080, true));
        w.feed(30, 1080, false);
        w.feed(55, 1080, false);
        assertFalse(w.feed(30, 1080, false));
        assertFalse("Auto", w.feed(30, 0, false));
    }
}
