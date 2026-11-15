package io.github.sinisk3r.visualizer;

/**
 * How long the current preset has been on screen with nothing over it (menu, 0-list, app paused). The activity
 * tells it about changes; {@link #show} and {@link #end} hand back the showing that ended, for
 * {@link WatchHistory}. Plain logic, tested on the JVM.
 */
final class WatchTimer {
    /** A showing that ended: the preset's file name and the uncovered seconds. */
    static final class Shown {
        final String name;
        final long seconds;

        Shown(String name, long seconds) {
            this.name = name;
            this.seconds = seconds;
        }
    }

    private String name;
    private long countedMs;
    private long since = -1;  // uptime ms the uncovered stretch began; -1 while covered
    private boolean covered;

    /** A new preset is on screen: ends the previous showing and returns it (null if there was none). */
    Shown show(String preset, long nowMs) {
        if (preset != null && preset.equals(name)) return null;
        Shown done = end(nowMs);
        name = preset;
        countedMs = 0;
        since = covered || preset == null ? -1 : nowMs;
        return done;
    }

    /** Something now covers the visuals (true) or nothing does (false). */
    void cover(boolean on, long nowMs) {
        if (on == covered) return;
        covered = on;
        if (on) {
            if (since >= 0) countedMs += nowMs - since;
            since = -1;
        } else if (name != null) {
            since = nowMs;
        }
    }

    /** Uncovered seconds so far for the current preset. */
    long seconds(long nowMs) {
        return (countedMs + (since >= 0 ? nowMs - since : 0)) / 1000;
    }

    /** Ends the current showing (app paused or closing) and returns it; the timer then waits for {@link #show}. */
    Shown end(long nowMs) {
        if (name == null) return null;
        Shown s = new Shown(name, seconds(nowMs));
        name = null;
        countedMs = 0;
        since = -1;
        return s;
    }

    String current() {
        return name;
    }
}
