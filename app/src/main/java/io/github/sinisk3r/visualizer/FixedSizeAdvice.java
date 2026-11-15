package io.github.sinisk3r.visualizer;

/**
 * Advice for fixed picture sizes (Live and advanced > Fixed picture size), from what Auto learned on this TV. A
 * fixed size well above the sizes Auto settles at is "not recommended for your TV"; a fixed size that keeps running
 * slow earns one gentle note suggesting Smooth or Sharp. No 4K choice exists: Auto may climb there on its own.
 */
final class FixedSizeAdvice {
    /** A fixed size this much above the median Auto height is more than this TV holds at 60 fps. */
    static final float OVER_TYPICAL = 1.3f;
    static final float SLOW_FPS = 45f;
    static final int SLOW_WINDOWS = 3;  // fps windows of 10 s: 30 s slow before the note

    private FixedSizeAdvice() {
    }

    /** @param typical the median height Auto settled at on this TV, 0 when nothing is learned yet */
    static boolean notRecommended(int height, int typical) {
        return typical > 0 && height > typical * OVER_TYPICAL;
    }

    /** Watches the 10 s fps windows; says once per size per session that a fixed size runs slow. */
    static final class SlowWatch {
        private int slowWindows;
        private int toldFor;  // the fixed size already told about
        private int watching; // the fixed size being counted

        /** One 10 s window. Returns true the moment the note should show. */
        boolean feed(float fps, int fixedHeight, boolean covered) {
            if (fixedHeight != watching) {
                watching = fixedHeight;
                slowWindows = 0;  // a new size starts its own count
            }
            if (fixedHeight <= 0 || covered) {
                slowWindows = 0;
                return false;
            }
            slowWindows = fps < SLOW_FPS ? slowWindows + 1 : 0;
            if (slowWindows >= SLOW_WINDOWS && toldFor != fixedHeight) {
                toldFor = fixedHeight;
                return true;
            }
            return false;
        }
    }
}
