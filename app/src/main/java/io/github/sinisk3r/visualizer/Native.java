package io.github.sinisk3r.visualizer;

/** Thin JNI wrapper around one projectM instance. Call only from the GL render thread. */
final class Native {
    static final int EV_SWITCH = 1;    // projectM asks for a new preset (timer or beat)
    static final int EV_HARD_CUT = 2;  // ...as a hard cut, so switch without a blend
    static final int EV_FAILED = 4;    // the last preset failed to load or compile

    static {
        System.loadLibrary("visualizer");
    }

    private Native() {}

    static native boolean create(int width, int height, String textureDir, float presetSeconds);
    static native void resize(int width, int height);
    static native void loadPreset(String path, boolean smooth);
    static native void setTiming(float presetSeconds, boolean beatCuts);
    static native void setLocked(boolean locked);
    static native void addPcm(byte[] data, int count);
    static native int render();
    static native void destroy();
}
