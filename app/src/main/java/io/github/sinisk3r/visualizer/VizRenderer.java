package io.github.sinisk3r.visualizer;

import android.media.audiofx.Visualizer;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Owns projectM and the play order. Every method runs on the GL thread; the activity reaches it with
 * GLSurfaceView.queueEvent.
 */
final class VizRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "Visualizer";

    // Auto picture. Frames are only counted once a preset is on its own: never during the blend from the
    // previous preset (two presets render at once) or right after a load or resize (shader compile, new
    // buffers), since those dips would wrongly mark a smooth preset as slow.
    private static final long BLEND_MS = 3000;       // blend length for timed and beat changes
    private static final long KEY_BLEND_MS = 1500;   // shorter for remote presses, so the remote feels quick
    private static final long GAP_BLEND_MS = 700;    // after a gap in the music: on the first beat of what follows
    private long blendOnce;                          // a blend length for the next change only (0: default)
    private static final long USER_PICK_GRACE_MS = 20_000; // no automatic switch this soon after the user picks
    private long userPickedAt = -USER_PICK_GRACE_MS;
    /** projectM's timer asked for a switch during the grace; it will not ask again, so switch when it ends. */
    private boolean switchAfterGrace;
    private static final long SETTLE_MS = 1500;      // after a load or resize
    private static final long WINDOW_MS = 3000;      // one measurement
    /** Auto picture: the inner height projectM draws at, chosen every half second. */
    private ResolutionController resolution = ResolutionController.smooth();
    private static final long INNER_SETTLE_MS = 250;  // after an inner size change

    interface Listener {
        /** Called on the GL thread whenever the shown preset, style or hold changes. */
        void onPresetShown(String category, File preset, boolean locked, boolean userAction);
        void onError(String message);
        /** Auto picture wants a different render height (GL thread; resize on the UI thread). */
        void onWantHeight(int height);
        /** Even 30 fps on or off (GL thread). The activity then asks for a frame every second vsync. */
        void onPaced(boolean on);
    }

    private final PresetLibrary library;
    private final Settings settings;
    private final String textureDir;
    private final Listener listener;

    /**
     * True while the menu or the preset name is on screen. Anything over the visuals makes this TV compose the
     * whole 4K picture on the GPU, which costs the visuals frames, so Auto picture does not measure then.
     */
    volatile boolean covered;

    /** Even 30 fps pacing allowed (off for speed tests: tools/tv-bench.sh measures the raw frame rate). */
    volatile boolean pacing = true;
    /** Test hook: projectM draws at this height and is stretched onto the surface (0: the surface size). */
    volatile int innerHeight;
    private boolean paced;  // swap interval 2 for the shown preset

    /** Set by the activity while it holds the audio capture; read every frame. */
    volatile Visualizer audio;
    private byte[] wave = new byte[0];

    /** "All" plus every style that is on and has presets left. */
    private final PlayOrder order;
    private boolean locked;
    private boolean ready;

    private long fpsStart;
    private int fpsFrames;
    private long lastFrameNs;
    private long worstGapNs;  // longest frame-to-frame gap in this log window
    private int slowFrames;   // frames over 50 ms in this log window

    private int surfaceHeight;
    private long measureFrom;   // uptime ms; frames before this are not counted
    private long windowStart;
    private int windowFrames;

    VizRenderer(PresetLibrary library, Settings settings, String textureDir, Listener listener,
                String startCategory, String startPreset, boolean startLocked) {
        this.library = library;
        this.settings = settings;
        this.textureDir = textureDir;
        this.listener = listener;
        order = new PlayOrder(source(), startCategory);
        order.rebuild(startPreset);
        if (startLocked && startPreset != null && new File(startPreset).isFile()) order.hold(new File(startPreset));
        locked = startLocked && currentPreset() != null && currentPreset().getPath().equals(startPreset);
    }

    // --- play order ---

    /** The library and settings, as PlayOrder sees them. */
    private PlayOrder.Source source() {
        return new PlayOrder.Source() {
            public List<String> styles() { return library.categories; }
            public List<File> presets(String style) {
                List<File> l = library.byCategory.get(style);
                return l == null ? new ArrayList<>() : l;
            }
            public List<File> all() { return library.all; }
            public boolean styleOn(String style) { return settings.isStyleOn(style); }
            public boolean hidden(File f) { return settings.isHidden(f.getName()); }
            public boolean favourite(File f) { return settings.isFavourite(f.getName()); }
            public boolean skipped(String style, File f) {
                return settings.isHidden(f.getName()) || isHeavy(f) || (settings.skipDeaf() && library.isDeaf(f))
                        || !settings.isSubOn(style, library.subStyleOf(f));
            }
            public int shuffleMode() { return settings.shuffle(); }
            public List<File> shuffle(List<File> list) { return library.shuffled(list); }
        };
    }

    /** Skipped while "Skip heavy presets" is on: listed as heavy, or measured too slow on this TV. */
    private boolean isHeavy(File f) {
        return settings.skipHeavy() && (library.heavy.contains(f.getName()) || settings.isTooSlow(f.getName()));
    }

    String category() {
        return order.category();
    }

    File currentPreset() {
        return order.current();
    }

    // --- GL ---

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        // A new GL context means projectM's GL objects are gone: start a fresh instance.
        ready = false;
        setPaced(false);
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        surfaceHeight = height;
        settle(SETTLE_MS);
        if (ready) {
            Native.resize(width, height);
            applyInnerSize();
            return;
        }
        if (!Native.create(width, height, textureDir, settings.durationSec())) {
            listener.onError("projectM could not start (needs OpenGL ES 3).");
            return;
        }
        ready = true;
        applyInnerSize();
        Native.setTiming(settings.durationSec(), settings.beatCuts());
        Native.setLocked(locked);
        File current = currentPreset();
        if (current != null) show(current, false, false);
        else step(1, false, false);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        if (!ready) return;
        Visualizer v = audio;
        if (v != null) {
            try {
                int size = v.getCaptureSize();
                if (wave.length != size) wave = new byte[size];
                if (v.getWaveForm(wave) == Visualizer.SUCCESS) Native.addPcm(wave, size);
            } catch (IllegalStateException e) {
                // The activity released it between frames.
            }
        }
        int ev = Native.render();
        if ((ev & Native.EV_FAILED) != 0) {
            // Drop presets that do not compile on this GPU, so they never come back this session.
            dropCurrent(false);
        } else if (SystemClock.uptimeMillis() - userPickedAt < USER_PICK_GRACE_MS) {
            // The user just chose this preset (remote or channel number): let them see it.
            if ((ev & Native.EV_SWITCH) != 0) switchAfterGrace = true;
        } else if (switchAfterGrace && !locked) {
            switchAfterGrace = false;
            step(1, true, false);
        } else if ((ev & Native.EV_DROP) != 0 && !locked) {
            step(1, !settings.beatCuts(), false);  // a cut, or a blend when big-beat cuts are off
        } else if ((ev & Native.EV_SONG) != 0 && !locked) {
            blendOnce = GAP_BLEND_MS;
            step(1, true, false);
        } else if ((ev & Native.EV_SWITCH) != 0 && !locked) {
            step(1, (ev & Native.EV_HARD_CUT) == 0, false);
        }
        countFps();
        measure();
    }

    /** Takes the shown preset out of this session's order and moves on. */
    private void dropCurrent(boolean smooth) {
        File bad = order.dropCurrent();
        if (bad != null) {
            Log.w(TAG, "skipping " + bad.getName());
            step(1, smooth, false);
        }
    }

    private void settle(long ms) {
        measureFrom = SystemClock.uptimeMillis() + ms;
        windowStart = 0;
        resolution.reset();
    }

    /** Measures the frame rate once a preset has settled (Auto: half-second windows, else 3 s); variable
     *  resolution and pacing act on it. */
    private void measure() {
        File p = currentPreset();
        if (p == null || surfaceHeight == 0) return;
        long now = SystemClock.uptimeMillis();
        if (covered) {
            settle(SETTLE_MS);
            return;
        }
        if (now < measureFrom) return;
        if (windowStart == 0) {
            windowStart = now;
            windowFrames = 0;
            return;
        }
        windowFrames++;
        if (now - windowStart < (settings.isAuto() ? ResolutionController.WINDOW_MS : WINDOW_MS)) return;
        float fps = windowFrames * 1000f / (now - windowStart);
        windowStart = now;
        windowFrames = 0;
        String name = p.getName();
        if (!paced && settings.isAuto()) {
            int h = resolution.update(fps, now);
            if (h != 0) {
                Log.i(TAG, String.format("auto: %.0f fps, %dp: %s", fps, h, name));
                settings.learn(name, h);
                applyInnerSize();
                settle(INNER_SETTLE_MS);
                return;
            }
            if (resolution.settled() && fps < FrameRatePolicy.TOO_SLOW_FPS) {
                // Already at the smallest picture: the cost is not in pixels (usually hundreds of custom shapes
                // worked out on the CPU), so a smaller picture cannot help. Remember it for "Skip heavy presets".
                Log.i(TAG, String.format("auto: %.0f fps at %dp, too slow: %s", fps, resolution.height(), name));
                settings.markTooSlow(name, fps);
                if (settings.skipHeavy() && !locked) dropCurrent(true);
                return;
            }
            if (!resolution.settled()) return;  // the size may still change: no pacing yet
        }
        switch (FrameRatePolicy.decide(fps, pacing, paced)) {
            case PACE_OFF:
                Log.i(TAG, String.format("pace: %.0f fps, cannot hold 30, pacing off: %s", fps, name));
                setPaced(false);
                settle(SETTLE_MS);
                break;
            case PACE_ON:
                Log.i(TAG, String.format("pace: %.0f fps at %dp, even 30: %s", fps, pictureHeight(), name));
                setPaced(true);
                break;
            case NONE:
                break;
        }
    }

    /** The height projectM draws at now. */
    int pictureHeight() {
        if (settings.isAuto()) return resolution.height();
        return innerHeight > 0 ? innerHeight : surfaceHeight;
    }

    /** Surface height for the chosen picture: Smooth draws inside 720p, Sharp inside 1080p, else fixed. */
    private int surfaceFor() {
        if (!settings.isAuto()) return settings.renderHeight();
        return settings.renderHeight() == Settings.AUTO_SHARP ? 1080 : 720;
    }

    private void applyInnerSize() {
        int h = settings.isAuto() ? resolution.height() : innerHeight;
        if (h >= surfaceHeight) h = 0;  // same size: draw straight to the surface
        Native.setInnerSize(h > 0 ? Settings.widthFor(h) : 0, h);
    }

    private void setPaced(boolean on) {
        if (on == paced) return;
        paced = on;
        // Not eglSwapInterval(2): this TV ignores it. The activity draws on demand every second vsync instead.
        listener.onPaced(on);
    }

    /** A preset starts at the height it settled at last time on this TV. */
    private void startResolution(File preset) {
        ResolutionController want = settings.renderHeight() == Settings.AUTO_SHARP
                ? ResolutionController.sharp() : ResolutionController.smooth();
        if (want.max != resolution.max) resolution = want;
        resolution.start(settings.learnedHeight(preset.getName()));
        if (ready) applyInnerSize();
    }

    private void want(int height) {
        settle(SETTLE_MS);
        if (height != surfaceHeight) listener.onWantHeight(height);
    }

    private void countFps() {
        long ns = System.nanoTime();
        if (lastFrameNs != 0) {
            long gap = ns - lastFrameNs;
            if (gap > worstGapNs) worstGapNs = gap;
            if (gap > 50_000_000L) slowFrames++;
        }
        lastFrameNs = ns;
        long now = SystemClock.uptimeMillis();
        if (fpsStart == 0) fpsStart = now;
        fpsFrames++;
        if (now - fpsStart >= 10_000) {
            File p = currentPreset();
            Log.i(TAG, String.format("fps %.1f  worst %d ms  slow %d  %dp%s  %s",
                    fpsFrames * 1000f / (now - fpsStart), worstGapNs / 1_000_000, slowFrames, pictureHeight(),
                    paced ? " paced" : "",
                    p == null ? "-" : p.getName()));
            fpsStart = now;
            fpsFrames = 0;
            worstGapNs = 0;
            slowFrames = 0;
        }
    }

    // --- actions (from the remote, via queueEvent) ---

    /** Jumps to this preset (typed channel number). If the current style's order skips it (filters, another
     *  style), it is slotted in after the current one, so Next and Back still work from there. */
    void play(File preset) {
        order.play(preset);
        show(preset, true, true);
    }

    /** Next (+1) or previous (-1) preset in the current style; wraps around. */
    void step(int delta, boolean smooth, boolean userAction) {
        File f = order.step(delta);
        if (f == null) {
            listener.onError("No presets left. Turn a style on or unhide presets in the OK menu.");
            return;
        }
        show(f, smooth, userAction);
    }

    void changeCategory(int delta) {
        order.changeCategory(delta);
        step(1, true, true);
    }

    void setLocked(boolean on) {
        locked = on;
        if (ready) Native.setLocked(on);
        listener.onPresetShown(order.category(), currentPreset(), locked, true);
    }

    /** After the menu changed settings: apply timing, re-filter, move on if the current preset is gone. */
    void applySettings() {
        if (ready) Native.setTiming(settings.durationSec(), settings.beatCuts());
        File shown = currentPreset();
        if (shown != null) {
            if (settings.isAuto()) startResolution(shown);
            want(surfaceFor());
        }
        File current = currentPreset();
        order.rebuild(current == null ? null : current.getPath());
        if (currentPreset() == null) {
            locked = false;
            if (ready) Native.setLocked(false);
            step(1, false, true);
        } else {
            listener.onPresetShown(order.category(), currentPreset(), locked, false);
        }
    }

    private void show(File preset, boolean smooth, boolean userAction) {
        long blend = blendOnce > 0 ? blendOnce : userAction ? KEY_BLEND_MS : BLEND_MS;
        if (userAction) userPickedAt = SystemClock.uptimeMillis();
        switchAfterGrace = false;
        blendOnce = 0;
        setPaced(false);  // every preset gets its chance at 60
        if (ready) {
            long t0 = System.nanoTime();
            Native.loadPreset(preset.getPath(), smooth, blend / 1000f);
            Log.i(TAG, String.format("load %d ms  %s  %s", (System.nanoTime() - t0) / 1_000_000,
                    smooth ? "blend" : "cut", preset.getName()));
            // Compile the next preset's shaders in the background, so moving on does not freeze the picture.
            File next = order.next();
            if (next != null) Native.warm(next.getPath(), textureDir);
        }
        if (settings.isAuto()) startResolution(preset);
        settle(SETTLE_MS + (smooth ? blend : 0));
        listener.onPresetShown(order.category(), preset, locked, userAction);
    }

    void release() {
        if (ready) Native.destroy();
        ready = false;
    }
}
