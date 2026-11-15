package io.github.sinisk3r.visualizer;

/**
 * Decides, from one measured frame rate window, what the renderer does next: change the picture size (Auto),
 * give up on a preset that is too slow everywhere, or pace it to an even 30 fps. Pure logic with no Android
 * types, so it is tested on the JVM; VizRenderer does the measuring and carries out the decision.
 */
final class FrameRatePolicy {
    static final float FAST_FPS = 55f;            // at or above this the GPU has room
    static final long PROBE_AFTER_MS = 10_000;    // smooth this long at a lowered size: try one step up
    static final float TOO_SLOW_FPS = 20f;        // below this at the lowest size: the preset is too heavy here
    /** A preset that settles between these cannot hold 60: show an even 30 (every second vsync) instead of a
     *  wobbly 35-45, where frames last one or two vsyncs at random and motion judders. */
    static final float PACE_MIN_FPS = 31f;
    static final float PACE_MAX_FPS = 46f;
    static final float PACED_LOST_FPS = 27f;      // paced but below this: it cannot even hold 30, stop pacing

    enum Kind { NONE, TOO_SLOW, DOWN, UP, PACE_ON, PACE_OFF }

    static final class Decision {
        final Kind kind;
        final int height;  // for DOWN and UP

        Decision(Kind kind, int height) {
            this.kind = kind;
            this.height = height;
        }
    }

    private static final Decision NONE = new Decision(Kind.NONE, 0);
    private long smoothSince;  // start of an unbroken run of fast windows, 0 if none

    /** Call when measuring starts over (preset load, resize, menu): a fast run must be unbroken. */
    void reset() {
        smoothSince = 0;
    }

    /**
     * @param fps       frames per second over the last window
     * @param now       uptime ms at the end of the window
     * @param height    current picture height
     * @param auto      Auto picture is on
     * @param slowFps   Auto steps down below this (Smooth 55, Sharp 24)
     * @param pacing    even-30 pacing is allowed
     * @param paced     this preset is paced now
     * @param probed    this preset already tried one size up this session
     */
    Decision decide(float fps, long now, int height, boolean auto, float slowFps, boolean pacing, boolean paced,
                    boolean probed) {
        if (paced) return fps < PACED_LOST_FPS ? new Decision(Kind.PACE_OFF, 0) : NONE;
        if (auto) {
            if (fps < TOO_SLOW_FPS && height <= Settings.HEIGHTS[0]) return new Decision(Kind.TOO_SLOW, 0);
            if (fps < slowFps && height > Settings.HEIGHTS[0]) {
                return new Decision(Kind.DOWN, Settings.lower(height));
            } else if (fps >= FAST_FPS && height < 1080 && !probed) {
                if (smoothSince == 0) smoothSince = now;
                if (now - smoothSince >= PROBE_AFTER_MS) return new Decision(Kind.UP, Settings.higher(height));
            } else {
                smoothSince = 0;
            }
        }
        if (pacing && fps >= PACE_MIN_FPS && fps < PACE_MAX_FPS) return new Decision(Kind.PACE_ON, 0);
        return NONE;
    }
}
