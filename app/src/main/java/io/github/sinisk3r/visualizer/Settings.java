package io.github.sinisk3r.visualizer;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Viewer choices from the OK menu. Written on the UI thread, read on the GL thread: every set is replaced,
 * never changed in place, so readers always see a whole value.
 */
final class Settings {
    static final int[] DURATIONS = {15, 30, 60, 120, 300};
    /** Render heights (16:9). The display scales the picture up; lower = smoother on slow TV GPUs. */
    static final int[] HEIGHTS = {480, 540, 720, 1080};
    /** renderHeight value meaning "Auto, smooth" (the default): each preset gets the largest height it runs at
     *  55 fps or more on this TV (a full 60); one that cannot at 480p is paced to an even 30. Motion that keeps
     *  up with the music matters more than sharpness. */
    static final int AUTO = 0;
    /** renderHeight value meaning "Auto, sharp": the largest height that still runs 24 fps or more. */
    static final int AUTO_SHARP = 1;
    /** Choices in the menu, in order. */
    static final int[] PICTURE_CHOICES = {AUTO, AUTO_SHARP, 480, 540, 720, 1080};
    /** "Fixed picture size" under Live and advanced: Off (Auto, Smooth or Sharp) or one height. */
    static final int[] FIXED_CHOICES = {AUTO, 480, 540, 720, 1080};

    /** Shuffle on the bar. Off: in order (style, sub-style, name). This set: shuffled within the style or list
     *  being played (what the app always did, so the default). Everything: shuffled across every style that is on. */
    static final int SHUFFLE_OFF = 0;
    static final int SHUFFLE_SET = 1;
    static final int SHUFFLE_ALL = 2;

    private final SharedPreferences prefs;
    private volatile int durationSec;
    private volatile boolean beatCuts;
    private volatile int renderHeight;
    private volatile boolean skipHeavy;
    private volatile boolean skipDeaf;
    private volatile int shuffle;
    private volatile boolean showPlaying;
    /** Presets measured too slow on this TV even at the lowest picture size. File names. */
    private final SharedPreferences tooSlow;
    /** Auto picture: preset file name to the height it last ran smoothly at on this TV. */
    private final SharedPreferences learned;
    private volatile Set<String> hidden;          // preset file names
    private volatile Set<String> disabledStyles;  // style names, and "Style/Sub-style" keys
    private volatile Set<String> favourites;      // preset file names

    /** Raise when a change makes presets render faster or slower (projectM patches, renderer changes): the first
     *  start after the update forgets what Auto picture learned on the old renderer and measures again.
     *  2: batched custom shapes (patches/projectm/0001, 15-Nov-2026). 3: batched non-additive borders (0002).
     *  4: Smooth steps down below 55 fps (was 45), even 30 pacing below that. */
    static final int RENDERER_GENERATION = 4;
    /** True when this start cleared learned picture sizes because the renderer changed. */
    final boolean relearning;

    Settings(Context ctx) {
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        durationSec = prefs.getInt("duration", 30);
        beatCuts = prefs.getBoolean("beatCuts", true);
        renderHeight = prefs.getInt("renderHeight", AUTO);
        learned = ctx.getSharedPreferences("auto_picture", Context.MODE_PRIVATE);
        skipHeavy = prefs.getBoolean("skipHeavy", true);
        skipDeaf = prefs.getBoolean("skipDeaf", true);
        shuffle = prefs.getInt("shuffle", SHUFFLE_SET);
        showPlaying = prefs.getBoolean("showPlaying", true);
        tooSlow = ctx.getSharedPreferences("too_slow", Context.MODE_PRIVATE);
        int generation = prefs.getInt("rendererGeneration", 1);
        relearning = generation != RENDERER_GENERATION && (!learned.getAll().isEmpty() || !tooSlow.getAll().isEmpty());
        if (generation != RENDERER_GENERATION) {
            if (relearning) forgetLearned();
            prefs.edit().putInt("rendererGeneration", RENDERER_GENERATION).apply();
        }
        hidden = Collections.unmodifiableSet(new HashSet<>(prefs.getStringSet("hidden", Collections.emptySet())));
        disabledStyles = Collections.unmodifiableSet(
                new HashSet<>(prefs.getStringSet("disabledStyles", Collections.emptySet())));
        favourites = Collections.unmodifiableSet(
                new HashSet<>(prefs.getStringSet("favourites", Collections.emptySet())));
    }

    int durationSec() { return durationSec; }
    boolean beatCuts() { return beatCuts; }
    /** The chosen picture: AUTO or a fixed height. */
    int renderHeight() { return renderHeight; }
    boolean isAuto() { return renderHeight == AUTO || renderHeight == AUTO_SHARP; }
    /** Auto picture steps a preset down below this frame rate. */
    float autoSlowFps() { return renderHeight == AUTO_SHARP ? 24f : 55f; }
    boolean skipHeavy() { return skipHeavy; }

    void setSkipHeavy(boolean on) {
        skipHeavy = on;
        prefs.edit().putBoolean("skipHeavy", on).apply();
    }

    /** Skip presets that do not react to the music at all (assets/reactivity.txt level 0). */
    boolean skipDeaf() { return skipDeaf; }

    void setSkipDeaf(boolean on) {
        skipDeaf = on;
        prefs.edit().putBoolean("skipDeaf", on).apply();
    }

    int shuffle() { return shuffle; }

    /** OK on the Shuffle button: Off, This set, Everything, Off ... */
    void cycleShuffle() {
        shuffle = nextShuffle(shuffle);
        prefs.edit().putInt("shuffle", shuffle).apply();
    }

    static int nextShuffle(int mode) {
        return mode == SHUFFLE_OFF ? SHUFFLE_SET : mode == SHUFFLE_SET ? SHUFFLE_ALL : SHUFFLE_OFF;
    }

    static String shuffleLabel(int mode) {
        return mode == SHUFFLE_OFF ? "Off" : mode == SHUFFLE_SET ? "This set" : "Everything";
    }

    /** The now-playing tag top right on every preset change, automatic ones too. Off: only on key presses. */
    boolean showPlaying() { return showPlaying; }

    void setShowPlaying(boolean on) {
        showPlaying = on;
        prefs.edit().putBoolean("showPlaying", on).apply();
    }

    boolean isTooSlow(String presetName) { return tooSlow.contains(presetName); }
    int tooSlowCount() { return tooSlow.getAll().size(); }

    void markTooSlow(String presetName, float fps) {
        tooSlow.edit().putFloat(presetName, fps).apply();
    }

    static int widthFor(int height) {
        return (height * 16 / 9 + 1) & ~1;  // even width: 480p is 854x480
    }

    /** For tests: a fixed picture height for this run only (not saved). */
    void overrideHeight(int height) {
        renderHeight = height;
    }

    void stepPicture(int delta) {
        int i = 0;
        while (i < PICTURE_CHOICES.length - 1 && PICTURE_CHOICES[i] != renderHeight) i++;
        i = Math.max(0, Math.min(PICTURE_CHOICES.length - 1, i + delta));
        setPicture(PICTURE_CHOICES[i]);
    }

    /** Next "Fixed picture size": Off, 480p ... 1080p, Off. Off from a fixed size is Auto, Smooth. */
    static int nextFixed(int renderHeight) {
        int i = 0;
        while (i < FIXED_CHOICES.length && FIXED_CHOICES[i] != renderHeight) i++;
        if (i == FIXED_CHOICES.length) i = 0;  // Auto, Sharp counts as Off
        return FIXED_CHOICES[(i + 1) % FIXED_CHOICES.length];
    }

    /** One of PICTURE_CHOICES: AUTO, AUTO_SHARP or a fixed height. */
    void setPicture(int choice) {
        int before = renderHeight;
        renderHeight = choice;
        // Sizes learned for smooth do not fit sharp and back: measure again.
        if ((before == AUTO || before == AUTO_SHARP) && (renderHeight == AUTO || renderHeight == AUTO_SHARP)
                && before != renderHeight) {
            forgetLearned();
        }
        prefs.edit().putInt("renderHeight", renderHeight).apply();
    }

    /**
     * Height learned for this preset. A preset that never ran here starts at the height most presets settled
     * at on this TV (1080 on a fresh install), so a slow GPU does not stutter on every new preset.
     */
    int learnedHeight(String presetName) {
        int h = learned.getInt(presetName, 0);
        return h != 0 ? h : typicalHeight();
    }

    private int typicalHeight() {
        int[] votes = new int[HEIGHTS.length];
        int best = HEIGHTS.length - 1;
        for (Object v : learned.getAll().values()) {
            if (!(v instanceof Integer)) continue;
            for (int i = 0; i < HEIGHTS.length; i++) {
                if (HEIGHTS[i] == (Integer) v && ++votes[i] > votes[best]) best = i;
            }
        }
        return HEIGHTS[best];
    }

    void learn(String presetName, int height) {
        learned.edit().putInt(presetName, height).apply();
    }

    /** Logs every learned height, so slow presets can be studied off the TV (release builds hide prefs). */
    void logLearned() {
        for (java.util.Map.Entry<String, ?> e : learned.getAll().entrySet()) {
            android.util.Log.i("Visualizer", "learned " + e.getValue() + " " + e.getKey());
        }
        for (java.util.Map.Entry<String, ?> e : tooSlow.getAll().entrySet()) {
            android.util.Log.i("Visualizer", "too slow " + e.getValue() + " fps " + e.getKey());
        }
    }

    int learnedCount() {
        return learned.getAll().size();
    }

    void forgetLearned() {
        learned.edit().clear().apply();
        tooSlow.edit().clear().apply();
    }

    static int lower(int h) {
        for (int i = HEIGHTS.length - 1; i >= 0; i--) if (HEIGHTS[i] < h) return HEIGHTS[i];
        return HEIGHTS[0];
    }

    static int higher(int h) {
        for (int x : HEIGHTS) if (x > h) return x;
        return HEIGHTS[HEIGHTS.length - 1];
    }
    boolean isHidden(String presetName) { return hidden.contains(presetName); }
    int hiddenCount() { return hidden.size(); }
    boolean isStyleOn(String style) { return !disabledStyles.contains(style); }
    boolean isSubOn(String style, String sub) { return !disabledStyles.contains(style + "/" + sub); }
    boolean isFavourite(String presetName) { return favourites.contains(presetName); }
    int favouriteCount() { return favourites.size(); }

    void setSub(String style, String sub, boolean on) {
        setStyle(style + "/" + sub, on);
    }

    void setFavourite(String presetName, boolean on) {
        Set<String> s = new HashSet<>(favourites);
        if (on) s.add(presetName); else s.remove(presetName);
        favourites = Collections.unmodifiableSet(s);
        prefs.edit().putStringSet("favourites", s).apply();
    }

    void setDuration(int sec) {
        durationSec = sec;
        prefs.edit().putInt("duration", durationSec).apply();
    }

    /** Steps through DURATIONS, clamped at both ends. */
    void stepDuration(int delta) {
        int i = 0;
        while (i < DURATIONS.length - 1 && DURATIONS[i] < durationSec) i++;
        i = Math.max(0, Math.min(DURATIONS.length - 1, i + delta));
        durationSec = DURATIONS[i];
        prefs.edit().putInt("duration", durationSec).apply();
    }

    void setBeatCuts(boolean on) {
        beatCuts = on;
        prefs.edit().putBoolean("beatCuts", on).apply();
    }

    void hide(String presetName) {
        if (favourites.contains(presetName)) setFavourite(presetName, false);
        Set<String> s = new HashSet<>(hidden);
        s.add(presetName);
        hidden = Collections.unmodifiableSet(s);
        prefs.edit().putStringSet("hidden", s).apply();
    }

    void unhideAll() {
        hidden = Collections.emptySet();
        prefs.edit().remove("hidden").apply();
    }

    void setStyle(String style, boolean on) {
        Set<String> s = new HashSet<>(disabledStyles);
        if (on) s.remove(style); else s.add(style);
        disabledStyles = Collections.unmodifiableSet(s);
        prefs.edit().putStringSet("disabledStyles", s).apply();
    }

    static String durationLabel(int sec) {
        return sec < 60 ? sec + " s" : (sec / 60) + " min";
    }
}
