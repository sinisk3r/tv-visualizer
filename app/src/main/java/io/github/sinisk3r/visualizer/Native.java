package io.github.sinisk3r.visualizer;

/** Thin JNI wrapper around one projectM instance. Call only from the GL render thread, except warm(). */
final class Native {
    static final int EV_SWITCH = 1;    // projectM asks for a new preset (timer or beat)
    static final int EV_HARD_CUT = 2;  // ...as a hard cut, so switch without a blend
    static final int EV_FAILED = 4;    // the last preset failed to load or compile
    static final int EV_SONG = 8;      // sound came back after a gap (new song or drop): quick blend
    static final int EV_DROP = 16;     // the music got much louder at once: cut to the next preset

    static {
        System.loadLibrary("visualizer");
    }

    private Native() {}

    static native boolean create(int width, int height, String textureDir, float presetSeconds);
    static native void resize(int width, int height);
    /** smooth: blend from the current preset over blendSec seconds; otherwise cut. */
    static native void loadPreset(String path, boolean smooth, float blendSec);
    /** Compiles this preset's shaders on a background thread so a later loadPreset is quick. Any thread. */
    static native void warm(String path, String textureDir);
    static native void setTiming(float presetSeconds, boolean beatCuts);
    static native void setLocked(boolean locked);
    static native void addPcm(byte[] data, int count);
    static native int render();
    static native void destroy();
}
