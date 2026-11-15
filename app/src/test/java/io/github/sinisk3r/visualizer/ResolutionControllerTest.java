package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ResolutionControllerTest {
    /** Simulates a GPU-bound preset: frame time grows with the pixel count, capped at 60 by vsync. */
    private static float fpsAt(int height, float fpsAt720) {
        float f = fpsAt720 * (720f * 720f) / (height * (float) height);
        return Math.min(60f, f);
    }

    @Test
    public void tooSlowJumpsStraightToASizeThatFits() {
        ResolutionController c = ResolutionController.smooth();
        c.start(720);
        int h = c.update(40, 500);
        assertTrue("one jump, not four steps: " + h, h < 600 && h >= 360);
        assertEquals(0, h % ResolutionController.STEP);
        assertTrue(fpsAt(h, 40) >= 55);
    }

    @Test
    public void growsFivePercentAfterTwoFastSeconds() {
        ResolutionController c = ResolutionController.smooth();
        c.start(480);
        long t = 0;
        for (int i = 0; i < 4; i++) assertEquals(0, c.update(60, t += 500));
        int h = c.update(60, t += 500);
        assertEquals(504, h);
    }

    @Test
    public void settlesOnTheEdgeInsteadOfSeeSawing() {
        ResolutionController c = ResolutionController.smooth();
        c.start(720);
        long t = 0;
        int changes = 0;
        for (int i = 0; i < 120; i++) {  // 60 s
            int h = c.update(fpsAt(c.height(), 50), t += 500);
            if (h != 0) changes++;
        }
        assertTrue("few changes in a minute: " + changes, changes <= 4);
        assertTrue(fpsAt(c.height(), 50) >= 55);
        assertTrue("uses most of the room: " + c.height(), c.height() >= 600);
    }

    @Test
    public void lightPresetsClimbToTheTop() {
        ResolutionController c = ResolutionController.smooth();
        c.start(360);
        long t = 0;
        for (int i = 0; i < 200; i++) c.update(60, t += 500);
        assertEquals(720, c.height());
    }

    @Test
    public void neverLeavesTheRange() {
        ResolutionController c = ResolutionController.smooth();
        c.start(2000);
        assertEquals(720, c.height());
        c.start(100);
        assertEquals(360, c.height());
        assertEquals("at the bottom nothing to shrink", 0, c.update(10, 500));
        assertTrue(c.settled());
    }

    @Test
    public void sharpShrinksToASizeThatHoldsAnEven30() {
        // 24-30 fps cannot be paced (an uneven 25 judders): Sharp shrinks until it runs 31+, then paces to 30.
        ResolutionController c = ResolutionController.sharp();
        c.start(1080);
        int h = c.update(fpsAt(1080, 60), 500);  // about 27 fps at 1080p
        assertTrue("shrinks from 27 fps: " + h, h < 1080 && h >= 480);
        assertTrue("fast enough to pace: " + fpsAt(h, 60), fpsAt(h, 60) >= 31);
        assertEquals(0, c.update(fpsAt(h, 60), 1000));
        assertTrue("settled, so pacing may start", c.settled());
    }

    @Test
    public void sharpAtTheTopMayPace() {
        // A preset at 40 fps at full size: nothing to gain by changing size, so pacing (even 30) decides.
        ResolutionController c = ResolutionController.sharp();
        c.start(1080);
        assertEquals(0, c.update(40, 500));
        assertTrue(c.settled());
    }

    @Test
    public void cpuBoundPresetsKeepTheirSharpness() {
        // Green machine on the TV: ~55 fps at any size, the limit is the CPU.
        ResolutionController c = ResolutionController.smooth();
        c.start(568);
        long t = 0;
        int first = c.update(54, t += 500);
        assertTrue(first < 568);
        assertEquals("no gain: back to the size before", 568, c.update(54.5f, t += 500));
        assertTrue("pacing decides from here", c.settled());
        for (int i = 0; i < 3; i++) assertEquals(0, c.update(54, t += 500));
        assertEquals(568, c.height());
    }

    @Test
    public void cpuBoundPresetsClimbWhileItIsFree() {
        // Learned too small by mistake: 55 fps at any size up to 720p.
        ResolutionController c = ResolutionController.smooth();
        c.start(368);
        long t = 0;
        c.update(54, t += 500);              // shrinks to 360
        c.update(54, t += 500);              // no gain: back to 368, CPU-bound
        for (int i = 0; i < 200; i++) c.update(54.5f, t += 500);
        assertEquals(720, c.height());
    }

    @Test
    public void cpuBoundClimbStopsWhereItStartsToCost() {
        ResolutionController c = ResolutionController.smooth();
        c.start(400);
        long t = 0;
        c.update(50, t += 500);
        c.update(50, t += 500);              // CPU-bound at 400, 50 fps
        for (int i = 0; i < 200; i++) {
            float fps = c.height() <= 560 ? 50f : 40f;  // the GPU becomes the limit above 560p
            c.update(fps, t += 500);
        }
        assertTrue("settles at or just below the edge: " + c.height(), c.height() <= 560 && c.height() >= 520);
    }

    @Test
    public void aShrinkThatHelpsIsKept() {
        ResolutionController c = ResolutionController.smooth();
        c.start(720);
        int h = c.update(40, 500);
        assertEquals(0, c.update(fpsAt(h, 40), 1000));
        assertEquals(h, c.height());
    }

    @Test
    public void presetsBetweenTheThresholdsClimbWhileItIsFree() {
        // Splash on the TV: 55.4 fps at 360p and at any size up to 720p.
        ResolutionController c = ResolutionController.smooth();
        c.start(360);
        long t = 0;
        for (int i = 0; i < 200; i++) c.update(55.4f, t += 500);
        assertEquals(720, c.height());
    }

    @Test
    public void noCapOnA4kScreen() {
        ResolutionController c = ResolutionController.smooth(2160);
        c.start(720);
        long t = 0;
        for (int i = 0; i < 2000; i++) c.update(60, t += 500);
        assertEquals("a light preset climbs to the screen's height", 2160, c.height());
        assertEquals(1080, ResolutionController.sharp(720).max);
    }

    @Test
    public void surfaceFollowsTheDrawingSize() {
        assertEquals(720, ResolutionController.surfaceFor(600, 720, 2160));
        assertEquals("up at once", 1080, ResolutionController.surfaceFor(728, 720, 2160));
        assertEquals(2160, ResolutionController.surfaceFor(1500, 1080, 2160));
        assertEquals("never past the screen", 1080, ResolutionController.surfaceFor(1500, 1080, 1080));
        assertEquals("just under a step: stays", 1080, ResolutionController.surfaceFor(712, 1080, 2160));
        assertEquals("well under: down", 720, ResolutionController.surfaceFor(640, 1080, 2160));
    }
}
