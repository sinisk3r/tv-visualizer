package io.github.sinisk3r.visualizer;

/** Texts on the transport bar and the now-playing tag. Plain functions, so they have JVM tests. */
final class BarText {
    private BarText() {}

    /**
     * Second line under the preset name: the preset's own style and sub-style first, then what is playing and how.
     * "Drawing / Liquid  ·  All, shuffled", "Geometric  ·  in order  ·  held", "Hypnotic  ·  everything shuffled".
     */
    static String styleLine(String style, String sub, String category, boolean locked, int shuffle) {
        String what = style == null || style.isEmpty() ? ""
                : sub == null || sub.isEmpty() || sub.equals(PresetLibrary.GENERAL) ? style : style + " / " + sub;
        String playing;
        if (shuffle == Settings.SHUFFLE_ALL) {
            playing = "everything shuffled";
        } else {
            String mode = shuffle == Settings.SHUFFLE_OFF ? "in order" : "shuffled";
            playing = category == null || category.equals(style) ? mode : category + ", " + mode;
        }
        return (what.isEmpty() ? "" : what + "  ·  ") + playing + (locked ? "  ·  held" : "");
    }

    /** 0:42, 1:00, 5:00. */
    static String clock(long sec) {
        if (sec < 0) sec = 0;
        return (sec / 60) + ":" + (sec % 60 < 10 ? "0" : "") + (sec % 60);
    }

    /** Seconds shown of this preset, capped at its time (beats and song changes can switch earlier). */
    static long elapsed(long shownAtMs, long nowMs, int durationSec) {
        return Math.max(0, Math.min(durationSec, (nowMs - shownAtMs) / 1000));
    }

    /** The small badge on the Shuffle button; null when off. */
    static String shuffleBadge(int shuffle) {
        return shuffle == Settings.SHUFFLE_SET ? "SET" : shuffle == Settings.SHUFFLE_ALL ? "ALL" : null;
    }

    static String shuffleTip(int shuffle) {
        return "Shuffle: " + Settings.shuffleLabel(shuffle).toLowerCase(java.util.Locale.ROOT);
    }

    /** "60 fps  ·  720p now", for the settings card and the frame rate readout. */
    static String fps(float fps, int height) {
        if (height <= 0) return "";
        return (fps > 0 ? Math.round(fps) + " fps  ·  " : "") + height + "p now";
    }
}
