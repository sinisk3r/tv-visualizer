package io.github.sinisk3r.visualizer;

/**
 * Frames per second over 1 s windows, for the settings card and the "Show frame rate" readout. Fed on the GL
 * thread, read on the UI thread.
 */
final class FpsMeter {
    private static final long WINDOW_MS = 1000;
    private long windowStart = -1;
    private int frames;
    private volatile float fps;

    /** One frame drawn at this time (uptime ms). */
    void frame(long nowMs) {
        if (windowStart < 0) {
            windowStart = nowMs;
            frames = 0;
            return;
        }
        frames++;
        long span = nowMs - windowStart;
        if (span >= WINDOW_MS) {
            fps = frames * 1000f / span;
            windowStart = nowMs;
            frames = 0;
        }
    }

    /** Starts over (new surface): the next window is measured fresh. */
    void reset() {
        windowStart = -1;
        fps = 0;
    }

    /** The last full window; 0 before the first one. */
    float fps() {
        return fps;
    }
}
