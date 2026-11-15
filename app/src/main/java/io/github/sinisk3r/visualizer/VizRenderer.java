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
    private final FrameRatePolicy policy = new FrameRatePolicy();

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
    private boolean paced;  // swap interval 2 for the shown preset

    /** Set by the activity while it holds the audio capture; read every frame. */
    volatile Visualizer audio;
    private byte[] wave = new byte[0];

    /** "All" plus every style that is on and has presets left. */
    private List<String> categories = new ArrayList<>();
    private String category;
    private List<File> order = new ArrayList<>();
    private int position = -1;
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
    /** Presets that already tried a step up this session, so a borderline one cannot flip back and forth. */
    private final Set<String> probed = new HashSet<>();

    VizRenderer(PresetLibrary library, Settings settings, String textureDir, Listener listener,
                String startCategory, String startPreset, boolean startLocked) {
        this.library = library;
        this.settings = settings;
        this.textureDir = textureDir;
        this.listener = listener;
        category = startCategory;
        rebuild(startPreset);
        if (startLocked && startPreset != null && currentPreset() == null && new File(startPreset).isFile()) {
            // A held preset stays on even if a filter (heavy, deaf, a style switched off) would skip it.
            order.add(0, new File(startPreset));
            position = 0;
        }
        locked = startLocked && currentPreset() != null && currentPreset().getPath().equals(startPreset);
    }

    // --- play order ---

    private List<File> visible(String cat) {
        List<File> out = new ArrayList<>();
        if (PresetLibrary.FAVOURITES.equals(cat)) {
            // Favourites play even from styles that are switched off: a star is a stronger choice.
            for (File f : library.all) {
                if (settings.isFavourite(f.getName()) && !settings.isHidden(f.getName())) out.add(f);
            }
        } else if (PresetLibrary.ALL.equals(cat)) {
            for (String c : library.categories) {
                if (!c.equals(PresetLibrary.ALL) && settings.isStyleOn(c)) addVisible(c, out);
            }
        } else {
            addVisible(cat, out);
        }
        return out;
    }

    private void addVisible(String cat, List<File> out) {
        List<File> list = library.byCategory.get(cat);
        if (list == null) return;
        for (File f : list) {
            if (!settings.isHidden(f.getName()) && !isHeavy(f) && !(settings.skipDeaf() && library.isDeaf(f))
                    && settings.isSubOn(cat, library.subStyleOf(f))) {
                out.add(f);
            }
        }
    }

    /**
     * Play order for a style under the bar's Shuffle setting. Off: library order. This set: shuffled within the
     * style (what the app always did). Everything: shuffled across every style that is on, whatever style is picked.
     * TODO(menu phase 2): Everything ignores Up/Down styles for now; decide whether picking a style turns it off,
     * and give lists (Featured, smart lists) their own sets once the Find panel has them.
     */
    private List<File> ordered(String cat) {
        switch (settings.shuffle()) {
            case Settings.SHUFFLE_OFF: return visible(cat);
            case Settings.SHUFFLE_ALL: return library.shuffled(visible(PresetLibrary.ALL));
            default: return library.shuffled(visible(cat));
        }
    }

    /** Skipped while "Skip heavy presets" is on: listed as heavy, or measured too slow on this TV. */
    private boolean isHeavy(File f) {
        return settings.skipHeavy() && (library.heavy.contains(f.getName()) || settings.isTooSlow(f.getName()));
    }

    /** Recomputes styles and order after a settings change; keeps `keepPath` first if it is still allowed. */
    private void rebuild(String keepPath) {
        List<String> cats = new ArrayList<>();
        cats.add(PresetLibrary.ALL);
        if (!visible(PresetLibrary.FAVOURITES).isEmpty()) cats.add(PresetLibrary.FAVOURITES);
        for (String c : library.categories) {
            if (!c.equals(PresetLibrary.ALL) && settings.isStyleOn(c) && !visible(c).isEmpty()) cats.add(c);
        }
        categories = cats;
        if (!categories.contains(category)) category = PresetLibrary.ALL;
        order = ordered(category);
        position = -1;
        if (keepPath != null) {
            for (int i = 0; i < order.size(); i++) {
                if (order.get(i).getPath().equals(keepPath)) {
                    order.add(0, order.remove(i));
                    position = 0;
                    break;
                }
            }
        }
    }

    String category() {
        return category;
    }

    File currentPreset() {
        return position >= 0 && position < order.size() ? order.get(position) : null;
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
            return;
        }
        if (!Native.create(width, height, textureDir, settings.durationSec())) {
            listener.onError("projectM could not start (needs OpenGL ES 3).");
            return;
        }
        ready = true;
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
        File bad = currentPreset();
        if (bad != null && order.size() > 1) {
            Log.w(TAG, "skipping " + bad.getName());
            order.remove(position);
            position--;
            step(1, smooth, false);
        }
    }

    private void settle(long ms) {
        measureFrom = SystemClock.uptimeMillis() + ms;
        windowStart = 0;
        policy.reset();
    }

    /** Measures the frame rate in 3 s windows once a preset has settled; Auto picture and pacing act on it. */
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
        if (now - windowStart < WINDOW_MS) return;
        float fps = windowFrames * 1000f / (now - windowStart);
        windowStart = now;
        windowFrames = 0;
        String name = p.getName();
        FrameRatePolicy.Decision d = policy.decide(fps, now, surfaceHeight, settings.isAuto(), settings.autoSlowFps(),
                pacing, paced, probed.contains(name));
        switch (d.kind) {
            case PACE_OFF:
                Log.i(TAG, String.format("pace: %.0f fps, cannot hold 30, pacing off: %s", fps, name));
                setPaced(false);
                settle(SETTLE_MS);
                break;
            case TOO_SLOW:
                // Already at the smallest picture: the cost is not in pixels (usually hundreds of custom shapes
                // worked out on the CPU), so a smaller picture cannot help. Remember it for "Skip heavy presets".
                Log.i(TAG, String.format("auto: %.0f fps at %dp, too slow: %s", fps, surfaceHeight, name));
                settings.markTooSlow(name, fps);
                if (settings.skipHeavy() && !locked) dropCurrent(true);
                break;
            case DOWN:
                Log.i(TAG, String.format("auto: %.0f fps at %dp, down to %dp: %s", fps, surfaceHeight, d.height, name));
                settings.learn(name, d.height);
                want(d.height);
                break;
            case UP:
                Log.i(TAG, String.format("auto: %.0f fps at %dp, trying %dp: %s", fps, surfaceHeight, d.height, name));
                probed.add(name);
                settings.learn(name, d.height);
                want(d.height);
                break;
            case PACE_ON:
                Log.i(TAG, String.format("pace: %.0f fps at %dp, even 30: %s", fps, surfaceHeight, name));
                setPaced(true);
                break;
            case NONE:
                break;
        }
    }

    private void setPaced(boolean on) {
        if (on == paced) return;
        paced = on;
        // Not eglSwapInterval(2): this TV ignores it. The activity draws on demand every second vsync instead.
        listener.onPaced(on);
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
                    fpsFrames * 1000f / (now - fpsStart), worstGapNs / 1_000_000, slowFrames, surfaceHeight,
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
        int i = -1;
        for (int k = 0; k < order.size(); k++) {
            if (order.get(k).getPath().equals(preset.getPath())) { i = k; break; }
        }
        if (i < 0) {
            i = Math.min(position + 1, order.size());
            order.add(i, preset);
        }
        position = i;
        show(preset, true, true);
    }

    /** Next (+1) or previous (-1) preset in the current style; wraps around. */
    void step(int delta, boolean smooth, boolean userAction) {
        if (order.isEmpty()) {
            listener.onError("No presets left. Turn a style on or unhide presets in the OK menu.");
            return;
        }
        position = Math.floorMod(position + delta, order.size());
        show(order.get(position), smooth, userAction);
    }

    void changeCategory(int delta) {
        int i = Math.max(0, categories.indexOf(category));
        category = categories.get(Math.floorMod(i + delta, categories.size()));
        order = ordered(category);
        position = -1;
        step(1, true, true);
    }

    void setLocked(boolean on) {
        locked = on;
        if (ready) Native.setLocked(on);
        listener.onPresetShown(category, currentPreset(), locked, true);
    }

    /** After the menu changed settings: apply timing, re-filter, move on if the current preset is gone. */
    void applySettings() {
        if (ready) Native.setTiming(settings.durationSec(), settings.beatCuts());
        File shown = currentPreset();
        if (shown != null) {
            want(settings.isAuto() ? settings.learnedHeight(shown.getName()) : settings.renderHeight());
        }
        File current = currentPreset();
        rebuild(current == null ? null : current.getPath());
        if (currentPreset() == null) {
            locked = false;
            if (ready) Native.setLocked(false);
            step(1, false, true);
        } else {
            listener.onPresetShown(category, currentPreset(), locked, false);
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
            if (order.size() > 1) Native.warm(order.get(Math.floorMod(position + 1, order.size())).getPath(), textureDir);
        }
        if (settings.isAuto()) want(settings.learnedHeight(preset.getName()));
        settle(SETTLE_MS + (smooth ? blend : 0));
        listener.onPresetShown(category, preset, locked, userAction);
    }

    void release() {
        if (ready) Native.destroy();
        ready = false;
    }
}
