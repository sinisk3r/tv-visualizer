package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;

import io.github.sinisk3r.visualizer.FrameRatePolicy.Kind;

import org.junit.Test;

public class FrameRatePolicyTest {
    @Test
    public void pacesWhenItCannotReach60() {
        assertEquals(Kind.PACE_ON, FrameRatePolicy.decide(39, true, false));
        assertEquals("52 fps is close enough to 60", Kind.NONE, FrameRatePolicy.decide(52, true, false));
        assertEquals("under 31: pacing would slow it down", Kind.NONE, FrameRatePolicy.decide(28, true, false));
    }

    @Test
    public void benchesTurnPacingOff() {
        assertEquals(Kind.NONE, FrameRatePolicy.decide(40, false, false));
    }

    @Test
    public void pacedStaysPacedUnlessItCannotHold30() {
        assertEquals(Kind.NONE, FrameRatePolicy.decide(29.8f, true, true));
        assertEquals(Kind.PACE_OFF, FrameRatePolicy.decide(25, true, true));
    }
}
