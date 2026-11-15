package io.github.sinisk3r.visualizer;

/**
 * Even 30 fps pacing, from one measured frame rate window. A preset that settles between 31 and 46 fps cannot
 * hold 60: it is shown on every second vsync (an even 30) instead of a wobbly 35-45, where frames last one or
 * two vsyncs at random and motion judders. Picture size is ResolutionController's job (Auto) or fixed.
 * Pure logic, tested on the JVM.
 */
final class FrameRatePolicy {
    static final float TOO_SLOW_FPS = 20f;        // below this at the smallest size: the preset is too heavy here
    static final float PACE_MIN_FPS = 31f;
    static final float PACE_MAX_FPS = 46f;
    static final float PACED_LOST_FPS = 27f;      // paced but below this: it cannot even hold 30, stop pacing

    enum Kind { NONE, PACE_ON, PACE_OFF }

    /**
     * @param fps    frames per second over the last window
     * @param pacing even-30 pacing is allowed (benches turn it off)
     * @param paced  this preset is paced now
     */
    static Kind decide(float fps, boolean pacing, boolean paced) {
        if (paced) return fps < PACED_LOST_FPS ? Kind.PACE_OFF : Kind.NONE;
        if (pacing && fps >= PACE_MIN_FPS && fps < PACE_MAX_FPS) return Kind.PACE_ON;
        return Kind.NONE;
    }
}
