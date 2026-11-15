package io.github.sinisk3r.visualizer;

/**
 * Variable resolution for Auto picture. projectM draws at an inner height anywhere between {@code min} and
 * {@code max} (stretched onto a fixed surface, patches 0003/0004), and this decides that height every half
 * second from the measured frame rate:
 * <ul>
 *   <li>too slow: jump straight to the size the frame rate says fits (the GPU's work grows with the pixel
 *       count, so with the square of the height), a little below it for headroom;</li>
 *   <li>fast for 2 s: grow about 5%, but not back to a size that was too slow in the last 2 minutes; a step
 *       up that turns out too slow goes straight back to the size before it and holds there for 2 minutes.
 *       A preset on the edge settles instead of see-sawing.</li>
 * </ul>
 * A shrink that does not raise the frame rate by 5% means the limit is the CPU, not the pixels: it goes back
 * to the size before and stops shrinking for this preset (pacing then decides, as at the smallest size). Such a
 * preset then grows while each step costs it under 5% of its frame rate: the sharpest size that is free. A preset
 * that sits between the shrink and grow thresholds (say 55-57 fps) is treated the same way: size is not what
 * holds it back.
 * Pure logic (no Android), tested on the JVM; VizRenderer measures and applies the height.
 */
final class ResolutionController {
    static final long WINDOW_MS = 500;
    static final long GROW_AFTER_MS = 2000;
    static final long CEILING_MS = 120_000;
    static final int STEP = 8;  // heights are multiples of 8

    final int min, max;
    private final float slowFps, growFps, aimFps;
    private int height;
    private long fastSince;
    private int ceiling;        // a height found too slow recently, 0 if none
    private long ceilingUntil;
    private int beforeGrow;     // the height before the last step up, 0 if the last change was not one
    private long holdUntil;     // no steps up before this (a step up just failed)
    private int beforeShrink;   // the height before the last shrink, 0 if the last change was not one
    private float fpsBeforeShrink;
    private boolean cpuBound;   // shrinking did not help: the size stays, pacing decides
    private float cpuFps;       // its frame rate when found CPU-bound

    /**
     * @param slowFps shrink below this
     * @param growFps grow at or above this, after 2 s
     * @param aimFps  the frame rate a shrink aims for
     */
    ResolutionController(int min, int max, float slowFps, float growFps, float aimFps) {
        this.min = min;
        this.max = max;
        this.slowFps = slowFps;
        this.growFps = growFps;
        this.aimFps = aimFps;
        this.height = max;
    }

    /** Smooth: a full 60 fps, from 360p up to what the screen shows (no cap: a light preset on a 4K TV may climb
     *  to 2160p). */
    static ResolutionController smooth(int screenHeight) {
        return new ResolutionController(360, Math.max(720, screenHeight), 55f, 58f, 60f);
    }

    static ResolutionController smooth() {
        return smooth(720);
    }

    /** Sharp: an even 30 fps (paced), 480p to 1080p (a 1080p surface). It shrinks below 31 fps, because 24-30
     *  cannot be paced and judders, and grows at 42 fps or more. */
    static ResolutionController sharp(int screenHeight) {
        return new ResolutionController(480, Math.max(1080, screenHeight), 31f, 42f, 36f);
    }

    static ResolutionController sharp() {
        return sharp(1080);
    }

    /** Surface heights the picture is stretched onto. The surface follows the drawing size, so a 720p drawing is not
     *  stretched onto 4K buffers (the stretch costs more the bigger the surface: ~5% at 720p, ~11% at 1080p). */
    static final int[] SURFACES = {720, 1080, 1440, 2160};

    /**
     * The surface for drawing height {@code h}, given the current one: up at once to the smallest that holds it;
     * down only when the drawing is 10% under a smaller one, so a preset near a step does not flip between two.
     */
    static int surfaceFor(int h, int current, int max) {
        int fit = SURFACES[SURFACES.length - 1];
        for (int s : SURFACES) {
            if (s >= h) { fit = s; break; }
        }
        fit = Math.min(fit, Math.max(SURFACES[0], max));
        if (fit > current) return fit;
        if (fit < current && h <= fit * 0.9f) return fit;
        return current;
    }

    int height() {
        return height;
    }

    /** The size will not change for now, so pacing may decide: the smallest, the largest, or a preset where
     *  shrinking did not raise the frame rate. */
    boolean settled() {
        return height <= min || height >= max || cpuBound;
    }

    /** A new preset starts at the height it settled at last time (or a typical one). */
    void start(int h) {
        height = clamp(h);
        fastSince = 0;
        ceiling = 0;
        beforeGrow = 0;
        holdUntil = 0;
        beforeShrink = 0;
        cpuBound = false;
    }

    /** Measuring starts over (load, menu): a fast run must be unbroken. */
    void reset() {
        fastSince = 0;
    }

    /** One half-second window. Returns the new height, or 0 to keep the current one. */
    int update(float fps, long now) {
        if (ceiling != 0 && now >= ceilingUntil) ceiling = 0;
        if (beforeShrink != 0) {
            int back = beforeShrink;
            beforeShrink = 0;
            if (fps < slowFps && fps < fpsBeforeShrink * 1.05f) {
                cpuBound = true;  // fewer pixels, same speed: take the sharpness back
                cpuFps = Math.max(fps, fpsBeforeShrink);
                fastSince = 0;
                return set(back);
            }
        }
        if (cpuBound) return growWhileFree(fps, now);
        if (fps < slowFps && height > min && !cpuBound) {
            fastSince = 0;
            ceiling = height;
            ceilingUntil = now + CEILING_MS;
            if (beforeGrow != 0) holdUntil = now + CEILING_MS;
            int h = beforeGrow != 0 ? beforeGrow : clamp((int) (height * Math.sqrt(fps / aimFps) * 0.97));
            if (h >= height) h = clamp(height - STEP);
            beforeGrow = 0;
            beforeShrink = height;
            fpsBeforeShrink = fps;
            return set(h);
        }
        if (fps >= growFps && height < max && now >= holdUntil) {
            if (fastSince == 0) fastSince = now;
            if (now - fastSince < GROW_AFTER_MS) return 0;
            fastSince = now;  // the next step needs another 2 s
            int h = clamp(Math.max(height + STEP, (int) (height * 1.05)));
            if (ceiling != 0 && h >= ceiling) h = clamp(ceiling - STEP);
            if (h <= height) return 0;
            beforeGrow = height;
            return set(h);
        }
        if (fps >= slowFps && fps < growFps && height < max) {
            cpuBound = true;  // between the thresholds: try the size up for free
            cpuFps = fps;
            fastSince = 0;
            return 0;
        }
        if (fps < growFps) fastSince = 0;
        return 0;
    }

    /** CPU-bound: one step up every 2 s while it costs under 5% of the frame rate; a step that costs more goes
     *  back and holds for 2 minutes. */
    private int growWhileFree(float fps, long now) {
        boolean free = fps >= cpuFps * 0.95f;
        if (!free && beforeGrow != 0) {
            int back = beforeGrow;
            beforeGrow = 0;
            holdUntil = now + CEILING_MS;
            fastSince = 0;
            return set(back);
        }
        if (!free || height >= max || now < holdUntil) {
            fastSince = 0;
            return 0;
        }
        if (fastSince == 0) fastSince = now;
        if (now - fastSince < GROW_AFTER_MS) return 0;
        fastSince = now;
        cpuFps = Math.max(cpuFps, fps);
        beforeGrow = height;
        return set(clamp(Math.max(height + STEP, (int) (height * 1.05))));
    }

    private int set(int h) {
        height = h;
        return h;
    }

    private int clamp(int h) {
        h = h / STEP * STEP;
        return Math.max(min, Math.min(max, h));
    }
}
