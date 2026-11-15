package io.github.sinisk3r.visualizer;

/**
 * "Shuffle this set only?" (mockup A2, screen 0c). With Shuffle on Everything, picking a set (Up/Down to a style
 * while watching, or a style, playlist or Favourites from Find or the 0-list) still plays from every style. So the
 * now-playing tag asks, for 4 s: OK (menu closed) switches Shuffle to This set and plays from the chosen set; any
 * other key, or the wait running out, keeps Everything. Plain logic, tested on the JVM; times are uptime ms.
 */
final class ShuffleAsk {
    static final long WINDOW_MS = 4000;
    /** The tag's second line while asking. */
    static final String LINE = "Shuffling everything  ·  OK: shuffle this one only";

    private long askedAt = -1;

    /**
     * A set the user picked now shows. True when the tag should ask: Shuffle is on Everything and the set is not
     * All (which already is everything).
     */
    boolean chosen(int shuffle, String category, long now) {
        if (shuffle != Settings.SHUFFLE_ALL || category == null || PresetLibrary.ALL.equals(category)) {
            askedAt = -1;
            return false;
        }
        askedAt = now;
        return true;
    }

    boolean isAsking(long now) {
        return askedAt >= 0 && now - askedAt < WINDOW_MS;
    }

    /** A key with the menu closed. True: it is OK while asking, so switch to This set. Any key ends the question. */
    boolean key(boolean ok, long now) {
        boolean yes = ok && isAsking(now);
        askedAt = -1;
        return yes;
    }
}
