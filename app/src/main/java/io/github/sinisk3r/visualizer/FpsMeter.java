package io.github.sinisk3r.visualizer;

/**
 * Frames per second, for the settings card: counted in 1 s windows and shown as the average of the last 30, since
 * a per-second number jumps with every preset and blend. Fed on the GL thread, read on the UI thread.
 */
final class FpsMeter {
    private static final long WINDOW_MS = 1000;
    static final int AVERAGE_WINDOWS = 30;
    private long windowStart = -1;
    private int frames;
    private volatile float fps;
    private final float[] recent = new float[AVERAGE_WINDOWS];
    private int recentCount, recentNext;
    private volatile float average;

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
            recent[recentNext] = fps;
            recentNext = (recentNext + 1) % AVERAGE_WINDOWS;
            if (recentCount < AVERAGE_WINDOWS) recentCount++;
            float sum = 0;
            for (int i = 0; i < recentCount; i++) sum += recent[i];
            average = sum / recentCount;
        }
    }

    /** Starts the current window over (new surface size). The average keeps what it has. */
    void reset() {
        windowStart = -1;
        fps = 0;
    }

    /** Average of the last 30 full windows (fewer just after start); 0 before the first one. */
    float average() {
        return average;
    }

    /** The last full window; 0 before the first one. */
    float fps() {
        return fps;
    }
}
