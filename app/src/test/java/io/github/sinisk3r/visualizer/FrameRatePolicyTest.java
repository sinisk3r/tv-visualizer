package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;

import io.github.sinisk3r.visualizer.FrameRatePolicy.Kind;

import org.junit.Test;

public class FrameRatePolicyTest {
    private static final float SMOOTH = 55f, SHARP = 24f;
    private final FrameRatePolicy p = new FrameRatePolicy();

    private FrameRatePolicy.Decision auto(float fps, long now, int height) {
        return p.decide(fps, now, height, true, SMOOTH, true, false, false);
    }

    @Test
    public void smoothStepsDownBelow55() {
        FrameRatePolicy.Decision d = auto(50, 3000, 720);
        assertEquals(Kind.DOWN, d.kind);
        assertEquals(540, d.height);
    }

    @Test
    public void sharpKeepsTheSizeDownTo24() {
        assertEquals(Kind.NONE, p.decide(50, 3000, 720, true, SHARP, false, false, false).kind);
        assertEquals(Kind.DOWN, p.decide(20, 3000, 720, true, SHARP, false, false, false).kind);
    }

    @Test
    public void tooSlowOnlyAtTheSmallestSize() {
        assertEquals(Kind.TOO_SLOW, auto(15, 3000, 480).kind);
        assertEquals("a smaller picture may still help", Kind.DOWN, auto(15, 3000, 540).kind);
    }

    @Test
    public void stepsUpAfterTenSecondsOfUnbrokenFastWindows() {
        assertEquals(Kind.NONE, auto(60, 3000, 540).kind);
        assertEquals(Kind.NONE, auto(60, 6000, 540).kind);
        assertEquals(Kind.NONE, auto(60, 9000, 540).kind);
        FrameRatePolicy.Decision d = auto(60, 13000, 540);
        assertEquals(Kind.UP, d.kind);
        assertEquals(720, d.height);
    }

    @Test
    public void aSlowerWindowBreaksTheFastRun() {
        auto(60, 3000, 540);
        auto(60, 6000, 540);
        p.decide(50, 9000, 480, true, SMOOTH, false, false, false);  // at 480 nothing to step down to
        assertEquals(Kind.NONE, auto(60, 12000, 540).kind);
        assertEquals(Kind.NONE, auto(60, 15000, 540).kind);
    }

    @Test
    public void neverProbesTwiceOrAbove1080() {
        assertEquals(Kind.NONE, p.decide(60, 3000, 540, true, SMOOTH, true, false, true).kind);
        assertEquals(Kind.NONE, p.decide(60, 30000, 540, true, SMOOTH, true, false, true).kind);
        assertEquals(Kind.NONE, auto(60, 3000, 1080).kind);
        assertEquals(Kind.NONE, auto(60, 30000, 1080).kind);
    }

    @Test
    public void pacesAt480WhenItCannotReach60() {
        assertEquals(Kind.PACE_ON, auto(39, 3000, 480).kind);
        assertEquals("52 fps is close enough to 60", Kind.NONE, auto(52, 3000, 480).kind);
        assertEquals("under 31: pacing would slow it down", Kind.NONE, auto(28, 3000, 480).kind);
    }

    @Test
    public void pacesAtAFixedSizeToo() {
        assertEquals(Kind.PACE_ON, p.decide(40, 3000, 720, false, SMOOTH, true, false, false).kind);
        assertEquals("benches turn pacing off", Kind.NONE, p.decide(40, 3000, 720, false, SMOOTH, false, false, false).kind);
    }

    @Test
    public void pacedStaysPacedUnlessItCannotHold30() {
        assertEquals(Kind.NONE, p.decide(29.8f, 3000, 480, true, SMOOTH, true, true, false).kind);
        assertEquals(Kind.PACE_OFF, p.decide(25, 3000, 480, true, SMOOTH, true, true, false).kind);
    }
}
